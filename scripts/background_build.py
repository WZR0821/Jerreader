#!/usr/bin/env python3
"""Run a no-simulator safe build outside the GUI login session.

Why: the WindowServer watchdog on this Intel Mac ends the whole Aqua session,
which kills every process started from a terminal, an IDE or an agent. Jobs in
launchd's per-user Background domain are not part of that session and were
observed to survive the crash, so a build submitted here keeps running and its
result is still on disk after logging back in.

How: a Background job may not read ~/Documents (macOS privacy protection), so
`submit` mirrors the sources to ~/Library/Caches and the job builds the mirror
through the same scripts/safe_xcodebuild.sh entry: same host lock, one job,
one attempt, no retry. Nothing is installed permanently; the job definition is
loaded for one run and is gone after logout or reboot.

  submit [ipa|build|analyze|host-test|doctor]   default: ipa (Release + unsigned IPA)
  status [job]      state, phase and the last output lines
  wait [job]        block until the job ends; exit 0 only if it passed
  collect [job]     copy a finished IPA into dist/iOS of this checkout
  cancel            stop the running job (its own processes only)
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
BASE = Path.home() / "Library/Caches/Jerreader/BackgroundBuild"
MIRROR = BASE / "worktree"
JOBS = BASE / "jobs"
LABEL = "com.jerreader.background-build"
MODES = ("ipa", "build", "analyze", "host-test", "doctor")
KEEP_JOBS = 5
# Build outputs, tool caches, VCS data and signing material never enter the
# mirror. Excluded paths are also left alone on the mirror side, so its own
# .build/ (incremental DerivedData, logs) survives every sync.
EXCLUDES = ("/.build/", "/.git/", "/dist/", "/.tools/", "/.gradle/", "/.kotlin/", "/.idea/",
            "/build/", "/tmp/", "/DerivedData/", "/androidApp/build", "/shared/build",
            "/PrivateTestFixtures/", "/keystore.properties", "/local.properties",
            "/com.apple.dt.Xcode.plist", "xcuserdata/", ".DS_Store",
            "*.jks", "*.keystore", "*.p12", "*.mobileprovision", "*.apk", "*.aab", "*.ipa")


class JobError(RuntimeError):
    pass


def say(message):
    print("background_build: " + message, flush=True)


def domain():
    return "user/%d" % os.getuid()


def service_state():
    """None when no job definition is loaded, otherwise launchd's state word."""
    result = subprocess.run(["/bin/launchctl", "print", domain() + "/" + LABEL],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    if result.returncode != 0:
        return None
    for line in result.stdout.splitlines():
        if line.strip().startswith("state = "):
            return line.split("=", 1)[1].strip()
    return "unknown"


def active(state):
    # A definition whose process has exited stays loaded as "not running".
    return state is not None and state != "not running"


def unload():
    subprocess.run(["/bin/launchctl", "bootout", domain() + "/" + LABEL],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def write_json(path, data):
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    os.replace(temporary, path)


def read_json(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return None


def job_directory(name=None):
    if name:
        path = JOBS / Path(name).name
        if not path.is_dir():
            raise JobError("no such job: " + name)
        return path
    jobs = sorted(p for p in JOBS.glob("*") if p.is_dir()) if JOBS.is_dir() else []
    if not jobs:
        raise JobError("no background job has been submitted")
    return jobs[-1]


def developer_directory():
    developer = Path(os.environ.get("DEVELOPER_DIR") or subprocess.check_output(
        ["/usr/bin/xcode-select", "-p"], text=True).strip())
    if not os.access(developer / "usr/bin/xcodebuild", os.X_OK) or not (developer / "Platforms/iPhoneOS.platform").is_dir():
        raise JobError("set DEVELOPER_DIR to a full Xcode Contents/Developer for this command; "
                       "global xcode-select is unchanged")
    return developer


def mirror_command():
    command = ["/usr/bin/rsync", "-a", "--delete"]
    for pattern in EXCLUDES:
        command += ["--exclude", pattern]
    return command + [str(ROOT) + "/", str(MIRROR) + "/"]


def job_definition(job):
    return dict(
        Label=LABEL,
        ProgramArguments=["/usr/bin/python3", str(MIRROR / "scripts/background_build.py"), "run", str(job)],
        WorkingDirectory=str(MIRROR),
        # Without this key launchd refuses to load a job into the user domain.
        LimitLoadToSessionType="Background",
        # The default type is throttled for CPU and disk; the build already runs niced.
        ProcessType="Interactive",
        EnvironmentVariables=dict(LANG="en_US.UTF-8"),
        StandardOutPath=str(job / "output.log"),
        StandardErrorPath=str(job / "output.log"),
        RunAtLoad=True,
        ExitTimeOut=30,
    )


def submit(mode):
    if mode not in MODES:
        raise JobError("mode must be one of: " + " | ".join(MODES))
    if ROOT == MIRROR.resolve() or not (ROOT / "Jerreader.xcodeproj/project.pbxproj").is_file():
        raise JobError("submit from the real checkout, not from the build mirror")
    state = service_state()
    if active(state):
        raise JobError("a background job is still running; use status/wait, or cancel it first")
    if state is not None:
        unload()  # A finished definition stays loaded until removed.
    developer = developer_directory()

    MIRROR.mkdir(parents=True, exist_ok=True)
    JOBS.mkdir(parents=True, exist_ok=True)
    say("mirroring sources to " + str(MIRROR))
    subprocess.run(mirror_command(), check=True)
    # Reuse already-downloaded Swift packages (APFS clone: instant, no extra space).
    packages = ".build/SafeDerivedData/SourcePackages"
    if (ROOT / packages).is_dir() and not (MIRROR / packages).exists():
        (MIRROR / packages).parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["/bin/cp", "-cR", str(ROOT / packages), str(MIRROR / packages)], check=True)

    finished = [p for p in sorted(JOBS.glob("*")) if p.is_dir()]
    for old in finished[:-KEEP_JOBS + 1] if len(finished) >= KEEP_JOBS else []:
        shutil.rmtree(old, ignore_errors=True)
    job = JOBS / (datetime.datetime.now().strftime("%Y%m%d-%H%M%S-") + mode)
    job.mkdir()
    write_json(job / "job.json", dict(mode=mode, developer_dir=str(developer), source=str(ROOT),
                                      submitted=datetime.datetime.now().isoformat(timespec="seconds")))
    write_json(job / "status.json", dict(state="submitted", mode=mode))
    with (job / "job.plist").open("wb") as stream:
        plistlib.dump(job_definition(job), stream)
    result = subprocess.run(["/bin/launchctl", "bootstrap", domain(), str(job / "job.plist")],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if result.returncode != 0:
        write_json(job / "status.json", dict(state="finished", mode=mode, outcome="not-started",
                                             reason=result.stdout.strip()))
        raise JobError("launchd did not accept the job: " + result.stdout.strip())
    say("submitted %s as %s; it keeps running if the login session ends" % (mode, job.name))
    say("follow with: scripts/background_build.py wait   (or: status)")
    return 0


def run(job):
    """Runs inside the Background domain, from the mirror."""
    job = Path(job)
    spec = read_json(job / "job.json")
    if spec is None or job.parent != JOBS or ROOT != MIRROR.resolve():
        raise JobError("run is internal; use submit")
    mode = spec["mode"]
    session = subprocess.run(["/bin/launchctl", "managername"], stdout=subprocess.PIPE, text=True).stdout.strip()
    status = dict(state="running", mode=mode, phase="build", pid=os.getpid(), session=session,
                  started=datetime.datetime.now().isoformat(timespec="seconds"))
    write_json(job / "status.json", status)
    env = dict(os.environ, DEVELOPER_DIR=spec["developer_dir"])
    if mode == "ipa":
        env["JERREADER_CONFIGURATION"] = "Release"
    child = None
    stopped = False

    def stop(signum, frame):
        # Let the supervisor stop its own process groups before launchd's deadline.
        nonlocal stopped
        stopped = True
        if child and child.poll() is None:
            child.send_signal(signal.SIGTERM)
    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sig, stop)

    def step(phase, command):
        nonlocal child
        status["phase"] = phase
        write_json(job / "status.json", status)
        say(phase + ": " + " ".join(command))
        child = subprocess.Popen(command, cwd=ROOT, env=env)
        return child.wait()

    logs = ROOT / ".build/SafeValidationLogs"
    before = set(logs.glob("*")) if logs.is_dir() else set()
    code = step("build", [str(ROOT / "scripts/safe_xcodebuild.sh"), "build" if mode == "ipa" else mode])
    if stopped and code == 0:
        code = 130
    created = sorted(set(logs.glob("*")) - before) if logs.is_dir() else []
    if created:
        status["validation_log"] = str(created[-1])
        summary = read_json(created[-1] / "summary.json")
        if summary:
            status["summary"] = summary
    if code == 0 and mode == "ipa" and not stopped:
        if len(created) != 1:
            code = 1
            status["reason"] = "expected exactly one new validation log"
        else:
            code = step("package", ["/usr/bin/python3", str(ROOT / "scripts/package_unsigned_ipa.py"),
                                    "--validation-log", str(created[0]), "--output", str(job / "artifacts")])
            if code == 0:
                status["artifacts"] = sorted(p.name for p in (job / "artifacts").glob("*"))
    status.update(state="finished", outcome="cancelled" if stopped else "passed" if code == 0 else "failed", exit_code=code,
                  finished=datetime.datetime.now().isoformat(timespec="seconds"))
    write_json(job / "status.json", status)
    say("finished: " + status["outcome"] + ("" if code == 0 else " (exit %s, phase %s); no retry" % (code, status["phase"])))
    return 0 if code == 0 else 1


def tail(path, count):
    try:
        with path.open("rb") as stream:
            stream.seek(0, os.SEEK_END)
            stream.seek(max(0, stream.tell() - 8192))
            return stream.read().decode("utf-8", errors="replace").splitlines()[-count:]
    except OSError:
        return []


def current(job):
    """Status as recorded by the job, corrected when the job vanished without finishing."""
    status = read_json(job / "status.json") or dict(state="unknown")
    state = service_state()
    # Only the newest job can be the one launchd is running.
    running = active(state) and job == job_directory()
    if status.get("state") != "finished" and not running:
        time.sleep(1)  # The job may be writing its final status right now.
        status = read_json(job / "status.json") or dict(state="unknown")
        if status.get("state") != "finished":
            status = dict(status, state="finished", outcome="lost",
                          reason="the job is no longer running and recorded no result (reboot, logout or kill)")
    if state == "not running":
        unload()  # Nothing stays loaded once the process has exited.
    return status


def show(name):
    job = job_directory(name)
    status = current(job)
    say("job " + job.name + ": " + json.dumps(status, ensure_ascii=False))
    for line in tail(job / "output.log", 6):
        print("  " + line)
    return 0


def wait(name):
    job = job_directory(name)
    last = None
    while True:
        status = current(job)
        if status.get("state") == "finished":
            say("job " + job.name + ": " + json.dumps(status, ensure_ascii=False))
            return 0 if status.get("outcome") == "passed" else 1
        lines = tail(job / "output.log", 1)
        if lines and lines[0] != last:
            last = lines[0]
            print("  " + last, flush=True)
        time.sleep(15)


def collect(name):
    job = job_directory(name)
    status = current(job)
    if status.get("mode") != "ipa" or status.get("outcome") != "passed":
        raise JobError("job %s has no finished IPA: %s" % (job.name, json.dumps(status, ensure_ascii=False)))
    archives = list((job / "artifacts").glob("*.ipa"))
    if len(archives) != 1:
        raise JobError("expected exactly one IPA in " + str(job / "artifacts"))
    source = archives[0]
    recorded = source.with_suffix(".ipa.sha256").read_text().split()[0]
    target = ROOT / "dist/iOS" / source.name
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        raise JobError("already collected: " + str(target.relative_to(ROOT)))
    shutil.copyfile(source, target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != recorded:
        target.unlink()
        raise JobError("copied IPA does not match its recorded SHA-256; nothing was kept")
    shutil.copyfile(source.with_suffix(".ipa.sha256"), target.with_suffix(".ipa.sha256"))
    validation = json.loads(source.with_suffix(".ipa.validation.json").read_text())
    validation["file"] = str(target)
    write_json(target.with_suffix(".ipa.validation.json"), validation)
    say("collected " + str(target.relative_to(ROOT)) + " (sha256 " + recorded + ")")
    return 0


def cancel():
    if service_state() is None:
        say("no background job is loaded")
        return 0
    unload()  # SIGTERM to the runner, which stops the supervisor and its children.
    say("stop requested; check with: scripts/background_build.py status")
    return 0


def main(argv):
    command = argv[0] if argv else "help"
    rest = argv[1:]
    try:
        if command == "submit" and len(rest) <= 1:
            return submit(rest[0] if rest else "ipa")
        if command == "run" and len(rest) == 1:
            return run(rest[0])
        if command in ("status", "wait", "collect") and len(rest) <= 1:
            return dict(status=show, wait=wait, collect=collect)[command](rest[0] if rest else None)
        if command == "cancel" and not rest:
            return cancel()
    except (JobError, OSError, subprocess.SubprocessError, ValueError, KeyError) as error:
        say("STOP: " + str(error))
        return 1
    print(__doc__.strip())
    return 0 if command in ("help", "--help", "-h") else 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
