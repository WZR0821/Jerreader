#!/usr/bin/env python3
"""Serial, monitored local validation. No retries, Simulator GUI, or global shutdown."""
import contextlib
import ctypes
import datetime
import fcntl
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parent.parent
MODES = {"doctor", "host-test", "build", "analyze", "test", "ui-test", "android", "android-test"}
POLL_SECONDS = 5
COOL_SECONDS = 30
COOL_TIMEOUT = 300
# Load average and thermal throttling are recorded but never queue, delay or
# stop a job (user decision, 2026-11-13). Evidence: both documented WindowServer
# watchdog crashes happened below CPU saturation (one on an idle machine), in the
# display driver, while runs at load 200+ completed. The load gate (50 abort,
# then the 150 queue threshold of 2026-10-05) and the thermal stop only made
# jobs fail. Serial locking, foreign-build detection, memory, disk and deadlines
# are unchanged. See docs/IOS_SAFE_TESTING.md.
# Simulator/emulator phases still prefer to start on a settled host: wait for
# load <= 2.5 per logical core (20 on this 8-thread host; raised from 1 at the
# user's request on 2026-11-12), but only up to COOL_TIMEOUT, then continue.
COOL_LOAD_PER_CPU = 2.5
QUEUE_TIMEOUT = 3600
_DAEMON_LOG_STATES = {}


class SafetyError(RuntimeError):
    pass


class MonitorUnavailable(SafetyError):
    pass


class PhaseFailure(SafetyError):
    pass


class QueueTimeout(SafetyError):
    pass


def say(message):
    print("safe_xcodebuild: " + message, flush=True)


@contextlib.contextmanager
def finish_cleanup():
    """A second Ctrl-C must not abandon child/device cleanup halfway through."""
    previous = {sig: signal.signal(sig, signal.SIG_IGN)
                for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)}
    try:
        yield
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def verify_test_report(report):
    try:
        counts = [report[key] for key in ("totalTestCount", "passedTests", "failedTests", "skippedTests")]
        if any(type(n) is not int or n < 0 for n in counts):
            raise ValueError()
        total, passed, failed, skipped = counts
        if total < 1 or passed < 1 or failed != 0 or passed + skipped != total:
            raise ValueError()
    except (KeyError, TypeError, ValueError) as error:
        raise PhaseFailure("xcresult does not show any passing tests with zero failures; inspect the result bundle") from error


# Telemetry commands (ps, sysctl, pmset, ...) may take this long before the
# monitor counts as unavailable and the run fails closed. Raised from 8 to 30 s
# at the user's request on 2026-11-13: during simulator boot at load ~250, `ps`
# took longer than 8 s and stopped XCTest before any test ran. Thresholds and
# fail-closed behaviour are unchanged.
MONITOR_COMMAND_TIMEOUT = 30


def read(command, env=None):
    try:
        return subprocess.check_output(command, text=True, stderr=subprocess.PIPE,
                                       timeout=MONITOR_COMMAND_TIMEOUT, env=env).strip()
    except (OSError, subprocess.SubprocessError) as error:
        raise MonitorUnavailable("health/configuration command failed: " + shlex.join(command)) from error


def parse_health(cpus, loads, memory, pressure, thermal, free_bytes):
    """Fail closed: absent/malformed telemetry must never silently allow a build."""
    try:
        free = re.search(r"System-wide memory free percentage:\s*(\d+)%", memory)
        values = [float(v) for v in loads.strip("{} ").split()]
        if free is None or len(values) != 3:
            raise ValueError()
        sample = dict(cpus=int(cpus), load=values[0], memory_free=int(free[1]),
                      pressure=int(pressure), disk_gib=free_bytes / (1024 ** 3))
        if (sample["cpus"] < 1 or not 0 <= sample["memory_free"] <= 100
                or sample["pressure"] not in (1, 2, 4)
                or any(not 0 <= v < 100000 for v in values)):
            raise ValueError()
        speed = re.search(r"CPU_Speed_Limit\s*=\s*(\d+)", thermal)
        scheduler = re.search(r"CPU_Scheduler_Limit\s*=\s*(\d+)", thermal)
        warning = re.search(r"(?:Thermal|Performance)\w*\s*=\s*([1-9]\d*)", thermal, re.I)
        if speed is None or scheduler is None:
            raise ValueError()
        sample["throttled"] = int(speed[1]) < 100 or int(scheduler[1]) < 100 or bool(warning)
        return sample
    except (TypeError, ValueError) as error:
        raise MonitorUnavailable("missing or malformed system telemetry; nothing will be launched") from error


def health():
    return parse_health(
        read(["/usr/sbin/sysctl", "-n", "hw.logicalcpu"]),
        read(["/usr/sbin/sysctl", "-n", "vm.loadavg"]),
        read(["/usr/bin/memory_pressure", "-Q"]),
        read(["/usr/sbin/sysctl", "-n", "kern.memorystatus_vm_pressure_level"]),
        read(["/usr/bin/pmset", "-g", "therm"]), shutil.disk_usage(ROOT).free)


def unsafe(sample, starting=False):
    # Only memory and disk stop or queue work. sample["load"] and
    # sample["throttled"] stay in health.jsonl for diagnosis.
    if sample["pressure"] != 1 or sample["memory_free"] < (20 if starting else 15):
        return "memory pressure/free memory is unsafe"
    if sample["disk_gib"] < 12:
        return "less than 12 GiB of disk space remains"
    return None


def cool(sample, allow_high_load=False):
    return not unsafe(sample, starting=True) and (allow_high_load or sample["load"] <= sample["cpus"] * COOL_LOAD_PER_CPU)


def gradle_log_state(text):
    # Only trust explicit registry state, not CPU usage or a generic sleep log.
    states = re.findall(r"\[org\.gradle\.launcher\.daemon\.server\.DaemonRegistryUpdater\] "
                        r"Marking the daemon as (idle|busy), address:", text)
    return states[-1] if states else None


def gradle_log_idle(text):
    return gradle_log_state(text) == "idle"


def gradle_daemon_idle(pid):
    if not str(pid).isdigit():
        return False
    # Unknown/custom daemon homes remain queued. Never guess that they are idle.
    homes = {Path.home() / ".gradle"}
    if os.environ.get("GRADLE_USER_HOME"):
        homes.add(Path(os.environ["GRADLE_USER_HOME"]))
    try:
        logs = sorted({p.resolve() for home in homes for p in (home / "daemon").glob("*/daemon-%s.out.log" % pid)})
        if len(logs) != 1:
            return False
        stat = logs[0].stat()
        identity = (stat.st_dev, stat.st_ino)
        previous = _DAEMON_LOG_STATES.get(logs[0])
        if previous and previous[:2] == (identity, stat.st_size) and previous[2] == stat.st_mtime_ns:
            return previous[3] == "idle"
        with logs[0].open("rb") as stream:
            if previous and previous[0] == identity and 0 < stat.st_size - previous[1] <= 65536:
                stream.seek(max(0, previous[1] - 512))
                state = gradle_log_state(stream.read(66048).decode("utf-8", errors="replace")) or previous[3]
            else:
                # Idle daemons can append housekeeping messages for hours. Find
                # the last explicit transition with a bounded backwards scan,
                # then inspect only appended bytes on subsequent polls.
                end = stat.st_size
                floor = max(0, end - 4 * 1024 * 1024)
                state = None
                while end > floor and state is None:
                    start = max(floor, end - 65536)
                    stream.seek(start)
                    state = gradle_log_state(stream.read(end - start + 512).decode("utf-8", errors="replace"))
                    end = start
        _DAEMON_LOG_STATES[logs[0]] = (identity, stat.st_size, stat.st_mtime_ns, state)
        return state == "idle"
    except OSError:
        return False


def foreign_work(processes, owned_group=None, owned_cache=None, idle_daemon=None):
    idle_daemon = idle_daemon or (lambda pid: False)
    for line in processes.splitlines():
        fields = line.strip().split(None, 3)
        if len(fields) < 4:
            continue
        pid, group, executable, args = fields
        if owned_group is not None and group == str(owned_group):
            continue
        name = Path(executable).name
        # XCBuildService may launch compiler workers outside xcodebuild's process
        # group. Their exact fixed cache path identifies this serialized build.
        if (owned_cache and name in ("swiftc", "swift-frontend") and owned_cache in args):
            continue
        if name in ("xcodebuild", "swiftc", "swift-frontend"):
            return "another compiler/build is running (pid " + pid + ")"
        if "org.gradle.launcher.daemon.bootstrap.GradleDaemon" in args:
            if idle_daemon(pid):
                continue
            return "a busy or unverified Gradle daemon is present (pid " + pid + ")"
        # Persistent Kotlin/Gradle worker servers are not themselves evidence of
        # an active build. Their requesting Gradle client/daemon is checked here.
        if re.search(r"GradleWrapperMain|org\.gradle\.launcher\.", args):
            return "another Gradle/compiler task is present (pid " + pid + ")"
        if name.startswith("qemu-system") or name == "emulator":
            return "an Android emulator is running (pid " + pid + ")"
    return None


def external_processes(processes, owned_group=None, owned_groups=None):
    """Exclude only this command's process group and its live descendants.

    A --no-daemon Gradle invocation can spawn a single-use JVM in another group.
    Its parent chain identifies ownership without matching project names.
    """
    rows = [line.strip().split(None, 4) for line in processes.splitlines()]
    rows = [row for row in rows if len(row) == 5]
    owned = {row[0] for row in rows if owned_group is not None and row[2] == str(owned_group)}
    while True:
        descendants = {row[0] for row in rows if row[1] in owned}
        if descendants <= owned:
            break
        owned |= descendants
    if owned_groups is not None:
        owned_groups.update(int(row[2]) for row in rows if row[0] in owned and row[0] == row[2])
    return "\n".join(" ".join([row[0], *row[2:]]) for row in rows if row[0] not in owned)


def foreign_reason(owned_group=None, owned_cache=None, owned_groups=None):
    processes = read(["/bin/ps", "-axo", "pid=,ppid=,pgid=,comm=,args="])
    return foreign_work(external_processes(processes, owned_group, owned_groups), owned_group, owned_cache,
                        idle_daemon=gradle_daemon_idle)


def check_foreign(owned_group=None, owned_cache=None):
    owned_groups = set()
    reason = foreign_reason(owned_group, owned_cache, owned_groups)
    if reason:
        raise SafetyError(reason)
    return owned_groups


def child_process_ids(parent):
    # libproc returns a PID count (not a byte count). Query only the command's
    # children: even numeric `ps -a` can stall on unrelated macOS processes.
    library = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
    query = library.proc_listchildpids
    query.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_int]
    query.restype = ctypes.c_int
    buffer = (ctypes.c_int * 4096)()
    count = query(parent, buffer, ctypes.sizeof(buffer))
    if count < 0 or count >= len(buffer):
        raise MonitorUnavailable("cannot read command descendants")
    return list(buffer[:count])


def owned_process_groups(group):
    """Follow only this command's parent chain; never inspect foreign groups."""
    pending, seen, groups = [group], set(), set()
    while pending:
        pid = pending.pop()
        if pid in seen:
            continue
        seen.add(pid)
        try:
            if os.getpgid(pid) == pid:
                groups.add(pid)
            pending.extend(child_process_ids(pid))
        except ProcessLookupError:
            pass  # A child exited between ownership samples.
    return groups


@contextlib.contextmanager
def validation_lock(wait=False, timeout=QUEUE_TIMEOUT):
    # Never unlink an flock file: doing so lets two processes lock different inodes.
    path = Path(tempfile.gettempdir()) / ("jerreader-validation-%s.lock" % os.getuid())
    fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        started = time.monotonic()
        last_message = None
        while True:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError as error:
                if not wait:
                    raise SafetyError("another Jerreader validation owns the host lock") from error
                now = time.monotonic()
                if now - started >= timeout:
                    raise QueueTimeout("host queue wait expired; no command was launched") from error
                if last_message is None or now - last_message >= 45:
                    say("queued: another Jerreader job owns the host lock; waiting serially")
                    last_message = now
                time.sleep(min(POLL_SECONDS, timeout - (now - started)))
        os.ftruncate(fd, 0)
        os.write(fd, str(os.getpid()).encode())
        yield
    finally:
        os.close(fd)


def options(argv, env):
    if len(argv) > 1 or (argv and argv[0] not in MODES):
        raise SafetyError("use exactly one mode: doctor | host-test | build | analyze | test | ui-test | android")
    mode = argv[0] if argv else "build"
    config = env.get("JERREADER_CONFIGURATION", "Debug")
    jobs = env.get("JERREADER_BUILD_JOBS", "1")
    if config not in ("Debug", "Release") or jobs != "1":
        raise SafetyError("configuration must be Debug/Release; queued builds require exactly 1 job")
    queue_timeout = env.get("JERREADER_QUEUE_TIMEOUT_SECONDS", str(QUEUE_TIMEOUT))
    if not queue_timeout.isdecimal() or not 1 <= int(queue_timeout) <= 43200:
        raise SafetyError("queue timeout must be 1 to 43200 seconds")
    # Off by default. Set only on a command explicitly authorized by the user;
    # never persist these exceptions in project settings or a shell profile.
    # JERREADER_SESSION_ALLOW_THERMAL_THROTTLING is obsolete and ignored:
    # throttling no longer stops anything. The load exception now only skips
    # the bounded settle wait between simulator/emulator phases.
    load_exception = env.get("JERREADER_SESSION_ALLOW_HIGH_LOAD", "0")
    if load_exception not in ("0", "1"):
        raise SafetyError("session load exception must be 0 or 1")
    external_exception = env.get("JERREADER_SESSION_ALLOW_EXTERNAL_ACTIVITY", "0")
    if external_exception not in ("0", "1"):
        raise SafetyError("session external activity exception must be 0 or 1")
    target = "JerreaderUITests" if mode == "ui-test" else "JerreaderTests"
    filters = ["-only-testing:" + target]
    destination = env.get("JERREADER_SIMULATOR_DESTINATION", "")
    android_scope = env.get("JERREADER_ANDROID_VALIDATION_SCOPE", "full")
    if (android_scope not in ("full", "ui") or
        ("JERREADER_ANDROID_VALIDATION_SCOPE" in env and mode != "android-test")):
        raise SafetyError("Android validation scope must be full/ui and only applies to android-test")
    android_variant = env.get("JERREADER_ANDROID_PACKAGE_VARIANT", "debug")
    if (android_variant not in ("debug", "release") or
        ("JERREADER_ANDROID_PACKAGE_VARIANT" in env and mode != "android")):
        raise SafetyError("Android package variant must be debug/release and only applies to Android builds")
    release_smoke = env.get("JERREADER_ANDROID_RELEASE_SMOKE", "0")
    if release_smoke not in ("0", "1") or ("JERREADER_ANDROID_RELEASE_SMOKE" in env and mode != "android-test"):
        raise SafetyError("Release smoke check must be 0/1 and only applies to Android device tests")
    udid = None
    if mode in ("test", "ui-test"):
        if env.get("JERREADER_ALLOW_SIMULATOR") != "1":
            raise SafetyError("simulator use requires explicit user approval and JERREADER_ALLOW_SIMULATOR=1")
        match = re.fullmatch(r"platform=iOS Simulator,id=([0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12})", destination)
        if not match:
            raise SafetyError("set exactly one simulator destination: platform=iOS Simulator,id=<UDID>")
        udid = match[1].upper()
        if env.get("JERREADER_TEST_FILTER"):
            selections = env["JERREADER_TEST_FILTER"].split(",")
            if len(selections) > 8 or any(not re.fullmatch(target + r"/[A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)?", s) for s in selections):
                raise SafetyError("select at most 8 classes/methods in " + target)
            filters = ["-only-testing:" + s for s in selections]
    elif mode == "android-test":
        if env.get("JERREADER_ALLOW_SIMULATOR") != "1":
            raise SafetyError("Android emulator requires explicit user approval and JERREADER_ALLOW_SIMULATOR=1")
        if not re.fullmatch(r"platform=Android,serial=emulator-5580,avd=[A-Za-z0-9_-]+", destination):
            raise SafetyError("set one Android destination: platform=Android,serial=emulator-5580,avd=<AVD>")
        if env.get("JERREADER_TEST_FILTER"):
            raise SafetyError("Android device tests use the reviewed offline test suite")
    elif env.get("JERREADER_TEST_FILTER"):
        raise SafetyError("test filters only apply to test/ui-test")
    return dict(mode=mode, config=config, jobs=jobs, filters=filters,
                udid=udid, destination=destination, dry=env.get("JERREADER_DRY_RUN") == "1",
                queue_timeout=int(queue_timeout), android_scope=android_scope, android_variant=android_variant, android_release_smoke=release_smoke == "1",
                allow_high_load=load_exception == "1",
                allow_external_activity=external_exception == "1")


class Supervisor:
    def __init__(self, opts, developer, env):
        self.opts, self.developer, self.env = opts, developer, env
        self.logdir = None
        self.sequence = 0
        self.owned_simulator = None
        self.latest = None
        self.next_inventory_check = 0
        self.owned_groups = set()

    def simctl(self, *args):
        return ["/usr/bin/xcrun", "simctl", *args]

    def devices(self):
        if self.developer is None:
            # Android-only hosts need no Xcode; process checks still detect booted
            # iOS guests via launchd_sim, plus Android emulators.
            rows = read(["/bin/ps", "-axo", "pid=,comm="]).splitlines()
            return [dict(udid="process-" + parts[0], state="Booted")
                    for parts in (row.strip().split(None, 1) for row in rows)
                    if len(parts) == 2 and Path(parts[1]).name == "launchd_sim"]
        try:
            data = json.loads(read(self.simctl("list", "devices", "available", "-j"), self.env))
            devices = [d for group in data["devices"].values() for d in group]
            if any("state" not in d or "udid" not in d for d in devices):
                raise ValueError()
            return devices
        except (ValueError, KeyError, TypeError) as error:
            raise MonitorUnavailable("cannot read simulator inventory") from error

    def record(self, phase, sample):
        self.latest = sample
        if self.logdir:
            with (self.logdir / "health.jsonl").open("a") as stream:
                stream.write(json.dumps(dict(time=datetime.datetime.now().isoformat(), phase=phase, **sample)) + "\n")

    def check(self, phase, starting=False, group=None):
        sample = health()
        self.record(phase, sample)
        reason = unsafe(sample, starting)
        if reason:
            raise SafetyError(reason + ": " + json.dumps(sample))
        if self.opts["allow_external_activity"]:
            if group:
                self.owned_groups.update(owned_process_groups(group))
        else:
            self.owned_groups.update(check_foreign(group, str(ROOT / ".build/SafeDerivedData") if group else None))
        if not self.opts["allow_external_activity"] and self.logdir and time.monotonic() >= self.next_inventory_check:
            unexpected = [d for d in self.devices() if d["state"] != "Shutdown"
                          and d["udid"].upper() != self.owned_simulator]
            if unexpected:
                raise SafetyError("another simulator became active during validation")
            self.next_inventory_check = time.monotonic() + 15
        return sample

    def preflight(self):
        self.check("preflight", starting=True)
        if self.opts["allow_external_activity"] and not self.opts["udid"]:
            return
        devices = self.devices()
        booted = [d for d in devices if d["state"] != "Shutdown" and
                  (not self.opts["allow_external_activity"] or d["udid"].upper() == self.opts["udid"])]
        if booted:
            raise SafetyError("a simulator is already active; shut down that specific device before validation: "
                              + ", ".join(d["udid"] for d in booted))
        if self.opts["udid"] and not any(d["udid"].upper() == self.opts["udid"] for d in devices):
            raise SafetyError("the selected simulator is missing/unavailable")

    def admission(self, phase):
        sample = health()
        self.record(phase, sample)
        if sample["disk_gib"] < 12:
            raise SafetyError("less than 12 GiB of disk space remains")
        # A known queue condition already prevents launch. Avoid contacting
        # CoreSimulator while memory is short or a foreign build is starting it;
        # the full inventory is still required once these conditions clear.
        reason = unsafe(sample, starting=True)
        if reason:
            return reason
        if not self.opts["allow_external_activity"]:
            reason = foreign_reason()
            if reason:
                return reason
        elif not self.opts["udid"]:
            return None
        devices = self.devices()
        if self.opts["udid"] and not any(d["udid"].upper() == self.opts["udid"] for d in devices):
            raise SafetyError("the selected simulator is missing/unavailable")
        if any(d["state"] != "Shutdown" and d["udid"].upper() != self.owned_simulator and
               (not self.opts["allow_external_activity"] or d["udid"].upper() == self.opts["udid"]) for d in devices):
            reason = reason or "another simulator is active"
        return reason

    def wait_for_turn(self, phase):
        started = time.monotonic()
        stable_since = None
        waited = False
        last_message = None
        while True:
            try:
                reason = self.admission(phase + "-queue")
            except MonitorUnavailable as error:
                # No child has been launched here. Keep the bounded queue
                # closed until telemetry is complete; runtime checks still
                # propagate this error and clean up an active task.
                reason = "system monitoring unavailable; waiting: " + str(error)
            now = time.monotonic()
            if waited and now - started >= self.opts["queue_timeout"]:
                raise QueueTimeout(phase + " queue wait expired; this phase was not launched")
            if reason:
                waited = True
                stable_since = None
            elif not waited:
                return
            else:
                stable_since = stable_since if stable_since is not None else now
                if now - stable_since >= COOL_SECONDS:
                    say(phase + ": queue cleared; running one job")
                    return
            if now - started >= self.opts["queue_timeout"]:
                raise QueueTimeout(phase + " queue wait expired; this phase was not launched")
            if last_message is None or now - last_message >= 45:
                say(phase + " queued: " + (reason or "confirming 30 continuous seconds of stable resources"))
                last_message = now
            time.sleep(min(POLL_SECONDS, self.opts["queue_timeout"] - (now - started)))

    def stop_group(self, process):
        # Also track single-use Gradle JVMs which create their own group. Only
        # proven descendants are owned; never kill idle external daemons.
        try:
            if self.opts["allow_external_activity"]:
                self.owned_groups.update(owned_process_groups(process.pid))
            else:
                external_processes(read(["/bin/ps", "-axo", "pid=,ppid=,pgid=,comm=,args="]),
                                   process.pid, self.owned_groups)
        except SafetyError:
            pass  # Still clean up the process group(s) already known to be ours.
        groups = self.owned_groups | {process.pid}
        for sig, delay in ((signal.SIGINT, 3), (signal.SIGTERM, 3), (signal.SIGKILL, 0)):
            # Reap a dead leader before signalling its group again. macOS can return
            # EPERM for a group containing only an unreaped zombie.
            process.poll()
            alive = False
            for group in groups:
                try:
                    os.killpg(group, sig)
                    alive = True
                except ProcessLookupError:
                    pass
            if not alive:
                break
            if delay:
                time.sleep(delay)
        process.wait(timeout=8)
        self.owned_groups.clear()

    def run(self, phase, command, timeout=1800):
        self.wait_for_turn(phase)
        self.check(phase + "-start", starting=True)
        self.sequence += 1
        logfile = self.logdir / ("%02d-%s.log" % (self.sequence, phase))
        say(phase + "; log=" + str(logfile.relative_to(ROOT)))
        started = time.monotonic()
        last_message = started
        with logfile.open("w") as stream:
            stream.write(shlex.join(command) + "\n")
            stream.flush()
            process = subprocess.Popen(["/usr/bin/nice", "-n", "10", *command], cwd=ROOT,
                                       env=self.env, stdout=stream, stderr=subprocess.STDOUT,
                                       start_new_session=True)
            try:
                while process.poll() is None:
                    self.check(phase, group=process.pid)
                    if time.monotonic() - started > timeout:
                        raise SafetyError(phase + " exceeded its wall-clock deadline")
                    if time.monotonic() - last_message >= 45:
                        say(phase + " running; load=%.2f memory_free=%s%%" %
                            (self.latest["load"], self.latest["memory_free"]))
                        last_message = time.monotonic()
                    time.sleep(POLL_SECONDS)
                result = process.returncode
                if result != 0:
                    raise PhaseFailure(phase + " failed (exit %s); inspect %s; no retry" % (result, logfile.relative_to(ROOT)))
            finally:
                # Also reap remaining same-group children after a failed/exited parent.
                with finish_cleanup():
                    self.stop_group(process)
        say(phase + " passed")

    def cooldown(self, phase):
        """Bounded settle wait before a simulator/emulator phase.

        Load never stops the run: after COOL_TIMEOUT the next phase starts
        anyway. Memory, disk and foreign work still stop it through check().
        """
        say(phase + (": wait for 30 continuous seconds of resource stability; session load exception active"
                     if self.opts["allow_high_load"] else ": wait up to 5 minutes for 30 continuous seconds of load <= %g/core" % COOL_LOAD_PER_CPU))
        started = time.monotonic()
        stable_since = None
        last_message = started
        sample = None
        while time.monotonic() - started < COOL_TIMEOUT:
            sample = self.check(phase)
            now = time.monotonic()
            stable_since = (stable_since if stable_since is not None else now) if cool(
                sample, allow_high_load=self.opts["allow_high_load"]) else None
            if stable_since is not None and now - stable_since >= COOL_SECONDS:
                return
            if now - last_message >= 45:
                say(phase + ": waiting; load=%.2f" % sample["load"])
                last_message = now
            time.sleep(POLL_SECONDS)
        say(phase + ": load did not settle within 5 minutes%s; continuing, load alone does not stop a run"
            % ("" if sample is None else " (load=%.2f)" % sample["load"]))

    def common(self):
        return [str(self.developer / "usr/bin/xcodebuild"), "-project", str(ROOT / "Jerreader.xcodeproj"),
                "-scheme", "Jerreader", "-configuration", self.opts["config"],
                "-derivedDataPath", str(ROOT / ".build/SafeDerivedData"), "-jobs", self.opts["jobs"],
                "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO", "COMPILER_INDEX_STORE_ENABLE=NO",
                "SWIFT_ENABLE_BATCH_MODE=NO", "OTHER_SWIFT_FLAGS=$(inherited) -j1", "ONLY_ACTIVE_ARCH=YES"]

    def test_arguments(self):
        return ["-parallel-testing-enabled", "NO", "-parallel-testing-worker-count", "1",
                "-maximum-concurrent-test-simulator-destinations", "1",
                "-enableCodeCoverage", "NO", *self.opts["filters"]]

    def execute(self):
        mode = self.opts["mode"]
        if mode == "host-test":
            self.host_tests()
        elif mode == "android":
            self.run("android", [str(ROOT / "gradlew"), "--no-daemon", "--no-parallel", "--max-workers=1",
                "-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8 -XX:ActiveProcessorCount=1 -XX:ParallelGCThreads=1 -XX:ConcGCThreads=1",
                "-Pkotlin.compiler.execution.strategy=in-process",
                ":shared:testAndroidHostTest", ":androidApp:testDebugUnitTest",
                ":androidApp:lintDebug", ":androidApp:assembleDebug", ":androidApp:assembleDebugAndroidTest"] +
                ([":androidApp:lintRelease", ":androidApp:assembleRelease"] if self.opts["android_variant"] == "release" else []))
        elif mode == "android-test":
            self.cooldown("before-android-emulator")
            self.run("android-device-tests", ["/usr/bin/python3", str(ROOT / "scripts/android_device_validation.py"),
                self.opts["destination"], str(self.logdir), self.opts["android_scope"]], timeout=1800)
        elif mode in ("build", "analyze"):
            self.run(mode, self.common() + ["-destination", "generic/platform=iOS", mode])
        else:
            # Compile once while all simulators are shut down; run that build exactly once.
            args = self.common() + self.test_arguments()
            self.run("build-for-testing", args + ["-destination", self.opts["destination"], "build-for-testing"])
            self.cooldown("before-simulator-boot")
            self.preflight()  # Catch devices/foreign work started during compilation/cooldown.
            self.owned_simulator = self.opts["udid"]
            self.run("simulator-boot", self.simctl("boot", self.owned_simulator), timeout=120)
            self.run("simulator-ready", self.simctl("bootstatus", self.owned_simulator, "-b"), timeout=180)
            self.cooldown("before-test-execution")
            self.run(mode, args + ["-destination", self.opts["destination"], "-destination-timeout", "30",
                "-test-timeouts-enabled", "YES", "-default-test-execution-time-allowance", "120",
                "-maximum-test-execution-time-allowance", "180",
                "-collect-test-diagnostics", "never", "-resultBundlePath", str(self.logdir / (mode + ".xcresult")),
                "test-without-building"], timeout=1800)
            report = json.loads(read(["/usr/bin/xcrun", "xcresulttool", "get", "test-results", "summary",
                                      "--path", str(self.logdir / (mode + ".xcresult")), "--compact"], self.env))
            (self.logdir / "test-summary.json").write_text(json.dumps(report, indent=2) + "\n")
            verify_test_report(report)
            say("XCTest: %s passed, %s skipped, %s failed" %
                (report["passedTests"], report["skippedTests"], report["failedTests"]))

    def host_tests(self):
        learning = "Jerreader/Features/Learning/"
        suites = [
            ("translation-watchdog", ["StandaloneTranslationWatchdog"], "test_translation_watchdog"),
            ("learning-features", ["WordReviewState", "SentencePractice", "ReadingActivity"], "test_learning_features"),
            ("offline-dictionary", ["OfflineDictionaryPackage"], "test_offline_dictionary"),
            ("japanese-chinese-lexicon", ["JapaneseChineseLexicon"], "test_japanese_chinese_lexicon"),
        ]
        suites = [(name, [learning + source + ".swift" for source in sources], harness)
                  for name, sources, harness in suites]
        suites.append(("model-catalog", ["Jerreader/Data/Services/AIModelCatalogService.swift"], "test_model_catalog"))
        suites.append(("ai-providers", ["Jerreader/Core/Models/LanguageCode.swift",
                                        "Jerreader/Core/Models/TranslationPreferences.swift",
                                        "Jerreader/Core/Models/AIProviderSettings.swift"], "test_ai_providers"))
        # Sign in with ChatGPT; the loopback listener runs for real on 127.0.0.1, nothing leaves the Mac.
        suites.append(("chatgpt-plan", ["Jerreader/Data/Services/ChatGPTPlan.swift",
                                        "Jerreader/Data/Services/ChatGPTPlanLoopback.swift"], "test_chatgpt_plan"))
        # Same cases as the Android ParityFixturesTest, read from shared/parity/.
        suites.append(("parity-fixtures", [learning + "WordReviewState.swift", learning + "SentencePractice.swift",
                                           "Jerreader/Data/Services/LibraryBackupPruner.swift",
                                           "Jerreader/Core/Models/LanguageCode.swift",
                                           learning + "ReadingCompanion.swift",
                                           "Jerreader/Core/Services/ReadingLens.swift"], "test_parity_fixtures"))
        sdk = read(["/usr/bin/xcrun", "--sdk", "macosx", "--show-sdk-path"], self.env)
        out = ROOT / ".build/SafeHostTests"
        out.mkdir(parents=True, exist_ok=True)
        for name, sources, harness in suites:
            binary = out / name
            self.run(name + "-compile", ["/usr/bin/xcrun", "--sdk", "macosx", "swiftc", "-j", "1",
                "-parse-as-library", "-sdk", sdk, "-target", os.uname().machine + "-apple-macosx13.0",
                *sources, "scripts/" + harness + ".swift",
                "-o", str(binary)], timeout=300)
            self.run(name, [str(binary)], timeout=120)

    def shutdown_owned(self):
        if self.owned_simulator:
            say("shutting down only this run's simulator: " + self.owned_simulator)
            # Cleanup must run even when health is unsafe; it may only reduce work.
            result = subprocess.run(self.simctl("shutdown", self.owned_simulator), env=self.env,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            if result.returncode != 0:
                # Boot may have failed before starting a device, or XCTest may
                # already have stopped it. Do not mask the original failure.
                if not any(d["udid"].upper() == self.owned_simulator and d["state"] == "Shutdown"
                           for d in self.devices()):
                    raise SafetyError("could not shut down this run's simulator: " + self.owned_simulator)
            self.owned_simulator = None


def main(argv):
    if argv in (["--help"], ["-h"], ["help"]):
        say("modes: doctor | host-test | build | analyze | test | ui-test | android; see docs/IOS_SAFE_TESTING.md")
        return 0
    supervisor = None
    outcome = "safety-stopped"
    reason = None
    try:
        opts = options(argv, os.environ)
        if opts["allow_high_load"]:
            say("explicit session exception: the settle wait between simulator/emulator phases ignores load; serial lock and other checks remain active")
        if opts["allow_external_activity"]:
            say("explicit session exception: unrelated builds/devices do not block this command; own jobs remain serial and cleanup only owns descendants/selected device")
        if Path.cwd().resolve() != ROOT or not (ROOT / "Jerreader.xcodeproj/project.pbxproj").is_file():
            raise SafetyError("run from the repository root with a valid Jerreader.xcodeproj/project.pbxproj")
        read(["/usr/bin/plutil", "-lint", str(ROOT / "Jerreader.xcodeproj/project.pbxproj")])
        developer = Path(os.environ.get("DEVELOPER_DIR") or read(["/usr/bin/xcode-select", "-p"]))
        if not os.access(developer / "usr/bin/xcodebuild", os.X_OK) or not (developer / "Platforms/iPhoneOS.platform").is_dir():
            if opts["mode"] not in ("android", "android-test"):
                raise SafetyError("set DEVELOPER_DIR to a full Xcode Contents/Developer; global xcode-select is unchanged")
            developer = None
        env = dict(os.environ)
        if developer:
            env["DEVELOPER_DIR"] = str(developer)
        if opts["mode"] in ("android", "android-test"):
            env.update(LANG="en_US.UTF-8", LC_ALL="en_US.UTF-8")
            bundled_java = ROOT / ".tools/jdk17/Contents/Home"
            if not env.get("JAVA_HOME") and (bundled_java / "bin/java").is_file():
                env["JAVA_HOME"] = str(bundled_java)
        env.pop("SDKROOT", None)
        supervisor = Supervisor(opts, developer, env)
        if opts["mode"] == "doctor" or opts["dry"]:
            with validation_lock():
                pending = supervisor.admission("doctor")
                say(("would queue: " + pending if pending else "ready for serial execution") + ": " + json.dumps(supervisor.latest))
                say("mode=" + opts["mode"] + "; jobs=1; no build or simulator was started")
                if opts["dry"] and opts["mode"] in ("build", "analyze", "test", "ui-test"):
                    say("base command: " + shlex.join(supervisor.common()))
                return 0
        logs = ROOT / ".build/SafeValidationLogs"
        logs.mkdir(parents=True, exist_ok=True)
        supervisor.logdir = Path(tempfile.mkdtemp(prefix=datetime.datetime.now().strftime("%Y%m%d-%H%M%S-"), dir=logs))
        with validation_lock(wait=True, timeout=opts["queue_timeout"]):
            supervisor.wait_for_turn("preflight")
            supervisor.preflight()
            try:
                supervisor.execute()
                outcome = "passed"
            finally:
                # Keep the host lock until child processes and our device are stopped.
                with finish_cleanup():
                    supervisor.shutdown_owned()
        return 0
    except PhaseFailure as error:
        outcome = "phase-failed"
        reason = str(error)
        say("FAIL: " + reason)
        return 1
    except QueueTimeout as error:
        outcome = "queue-timeout"
        reason = str(error)
        say("QUEUE: " + reason)
        return 2
    except (SafetyError, OSError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        outcome = "safety-stopped"
        reason = str(error)
        say("STOP: " + reason)
        return 1
    except KeyboardInterrupt:
        outcome = "interrupted"
        reason = "interrupted; no retry"
        say("STOP: " + reason)
        return 130
    finally:
        if supervisor and supervisor.logdir:
            summary = dict(mode=supervisor.opts["mode"], outcome=outcome,
                           reason=reason, last_health=supervisor.latest,
                           allow_high_load=supervisor.opts["allow_high_load"],
                           allow_external_activity=supervisor.opts["allow_external_activity"])
            (supervisor.logdir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
            say("summary: " + str((supervisor.logdir / "summary.json").relative_to(ROOT)))


def interrupt(signum, frame):
    raise KeyboardInterrupt()


if __name__ == "__main__":
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, interrupt)
    sys.exit(main(sys.argv[1:]))
