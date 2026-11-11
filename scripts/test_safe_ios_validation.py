"""Offline safety regressions; never invokes Xcode, Gradle, or Simulator."""
import contextlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import safe_ios_validation as guard

UDID = "01234567-89AB-CDEF-0123-456789ABCDEF"


def good_sample():
    return dict(cpus=8, load=3, memory_free=60, pressure=1, disk_gib=80, throttled=False)


def test_options(mode="test", **extra):
    env = dict(JERREADER_ALLOW_SIMULATOR="1", JERREADER_SIMULATOR_DESTINATION="platform=iOS Simulator,id=" + UDID)
    env.update(extra)
    return guard.options([mode], env)


class SafetyPolicyTests(unittest.TestCase):
    def test_actual_telemetry_format(self):
        sample = guard.parse_health("8", "{ 2.33 3.71 4.42 }",
            "System-wide memory free percentage: 62%", "1",
            "No thermal warning level has been recorded\nCPU_Speed_Limit = 100\nCPU_Scheduler_Limit = 100", 40 * 1024**3)
        self.assertIsNone(guard.unsafe(sample, True))
        self.assertTrue(guard.cool(sample))

    def test_bad_or_missing_telemetry_fails_closed(self):
        args = ["8", "{ 3 4 5 }", "System-wide memory free percentage: 60%", "1",
                "CPU_Speed_Limit = 100\nCPU_Scheduler_Limit = 100", 50 * 1024**3]
        for index, value in [(0, "0"), (1, "{ nan 2 3 }"), (1, "unknown"), (2, ""), (3, "0"), (4, "")]:
            with self.subTest(index=index, value=value):
                changed = list(args)
                changed[index] = value
                with self.assertRaises(guard.SafetyError):
                    guard.parse_health(*changed)

    def test_each_resource_can_stop_work(self):
        for key, value in [("memory_free", 14), ("pressure", 2),
                           ("pressure", 4), ("throttled", True), ("disk_gib", 11.9)]:
            with self.subTest(key=key, value=value):
                self.assertIsNotNone(guard.unsafe(dict(good_sample(), **{key: value})))

    def test_start_has_memory_reserve(self):
        sample = dict(good_sample(), memory_free=18)
        self.assertIsNone(guard.unsafe(sample))
        self.assertIsNotNone(guard.unsafe(sample, True))

    def test_session_thermal_exception_is_opt_in_and_bounded(self):
        self.assertFalse(guard.options(["build"], {})["allow_thermal_throttling"])
        self.assertTrue(guard.options(["build"], {"JERREADER_SESSION_ALLOW_THERMAL_THROTTLING": "1"})["allow_thermal_throttling"])
        for value in ("true", "2", "", "always"):
            with self.subTest(value=value), self.assertRaises(guard.SafetyError):
                guard.options(["build"], {"JERREADER_SESSION_ALLOW_THERMAL_THROTTLING": value})

    def test_session_exception_preserves_other_checks_and_cooldown(self):
        sample = dict(good_sample(), throttled=True)
        self.assertIsNone(guard.unsafe(sample, allow_thermal_throttling=True))
        self.assertTrue(guard.cool(sample, allow_thermal_throttling=True))
        self.assertFalse(guard.cool(dict(sample, load=9), allow_thermal_throttling=True))
        for key, value in (("memory_free", 14), ("pressure", 2), ("disk_gib", 11)):
            with self.subTest(key=key):
                self.assertIsNotNone(guard.unsafe(dict(sample, **{key: value}), allow_thermal_throttling=True))
        self.assertIsNotNone(guard.unsafe(dict(sample, memory_free=18), starting=True, allow_thermal_throttling=True))

    def test_session_load_exception_is_opt_in_and_keeps_memory_guard(self):
        self.assertFalse(guard.options(["build"], {})["allow_high_load"])
        self.assertTrue(guard.options(["build"], {"JERREADER_SESSION_ALLOW_HIGH_LOAD": "1"})["allow_high_load"])
        for value in ("true", "2", "", "always"):
            with self.assertRaises(guard.SafetyError):
                guard.options(["build"], {"JERREADER_SESSION_ALLOW_HIGH_LOAD": value})
        self.assertFalse(guard.cool(dict(good_sample(), load=999)))
        self.assertTrue(guard.cool(dict(good_sample(), load=999), allow_high_load=True))
        self.assertFalse(guard.cool(dict(good_sample(), load=999, memory_free=10), allow_high_load=True))

    def test_cooldown_stricter_than_build_admission(self):
        sample = dict(good_sample(), load=9)
        self.assertIsNone(guard.unsafe(sample, True))
        self.assertFalse(guard.cool(sample))

    def test_load_alone_never_aborts_running_work(self):
        for cpus in (4, 8, 16):
            for starting in (False, True):
                with self.subTest(cpus=cpus, starting=starting):
                    for load in (50, 150, 150.01, 217.98, 999):
                        self.assertIsNone(guard.unsafe(dict(good_sample(), cpus=cpus, load=load), starting))

    def test_no_simulator_without_explicit_opt_in(self):
        with self.assertRaises(guard.SafetyError):
            guard.options(["test"], {})

    def test_destination_injection_and_multiple_destinations_rejected(self):
        for destination in ["platform=iOS Simulator,id=anything", "platform=iOS Simulator,id=" + UDID + ",name=foo",
                            "platform=iOS Simulator,id=" + UDID + " -jobs 20"]:
            with self.assertRaises(guard.SafetyError):
                test_options(JERREADER_SIMULATOR_DESTINATION=destination)

    def test_filters_cannot_cross_targets_or_add_arguments(self):
        for value in ["JerreaderUITests/Foo", "JerreaderTests/Foo,", "JerreaderTests/Foo -jobs 20",
                      ",".join(["JerreaderTests/Foo"] * 9)]:
            with self.assertRaises(guard.SafetyError):
                test_options(JERREADER_TEST_FILTER=value)
        result = test_options(JERREADER_TEST_FILTER="JerreaderTests/Foo/testBar,JerreaderTests/Baz")
        self.assertEqual(len(result["filters"]), 2)

    def test_restrict_jobs_modes_and_extra_args(self):
        for args, env in [(["build", "test"], {}), (["clean"], {}),
                          (["build"], {"JERREADER_BUILD_JOBS": "2"}), (["build"], {"JERREADER_BUILD_JOBS": "3"})]:
            with self.assertRaises(guard.SafetyError):
                guard.options(args, env)
        self.assertEqual(guard.options(["build"], {})["jobs"], "1")

    def test_queue_timeout_is_bounded(self):
        for value in ("0", "-1", "43201", "forever"):
            with self.assertRaises(guard.SafetyError):
                guard.options(["build"], {"JERREADER_QUEUE_TIMEOUT_SECONDS": value})
        self.assertEqual(guard.options(["build"], {})["queue_timeout"], 3600)

    def test_foreign_build_detection_and_own_group_exclusion(self):
        self.assertIsNone(guard.foreign_work("100 100 /bin/xcodebuild xcodebuild build", 100))
        for line in ["101 101 /bin/xcodebuild xcodebuild build",
                     "102 102 /bin/java java org.gradle.launcher.daemon.bootstrap.GradleDaemon",
                     "103 103 /bin/swift-frontend swift-frontend",
                     "104 104 /bin/qemu-system-x86_64 qemu-system-x86_64 -avd test"]:
            self.assertIsNotNone(guard.foreign_work(line, 100))
        self.assertIsNone(guard.foreign_work("105 105 /bin/WindowServer WindowServer"))
        own_worker = "106 999 /bin/swift-frontend swift-frontend -o /fixture/SafeDerivedData/foo.o"
        self.assertIsNone(guard.foreign_work(own_worker, 100, "/fixture/SafeDerivedData"))
        self.assertIsNotNone(guard.foreign_work(own_worker, 100, "/other/project"))

    def test_empty_skipped_failed_or_missing_xctest_results_are_not_passes(self):
        for report in [{}, dict(totalTestCount=0, passedTests=0, failedTests=0, skippedTests=0),
                       dict(totalTestCount=1, passedTests=0, failedTests=0, skippedTests=1),
                       dict(totalTestCount=2, passedTests=1, failedTests=1, skippedTests=0)]:
            with self.assertRaises(guard.PhaseFailure):
                guard.verify_test_report(report)
        guard.verify_test_report(dict(totalTestCount=243, passedTests=240, failedTests=0, skippedTests=3))

    def test_partial_or_inconsistent_xctest_totals_are_not_passes(self):
        for report in [dict(totalTestCount=3, passedTests=1, failedTests=0, skippedTests=0),
                       dict(totalTestCount=3, passedTests=1, failedTests=0, skippedTests=1),
                       dict(totalTestCount=1, passedTests=1, failedTests=0, skippedTests=1)]:
            with self.subTest(report=report), self.assertRaises(guard.PhaseFailure):
                guard.verify_test_report(report)

    def test_signal_handlers_restored_after_cleanup(self):
        old = signal.getsignal(signal.SIGTERM)
        with guard.finish_cleanup():
            self.assertEqual(signal.getsignal(signal.SIGTERM), signal.SIG_IGN)
        self.assertEqual(signal.getsignal(signal.SIGTERM), old)

    def test_lock_serializes_and_releases_after_exception(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(guard.tempfile, "gettempdir", return_value=directory):
            with guard.validation_lock():
                with self.assertRaises(guard.SafetyError):
                    with guard.validation_lock():
                        self.fail("second lock acquired")
            with self.assertRaises(ValueError):
                with guard.validation_lock():
                    raise ValueError()
            with guard.validation_lock():
                pass

    def test_waiting_lock_times_out_without_entering_or_unlocking_owner(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(guard.tempfile, "gettempdir", return_value=directory), \
             patch.object(guard, "POLL_SECONDS", 0.01):
            with guard.validation_lock():
                with self.assertRaises(guard.QueueTimeout):
                    with guard.validation_lock(wait=True, timeout=0.02):
                        self.fail("entered a busy lock")
                with self.assertRaises(guard.SafetyError):
                    with guard.validation_lock():
                        self.fail("waiting job released somebody else's lock")
            with guard.validation_lock():
                pass

    def test_cancelled_waiter_does_not_keep_the_lock(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(guard.tempfile, "gettempdir", return_value=directory):
            with guard.validation_lock():
                with patch.object(guard.time, "sleep", side_effect=KeyboardInterrupt):
                    with self.assertRaises(KeyboardInterrupt):
                        with guard.validation_lock(wait=True):
                            self.fail("entered a busy lock")
            with guard.validation_lock():
                pass

    def test_real_second_process_waits_for_first_to_release_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "entered"
            source = ("import sys,tempfile; from pathlib import Path; "
                      "sys.path.insert(0,sys.argv[1]); import safe_ios_validation as g; "
                      "g.tempfile.gettempdir=lambda:sys.argv[2]; g.POLL_SECONDS=0.01; "
                      "\nwith g.validation_lock(wait=True,timeout=10): Path(sys.argv[3]).write_text('entered')")
            with patch.object(guard.tempfile, "gettempdir", return_value=directory):
                child = None
                try:
                    with guard.validation_lock():
                        child = subprocess.Popen([sys.executable, "-c", source, str(guard.ROOT / "scripts"),
                                                  directory, str(marker)], stdout=subprocess.PIPE, text=True)
                        self.assertIn("queued:", child.stdout.readline())
                        self.assertFalse(marker.exists())
                    child.wait(timeout=10)
                    self.assertEqual(child.returncode, 0)
                    self.assertTrue(marker.exists())
                finally:
                    if child:
                        if child.poll() is None:
                            child.terminate(); child.wait(timeout=5)
                        child.stdout.close()

    def test_daemon_requires_explicit_idle_state_and_busy_transition_wins(self):
        prefix = "[org.gradle.launcher.daemon.server.DaemonRegistryUpdater] Marking the daemon as "
        self.assertFalse(guard.gradle_log_idle("daemon is running. Sleeping until state changes."))
        self.assertTrue(guard.gradle_log_idle(prefix + "busy, address:x\n" + prefix + "idle, address:x"))
        self.assertFalse(guard.gradle_log_idle(prefix + "idle, address:x\n" + prefix + "busy, address:x"))
        process = "102 102 /bin/java java org.gradle.launcher.daemon.bootstrap.GradleDaemon"
        self.assertIsNone(guard.foreign_work(process, idle_daemon=lambda pid: pid == "102"))
        self.assertIsNotNone(guard.foreign_work(process))
        self.assertIsNotNone(guard.foreign_work(process + "\n103 103 java GradleWrapperMain", idle_daemon=lambda pid: True))
        self.assertIsNone(guard.foreign_work("104 104 java org.jetbrains.kotlin.daemon.KotlinCompileDaemon"))

    def test_own_detached_gradle_child_does_not_mask_unrelated_build(self):
        rows = ("100 1 100 /bin/java GradleWrapperMain\n"
                "101 100 101 /bin/java org.gradle.launcher.daemon.bootstrap.GradleDaemon\n"
                "102 101 101 /bin/java GradleWorkerMain\n"
                "200 1 200 /bin/java org.gradle.launcher.daemon.bootstrap.GradleDaemon")
        groups = set()
        external = guard.external_processes(rows, 100, groups)
        self.assertEqual(groups, {100, 101})
        self.assertEqual(external, "200 200 /bin/java org.gradle.launcher.daemon.bootstrap.GradleDaemon")
        self.assertIn("200", guard.foreign_work(external))

    def test_idle_transition_before_large_housekeeping_tail_and_appended_busy(self):
        prefix = "[org.gradle.launcher.daemon.server.DaemonRegistryUpdater] Marking the daemon as "
        with tempfile.TemporaryDirectory() as directory, patch.object(Path, "home", return_value=Path(directory)), \
             patch.dict(os.environ, {"GRADLE_USER_HOME": str(Path(directory) / ".gradle")}):
            path = Path(directory) / ".gradle/daemon/9.1.0/daemon-102.out.log"
            path.parent.mkdir(parents=True)
            path.write_text(prefix + "idle, address:x\n" + "housekeeping\n" * 12000)
            self.assertTrue(guard.gradle_daemon_idle("102"))
            self.assertTrue(guard.gradle_daemon_idle("102"))
            old_time = path.stat().st_mtime_ns
            path.write_text(prefix + "busy, address:x\n" + "housekeeping\n" * 12000)
            os.utime(path, ns=(old_time + 1000000, old_time + 1000000))
            self.assertFalse(guard.gradle_daemon_idle("102"))
            with path.open("a") as stream: stream.write(prefix + "idle, address:x\n")
            self.assertTrue(guard.gradle_daemon_idle("102"))
            path.write_text("unrecognized replacement\n")
            self.assertFalse(guard.gradle_daemon_idle("102"))


class SupervisorTests(unittest.TestCase):
    def make_supervisor(self, mode="test"):
        return guard.Supervisor(test_options(mode), Path("/fixture/Xcode/Contents/Developer"), {})

    def test_external_exception_requires_explicit_valid_value(self):
        key = "JERREADER_SESSION_ALLOW_EXTERNAL_ACTIVITY"
        self.assertFalse(guard.options(["build"], {})["allow_external_activity"])
        self.assertTrue(guard.options(["build"], {key: "1"})["allow_external_activity"])
        for value in ("true", "2", "", "always"):
            with self.assertRaises(guard.SafetyError):
                guard.options(["build"], {key: value})

    def test_authorized_external_activity_skips_only_external_inventory(self):
        supervisor = self.make_supervisor("build")
        supervisor.opts["allow_external_activity"] = True
        with patch.object(guard, "health", return_value=good_sample()), \
             patch.object(guard, "foreign_reason") as foreign, \
             patch.object(supervisor, "devices") as inventory, \
             patch.object(guard, "check_foreign") as runtime_foreign, \
             patch.object(guard, "owned_process_groups", return_value={123, 456}) as own:
            self.assertIsNone(supervisor.admission("fixture"))
            supervisor.preflight()
            supervisor.check("fixture", group=123)
            self.assertEqual(supervisor.owned_groups, {123, 456})
            own.assert_called_once_with(123)
            foreign.assert_not_called()
            runtime_foreign.assert_not_called()
            inventory.assert_not_called()
        with patch.object(guard, "health", return_value=dict(good_sample(), pressure=2)):
            self.assertIn("memory", supervisor.admission("fixture"))
            with self.assertRaises(guard.SafetyError): supervisor.check("fixture", group=123)

    def test_external_exception_does_not_claim_selected_active_device(self):
        supervisor = self.make_supervisor()
        supervisor.opts["allow_external_activity"] = True
        other = dict(udid="AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", state="Booted")
        for state in ("Shutdown", "Booted"):
            with patch.object(guard, "health", return_value=good_sample()), \
                 patch.object(supervisor, "devices", return_value=[other, dict(udid=UDID, state=state)]):
                if state == "Shutdown":
                    self.assertIsNone(supervisor.admission("fixture"))
                    supervisor.preflight()
                else:
                    self.assertIn("active", supervisor.admission("fixture"))
                    with self.assertRaises(guard.SafetyError): supervisor.preflight()
                self.assertIsNone(supervisor.owned_simulator)
        with patch.object(guard, "health", return_value=good_sample()), \
             patch.object(supervisor, "devices", return_value=[other]):
            with self.assertRaises(guard.SafetyError): supervisor.admission("fixture")

    def test_ownership_tracks_descendants_without_global_process_query(self):
        children = {100: [101], 101: [102], 102: [103], 103: [], 200: [201]}
        groups = {100: 100, 101: 100, 102: 102, 103: 102, 200: 200, 201: 201}
        with patch.object(guard, "child_process_ids", side_effect=lambda pid: children[pid]) as query, \
             patch.object(guard.os, "getpgid", side_effect=lambda pid: groups[pid]), \
             patch.object(guard, "read") as read:
            self.assertEqual(guard.owned_process_groups(100), {100, 102})
            self.assertEqual([call.args[0] for call in query.call_args_list], [100, 101, 102, 103])
            read.assert_not_called()

    @unittest.skipUnless(sys.platform == "darwin", "macOS libproc integration")
    def test_libproc_finds_actual_child_but_not_unrelated_groups(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(10)"], start_new_session=True)
        try:
            self.assertIn(child.pid, guard.child_process_ids(os.getpid()))
            self.assertEqual(guard.owned_process_groups(child.pid), {child.pid})
        finally:
            child.terminate(); child.wait(timeout=5)

    def test_fixed_cache_serial_compiler_and_no_signing(self):
        supervisor = self.make_supervisor()
        args = supervisor.common()
        self.assertEqual(args[args.index("-jobs") + 1], "1")
        self.assertEqual(args[args.index("-derivedDataPath") + 1], str(guard.ROOT / ".build/SafeDerivedData"))
        self.assertIn("CODE_SIGNING_ALLOWED=NO", args)
        self.assertIn("OTHER_SWIFT_FLAGS=$(inherited) -j1", args)
        self.assertIn("-only-testing:JerreaderTests", supervisor.test_arguments())

    def test_admission_uses_150_as_wait_threshold_with_other_guards_intact(self):
        supervisor = self.make_supervisor("build")
        with patch.object(supervisor, "devices", return_value=[]), patch.object(guard, "foreign_reason", return_value=None):
            for cpus in (4, 8, 16):
                with patch.object(guard, "health", return_value=dict(good_sample(), cpus=cpus, load=150)):
                    self.assertIsNone(supervisor.admission("test"))
                with patch.object(guard, "health", return_value=dict(good_sample(), cpus=cpus, load=150.01)):
                    self.assertIn("waiting", supervisor.admission("test"))
            with patch.object(guard, "health", return_value=dict(good_sample(), throttled=True)):
                self.assertIn("thermal", supervisor.admission("test"))
            with patch.object(guard, "health", return_value=dict(good_sample(), disk_gib=11)):
                with self.assertRaises(guard.SafetyError): supervisor.admission("test")

    def test_load_exception_does_not_skip_foreign_build_queue(self):
        supervisor = self.make_supervisor("build")
        supervisor.opts["allow_high_load"] = True
        with patch.object(guard, "health", return_value=dict(good_sample(), load=999)), \
             patch.object(supervisor, "devices", return_value=[]), \
             patch.object(guard, "foreign_reason", return_value="another build"):
            self.assertEqual(supervisor.admission("fixture"), "another build")

    def test_wait_then_stabilize_before_admitting_once(self):
        supervisor = self.make_supervisor("build")
        with patch.object(supervisor, "admission", side_effect=["busy", None, "load high", None, None]) as admission, \
             patch.object(guard, "COOL_SECONDS", 0.01), patch.object(guard, "POLL_SECONDS", 0.02):
            supervisor.wait_for_turn("fixture")
        self.assertEqual(admission.call_count, 5)

    def test_known_queue_conditions_defer_inventory_but_never_skip_admission_check(self):
        supervisor = self.make_supervisor("build")
        with patch.object(guard, "health", return_value=dict(good_sample(), load=302)), \
             patch.object(guard, "foreign_reason") as foreign, \
             patch.object(supervisor, "devices") as inventory:
            self.assertIn("waiting", supervisor.admission("fixture"))
            foreign.assert_not_called()
            inventory.assert_not_called()
        with patch.object(guard, "health", return_value=good_sample()), \
             patch.object(guard, "foreign_reason", return_value="another build") as foreign, \
             patch.object(supervisor, "devices") as inventory:
            self.assertEqual(supervisor.admission("fixture"), "another build")
            inventory.assert_not_called()
        with patch.object(guard, "health", return_value=good_sample()), \
             patch.object(guard, "foreign_reason", return_value=None), \
             patch.object(supervisor, "devices", return_value=[dict(udid=UDID, state="Booted")]) as inventory:
            self.assertEqual(supervisor.admission("fixture"), "another simulator is active")
            inventory.assert_called_once()

    def test_queued_phase_timeout_never_launches_a_child(self):
        supervisor = self.make_supervisor("build")
        supervisor.opts["queue_timeout"] = 0.02
        with patch.object(supervisor, "admission", return_value="high load"), \
             patch.object(guard, "POLL_SECONDS", 0.01), patch.object(guard.subprocess, "Popen") as launch:
            with self.assertRaises(guard.QueueTimeout): supervisor.run("fixture", ["never-launch"])
        launch.assert_not_called()

    def test_missing_startup_monitor_stays_queued_until_complete(self):
        supervisor = self.make_supervisor("build")
        with patch.object(supervisor, "admission", side_effect=[guard.MonitorUnavailable("fixture"), None, None]) as admission, \
             patch.object(guard, "COOL_SECONDS", 0.01), patch.object(guard, "POLL_SECONDS", 0.02), \
             patch.object(guard.subprocess, "Popen") as launch:
            supervisor.wait_for_turn("fixture")
        self.assertEqual(admission.call_count, 3)
        launch.assert_not_called()

    def test_missing_startup_monitor_can_only_time_out_without_launch(self):
        supervisor = self.make_supervisor("build")
        supervisor.opts["queue_timeout"] = 0.02
        with patch.object(supervisor, "admission", side_effect=guard.MonitorUnavailable("fixture")), \
             patch.object(guard, "POLL_SECONDS", 0.01), patch.object(guard.subprocess, "Popen") as launch:
            with self.assertRaises(guard.QueueTimeout): supervisor.run("fixture", ["never-launch"])
        launch.assert_not_called()

    def test_missing_runtime_monitor_still_propagates_with_thermal_exception(self):
        supervisor = self.make_supervisor("build")
        supervisor.opts["allow_thermal_throttling"] = True
        with patch.object(guard, "health", side_effect=guard.MonitorUnavailable("fixture")), \
             patch.object(guard, "check_foreign") as foreign:
            with self.assertRaises(guard.MonitorUnavailable): supervisor.check("fixture", group=123)
        foreign.assert_not_called()

    def test_runtime_high_load_is_recorded_but_does_not_abort(self):
        supervisor = self.make_supervisor("build")
        with patch.object(guard, "health", return_value=dict(good_sample(), load=300)), \
             patch.object(guard, "check_foreign", return_value=set()):
            self.assertEqual(supervisor.check("running", group=123)["load"], 300)

    def test_authorized_thermal_exception_applies_to_admission_and_monitor(self):
        supervisor = self.make_supervisor("build")
        supervisor.opts["allow_thermal_throttling"] = True
        with patch.object(guard, "health", return_value=dict(good_sample(), throttled=True)), \
             patch.object(supervisor, "devices", return_value=[]), \
             patch.object(guard, "foreign_reason", return_value=None), \
             patch.object(guard, "check_foreign", return_value=set()):
            self.assertIsNone(supervisor.admission("fixture"))
            self.assertTrue(supervisor.check("fixture", group=123)["throttled"])

    def test_android_uses_serial_single_use_gradle_and_shared_supervisor(self):
        supervisor = self.make_supervisor("android")
        with patch.object(supervisor, "run") as run:
            supervisor.execute()
        run.assert_called_once()
        command = run.call_args.args[1]
        for value in ("--no-daemon", "--no-parallel", "--max-workers=1", "-Pkotlin.compiler.execution.strategy=in-process"):
            self.assertIn(value, command)

    def test_release_package_stays_in_one_serial_gradle_invocation(self):
        supervisor = self.make_supervisor("android")
        supervisor.opts["android_variant"] = "release"
        with patch.object(supervisor, "run") as run:
            supervisor.execute()
        run.assert_called_once()
        command = run.call_args.args[1]
        for value in ("--no-daemon", "--no-parallel", "--max-workers=1",
                      ":androidApp:testDebugUnitTest", ":androidApp:assembleDebugAndroidTest",
                      ":androidApp:lintRelease", ":androidApp:assembleRelease"):
            self.assertIn(value, command)

    def test_android_package_variant_is_closed_and_build_only(self):
        self.assertEqual(guard.options(["android"], {})["android_variant"], "debug")
        self.assertEqual(guard.options(["android"], {"JERREADER_ANDROID_PACKAGE_VARIANT": "release"})["android_variant"], "release")
        for mode, variant in (("android", "release :arbitraryTask"), ("build", "release"), ("android", "")):
            with self.assertRaises(guard.SafetyError):
                guard.options([mode], {"JERREADER_ANDROID_PACKAGE_VARIANT": variant})

    def test_build_settle_boot_settle_test_order(self):
        supervisor = self.make_supervisor()
        supervisor.logdir = Path("/fixture/logs")
        events = []
        with patch.object(supervisor, "run", side_effect=lambda phase, command, **kw: events.append((phase, command))), \
             patch.object(supervisor, "cooldown", side_effect=lambda phase: events.append((phase, []))), \
             patch.object(supervisor, "preflight", side_effect=lambda: events.append(("recheck", []))), \
             patch.object(guard, "read", return_value=json.dumps(dict(totalTestCount=1, passedTests=1, failedTests=0, skippedTests=0))), \
             patch.object(Path, "write_text"):
            supervisor.execute()
        self.assertEqual([e[0] for e in events], ["build-for-testing", "before-simulator-boot", "recheck",
                         "simulator-boot", "simulator-ready", "before-test-execution", "test"])
        self.assertEqual(events[0][1][-1], "build-for-testing")
        self.assertEqual(events[-1][1][-1], "test-without-building")
        self.assertNotIn("-retry-tests-on-failure", events[-1][1])
        # Xcode rejects -test-iterations 1. Omitting repetition flags runs once.
        self.assertNotIn("-test-iterations", events[-1][1])
        self.assertNotIn("-run-tests-until-failure", events[-1][1])
        self.assertEqual(supervisor.owned_simulator, UDID)

    def test_compile_failure_never_boots_or_retries(self):
        supervisor = self.make_supervisor()
        with patch.object(supervisor, "run", side_effect=guard.PhaseFailure("compile failed")) as run, \
             patch.object(supervisor, "cooldown") as cooldown:
            with self.assertRaises(guard.PhaseFailure):
                supervisor.execute()
        self.assertEqual(run.call_count, 1)
        cooldown.assert_not_called()
        self.assertIsNone(supervisor.owned_simulator)

    def test_booted_device_is_not_claimed_or_stopped(self):
        supervisor = self.make_supervisor()
        with patch.object(supervisor, "check"), patch.object(supervisor, "devices", return_value=[dict(udid=UDID, state="Booted")]):
            with self.assertRaises(guard.SafetyError):
                supervisor.preflight()
        self.assertIsNone(supervisor.owned_simulator)

    def test_cleanup_targets_only_owned_udid(self):
        supervisor = self.make_supervisor()
        supervisor.owned_simulator = UDID
        with patch.object(guard.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
            supervisor.shutdown_owned()
        self.assertEqual(run.call_args.args[0], ["/usr/bin/xcrun", "simctl", "shutdown", UDID])
        self.assertIsNone(supervisor.owned_simulator)

    def test_cleanup_after_boot_failure_is_idempotent(self):
        supervisor = self.make_supervisor()
        supervisor.owned_simulator = UDID
        with patch.object(guard.subprocess, "run", return_value=subprocess.CompletedProcess([], 149)), \
             patch.object(supervisor, "devices", return_value=[dict(udid=UDID, state="Shutdown")]):
            supervisor.shutdown_owned()
        self.assertIsNone(supervisor.owned_simulator)

    def test_ui_mode_only_runs_ui_tests(self):
        supervisor = self.make_supervisor("ui-test")
        self.assertIn("-only-testing:JerreaderUITests", supervisor.test_arguments())
        self.assertNotIn("-only-testing:JerreaderTests", supervisor.test_arguments())

    def test_cooldown_requires_continuous_safe_interval(self):
        supervisor = self.make_supervisor()
        samples = [good_sample(), dict(good_sample(), load=9), good_sample(), good_sample()]
        with patch.object(supervisor, "check", side_effect=samples) as check, \
             patch.object(guard, "COOL_SECONDS", 0.015), patch.object(guard, "POLL_SECONDS", 0.02), \
             patch.object(guard, "COOL_TIMEOUT", 1):
            supervisor.cooldown("fixture")
        self.assertEqual(check.call_count, 4)

    def test_cooldown_timeout_does_not_continue(self):
        supervisor = self.make_supervisor()
        with patch.object(supervisor, "check", return_value=dict(good_sample(), load=9)), \
             patch.object(guard, "POLL_SECONDS", 0.01), patch.object(guard, "COOL_TIMEOUT", 0.02):
            with self.assertRaises(guard.SafetyError):
                supervisor.cooldown("fixture")

    def test_real_child_is_terminated_on_health_abort_and_other_group_survives(self):
        # Only tiny Python sleepers; validate actual process-group cleanup, not mock kill calls.
        with tempfile.TemporaryDirectory(dir=guard.ROOT / ".build") as directory:
            supervisor = self.make_supervisor()
            supervisor.logdir = Path(directory)
            unrelated = subprocess.Popen([sys.executable, "-c", "import signal; signal.pause()"], start_new_session=True)
            original_stop = supervisor.stop_group
            stopped = []
            child_file = Path(directory) / "child.pid"
            source = ("import subprocess,sys,signal; from pathlib import Path; "
                      "child=subprocess.Popen([sys.executable,'-c','import signal; signal.pause()']); "
                      "Path(sys.argv[1]).write_text(str(child.pid)); signal.pause()")
            def checked(phase, **kwargs):
                if kwargs.get("starting"):
                    return good_sample()
                deadline = time.monotonic() + 3
                while not child_file.exists() and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertTrue(child_file.exists(), "fixture descendant failed to start")
                raise guard.SafetyError("thermal throttling")
            def stop(process):
                original_stop(process)
                stopped.append(process)
            try:
                with patch.object(supervisor, "wait_for_turn"), patch.object(supervisor, "check", side_effect=checked), \
                     patch.object(supervisor, "stop_group", side_effect=stop):
                    with self.assertRaises(guard.SafetyError):
                        supervisor.run("fixture", [sys.executable, "-c", source, str(child_file)])
                self.assertEqual(len(stopped), 1)
                self.assertIsNotNone(stopped[0].poll())
                self.assertIsNone(unrelated.poll())
                child_pid = int(child_file.read_text())
                status = subprocess.run(["/bin/ps", "-o", "stat=", "-p", str(child_pid)],
                                        text=True, capture_output=True, timeout=8).stdout.strip()
                self.assertTrue(not status or status.startswith("Z"), "descendant still running")
            finally:
                if unrelated.poll() is None:
                    os.killpg(unrelated.pid, signal.SIGTERM)
                    unrelated.wait(timeout=5)


class AndroidDeviceSafetyTests(unittest.TestCase):
    def test_completed_ui_evidence_survives_a_later_interrupted_pull(self):
        from android_device_validation import preserve_screenshots
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            def completed(args):
                destination = Path(args[-1])
                self.assertFalse(destination.exists())
                destination.mkdir()
                (destination / "android-learning.png").write_bytes(b"verified screen")
            preserve_screenshots(completed, output, "ui-1")
            self.assertEqual((output / "screenshots/android-learning.png").read_bytes(), b"verified screen")
            def interrupted(args):
                destination = Path(args[-1])
                destination.mkdir()
                (destination / "android-learning.png").write_bytes(b"partial transfer")
                raise RuntimeError("owned test process interrupted")
            with self.assertRaises(RuntimeError): preserve_screenshots(interrupted, output, "ui-2")
            self.assertEqual((output / "screenshots/android-learning.png").read_bytes(), b"verified screen")

    def test_guest_boot_anr_blocks_tests_even_without_lastanr_report(self):
        from android_device_validation import verify_guest_startup
        verify_guest_startup("WINDOW MANAGER WINDOWS\nWindow #0: Launcher", "boot_progress_enable_screen: 123")
        with self.assertRaises(RuntimeError):
            verify_guest_startup("Application Not Responding: com.android.systemui", "")
        with self.assertRaises(RuntimeError):
            verify_guest_startup("WINDOW MANAGER WINDOWS\nWindow #0: Launcher", "I am_anr  : [0,889,com.android.systemui,550026765,executing service]")
        for windows, events in (("", "boot_progress"), ("WINDOW MANAGER WINDOWS\nDUMP TIMEOUT", "boot_progress"),
                                ("WINDOW MANAGER WINDOWS", "")):
            with self.subTest(windows=windows, events=events), self.assertRaises(RuntimeError):
                verify_guest_startup(windows, events)

    def test_temporary_guest_replaces_only_the_two_test_packages(self):
        from android_device_validation import clean_temporary_test_apps
        calls = []
        def command(args):
            calls.append(args)
            return ("package:com.jerreader.android\npackage:com.jerreader.android.test\n"
                    "package:com.other.app\n") if args[0] == "shell" else "Success\n"
        clean_temporary_test_apps(command)
        self.assertEqual(calls[1:], [["uninstall", "com.jerreader.android.test"], ["uninstall", "com.jerreader.android"]])
        with self.assertRaises(RuntimeError):
            clean_temporary_test_apps(lambda args: "package:com.jerreader.android" if args[0] == "shell" else "Failure")

    def test_android_ui_scope_is_reviewed_and_cannot_apply_to_other_modes(self):
        env = {"JERREADER_ALLOW_SIMULATOR": "1", "JERREADER_SIMULATOR_DESTINATION": "platform=Android,serial=emulator-5580,avd=QA",
               "JERREADER_ANDROID_VALIDATION_SCOPE": "ui"}
        self.assertEqual(guard.options(["android-test"], env)["android_scope"], "ui")
        for mode, value in (("android-test", "arbitrary"), ("doctor", "ui")):
            with self.assertRaises(guard.SafetyError): guard.options([mode], dict(env, JERREADER_ANDROID_VALIDATION_SCOPE=value))

    def test_android_reviewed_suite_cannot_pass_with_missing_or_skipped_tests(self):
        from android_device_validation import verify_report
        report = "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1"
        with self.assertRaises(RuntimeError): verify_report(report, minimum_tests=4)
        with self.assertRaises(RuntimeError): verify_report("OK (4 tests)\nINSTRUMENTATION_CODE: -1", minimum_tests=4)
        self.assertEqual(verify_report("INSTRUMENTATION_STATUS_CODE: 0\n" * 4 + "OK (4 tests)\nINSTRUMENTATION_CODE: -1", minimum_tests=4), 4)

    def test_serial_instrumentation_results_require_every_chunk_to_finish(self):
        from android_device_validation import verify_report
        one = "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"
        self.assertEqual(verify_report(one * 4, minimum_tests=4), 4)
        with self.assertRaises(RuntimeError): verify_report(one * 3 + "OK (1 test)", minimum_tests=4)
        with self.assertRaises(RuntimeError): verify_report(one * 4 + "FAILURES!!!", minimum_tests=4)

    def test_offline_guest_state_must_be_confirmed_before_testing(self):
        from android_device_validation import verify_offline_guest
        def state(airplane, wifi):
            return lambda args: airplane if args[-1] == "airplane_mode_on" else wifi
        verify_offline_guest(state("1\n", "0\n"))
        for airplane, wifi in (("0", "0"), ("1", "1"), ("null", "0")):
            with self.assertRaises(RuntimeError): verify_offline_guest(state(airplane, wifi))

    def test_android_requires_explicit_approval_and_one_destination(self):
        for env in ({}, {"JERREADER_ALLOW_SIMULATOR": "1"}, {
            "JERREADER_ALLOW_SIMULATOR": "1", "JERREADER_SIMULATOR_DESTINATION": "platform=Android,serial=emulator-5580,avd=foo -wipe-data"}):
            with self.assertRaises(guard.SafetyError): guard.options(["android-test"], env)
        self.assertEqual(guard.options(["android-test"], {
            "JERREADER_ALLOW_SIMULATOR": "1", "JERREADER_SIMULATOR_DESTINATION": "platform=Android,serial=emulator-5580,avd=QA"})["mode"], "android-test")

    def test_release_smoke_requires_visible_app_and_live_process(self):
        from android_device_validation import verify_release_launch
        hierarchy = '<hierarchy package="com.jerreader.android" text="书架 导入电子书" />'
        verify_release_launch("Status: ok", hierarchy, "1234\n")
        for start, ui, pid in (("Error", hierarchy, "1234"), ("Status: ok", "", "1234"), ("Status: ok", hierarchy, "")):
            with self.assertRaises(RuntimeError): verify_release_launch(start, ui, pid)
        for mode, value in (("build", "1"), ("android-test", "yes")):
            with self.assertRaises(guard.SafetyError):
                guard.options([mode], {"JERREADER_ANDROID_RELEASE_SMOKE": value})

    def test_android_empty_or_failed_report_is_not_success(self):
        from android_device_validation import verify_report
        for report in ("", "OK (0 tests)\nINSTRUMENTATION_CODE: -1", "FAILURES!!!\nINSTRUMENTATION_CODE: -1", "OK (2 tests)"):
            with self.assertRaises(RuntimeError): verify_report(report)
        self.assertEqual(verify_report("OK (2 tests)\nINSTRUMENTATION_CODE: -1"), 2)


if __name__ == "__main__":
    (guard.ROOT / ".build").mkdir(exist_ok=True)
    unittest.main()
