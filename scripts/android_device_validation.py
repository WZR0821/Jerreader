#!/usr/bin/env python3
"""Internal child of the shared safety supervisor. Runs precompiled offline tests only."""
import os
import json
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import safe_ios_validation as guard

ROOT = Path(__file__).resolve().parent.parent
REGRESSION_CLASSES = [
    "library.LibraryMilestoneOneTest", "library.DocumentImportTest",
    "reader.ReadiumPublicationIntegrityTest", "reader.ReaderTapTranslationTest",
    "learning.AndroidOfflineExperienceTest",
    "backup.BackupInheritanceTest",
]
UI_CLASSES = [
    "learning.LearningUiParityTest#japaneseLearningWorkflow",
    "learning.LearningUiParityTest#emptyLearningPages",
    "learning.LearningUiParityTest#largeDarkLearningPages",
    "learning.LearningUiParityTest#primaryTabsAndLocalModels",
    "learning.LearningUiParityTest#thousandLearningRecordsRemainSearchableAndScrollable",
]

def preserve_screenshots(command, output, label):
    # Pull into a fresh path: adb nests the remote directory when its target
    # already exists. Merge only after a successful pull, keeping earlier
    # evidence if a later task or its cleanup is interrupted.
    snapshot = output / ("screenshot-snapshot-" + label)
    command(["pull", "/sdcard/Android/data/com.jerreader.android/files/learning-ui", str(snapshot)])
    destination = output / "screenshots"
    destination.mkdir(exist_ok=True)
    for path in snapshot.iterdir():
        if path.is_file():
            shutil.copyfile(path, destination / path.name)

def verify_report(text, minimum_tests=None):
    matches = re.findall(r"OK \((\d+) tests?\)", text)
    if not matches or sum(map(int, matches)) < 1 or text.count("INSTRUMENTATION_CODE: -1") != len(matches) or any(
        value in text for value in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")):
        raise RuntimeError("Android instrumentation did not report passing tests with zero failures")
    count = sum(map(int, matches))
    if minimum_tests is not None and (count < minimum_tests or
        len(re.findall(r"^INSTRUMENTATION_STATUS_CODE: 0\s*$", text, re.MULTILINE)) != count):
        raise RuntimeError("The reviewed Android test set was not fully executed without skips")
    return count

def verify_guest_startup(windows, events):
    if "WINDOW MANAGER WINDOWS" not in windows or "DUMP TIMEOUT" in windows or not events.strip():
        raise RuntimeError("Android guest startup diagnostics were incomplete; no tests were launched")
    if "Application Not Responding" in windows or re.search(r"\bam_anr\s*:", events):
        raise RuntimeError("Android guest reported a boot/service ANR; no tests were launched")

def prepare_offline_guest(command):
    # ConnectivityService supports airplane mode even on AOSP guests without
    # a telephony Binder service. Wi-Fi may remain enabled in airplane mode.
    command(["shell", "cmd", "connectivity", "airplane-mode", "enable"])
    command(["shell", "svc", "wifi", "disable"])

def verify_offline_guest(command):
    airplane = command(["shell", "settings", "get", "global", "airplane_mode_on"]).strip()
    wifi = command(["shell", "settings", "get", "global", "wifi_on"]).strip()
    if airplane != "1" or wifi != "0":
        raise RuntimeError("Offline guest preparation was not confirmed; tests were not launched")

def clean_temporary_test_apps(command):
    # Called only after creating our owned read-only guest. A base AVD can hold
    # a newer Jerreader; replacing its temporary overlay avoids downgrades and
    # signature conflicts without changing the original AVD or other packages.
    packages = command(["shell", "pm", "list", "packages"]).splitlines()
    for package in ("com.jerreader.android.test", "com.jerreader.android"):
        if "package:" + package in packages:
            if command(["uninstall", package]).strip() != "Success":
                raise RuntimeError("Could not reset a test package in the temporary guest")

def verify_release_launch(start, hierarchy, pid):
    if "Status: ok" not in start or not re.fullmatch(r"[0-9]+", pid.strip()):
        raise RuntimeError("Release APK did not stay running after launch")
    if not all(value in hierarchy for value in ("com.jerreader.android", "书架", "导入电子书")):
        raise RuntimeError("Release APK did not render its initial library")


def main():
    destination, output_arg, scope = sys.argv[1:]
    if scope not in ("full", "ui"): raise RuntimeError("Unexpected Android validation scope")
    match = re.fullmatch(r"platform=Android,serial=(emulator-5580),avd=([A-Za-z0-9_-]+)", destination)
    if not match or os.environ.get("JERREADER_ALLOW_SIMULATOR") != "1":
        raise RuntimeError("Missing approved single emulator destination")
    allow_thermal_throttling = os.environ.get("JERREADER_SESSION_ALLOW_THERMAL_THROTTLING") == "1"
    # Inherit the process group created by Supervisor.run; its monitor owns all descendants.
    parent = subprocess.check_output(["/bin/ps", "-p", str(os.getppid()), "-o", "args="], text=True)
    if "safe_ios_validation.py" not in parent:
        raise RuntimeError("Use scripts/safe_android_build.sh test; never run this helper directly")
    output = Path(output_arg).resolve()
    if output.parent != (ROOT / ".build/SafeValidationLogs").resolve():
        raise RuntimeError("Unexpected report directory")
    serial, avd = match.groups()
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    adb = sdk / "platform-tools/adb"
    emulator = sdk / "emulator/emulator"
    app = ROOT / "androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    test = ROOT / "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk"
    release_smoke = os.environ.get("JERREADER_ANDROID_RELEASE_SMOKE") == "1"
    release_app = ROOT / "androidApp/build/outputs/apk/release/androidApp-release.apk"
    for path in (adb, emulator, app, test, *([release_app] if release_smoke else [])):
        if not path.is_file(): raise RuntimeError("Required prebuilt artifact or SDK tool missing: " + str(path))
    def command(args, timeout=60):
        result = subprocess.run([str(adb), "-s", serial, *args], text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode: raise RuntimeError(result.stdout)
        return result.stdout
    def wait_for_stable_resources(label):
        print(label + "; waiting for 30 continuous seconds of stable resources", flush=True)
        stable_since = None
        deadline = time.monotonic() + guard.COOL_TIMEOUT
        while True:
            sample = guard.health()
            reason = guard.unsafe(sample, starting=True, allow_thermal_throttling=allow_thermal_throttling)
            if reason: raise RuntimeError(reason)
            now = time.monotonic()
            stable_since = (stable_since if stable_since is not None else now) if guard.cool(
                sample, allow_thermal_throttling=allow_thermal_throttling,
                allow_high_load=os.environ.get("JERREADER_SESSION_ALLOW_HIGH_LOAD") == "1") else None
            if stable_since is not None and now - stable_since >= guard.COOL_SECONDS: break
            if now >= deadline: raise RuntimeError(label + " resources did not settle; tests were not launched")
            time.sleep(guard.POLL_SECONDS)
    def check_guest_startup(label):
        windows = command(["shell", "dumpsys", "window", "windows"])
        events = command(["logcat", "-b", "events", "-d"])
        (output / ("android-" + label + "-windows.txt")).write_text(windows)
        (output / ("android-" + label + "-events.txt")).write_text(events)
        verify_guest_startup(windows, events)
    inventory = subprocess.check_output([str(adb), "devices"], text=True)
    if serial in inventory: raise RuntimeError("Selected emulator is already active; nothing was changed")
    with (output / "emulator.log").open("w") as stream:
        process = subprocess.Popen([str(emulator), "-avd", avd, "-port", "5580", "-read-only",
            "-no-snapshot", "-no-window", "-no-audio", "-no-boot-anim", "-gpu", "swiftshader",
            "-feature", "-Vulkan", "-cores", "2", "-memory", "2048"], stdout=stream, stderr=subprocess.STDOUT)
        try:
            command(["wait-for-device"], timeout=180)
            deadline = time.monotonic() + 180
            while command(["shell", "getprop", "sys.boot_completed"]).strip() != "1":
                if process.poll() is not None or time.monotonic() > deadline: raise RuntimeError("Emulator boot failed/timed out")
                time.sleep(2)
            # boot_completed does not guarantee that cold system services have
            # settled. Do not compete with their startup by installing APKs or
            # sending connectivity broadcasts immediately after this property.
            wait_for_stable_resources("Android boot completed")
            check_guest_startup("boot")
            # Dedicated read-only guest, network disabled: regression tests cannot rely on services.
            prepare_offline_guest(command)
            # Match iPhone 16 Pro's 402 x 874 logical viewport in this disposable guest.
            # Keeping equal logical width avoids mistaking different line wrapping for a UI bug.
            command(["shell", "wm", "size", "1206x2622"])
            command(["shell", "wm", "density", "480"])
            for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                command(["shell", "settings", "put", "global", setting, "0"])
            command(["shell", "input", "keyevent", "82"])
            clean_temporary_test_apps(command)
            command(["install", "-r", "-t", str(app)], timeout=120)
            command(["install", "-r", "-t", str(test)], timeout=120)
            # Only this owned read-only guest's temporary overlay is reset. The
            # original AVD data stays intact; UI comparison starts with an empty shelf.
            if command(["shell", "pm", "clear", "com.jerreader.android"]).strip() != "Success":
                raise RuntimeError("Could not prepare a clean temporary test app")
            wait_for_stable_resources("Android ready before tests")
            verify_offline_guest(command)
            print("Offline guest confirmed: airplane mode enabled, Wi-Fi disabled", flush=True)
            check_guest_startup("startup")
            print("Guest startup verified: no boot/service ANR or blocking ANR window", flush=True)
            (output / "android-device.json").write_text(json.dumps(dict(
                avd=avd, scope=scope, cores=2, memory_mib_requested=2048, renderer="swiftshader", guest_vulkan=False,
                memory_total=command(["shell", "cat", "/proc/meminfo"]).splitlines()[0],
                android_release=command(["shell", "getprop", "ro.build.version.release"]).strip(),
                sdk=command(["shell", "getprop", "ro.build.version.sdk"]).strip(),
                screen=command(["shell", "wm", "size"]).strip(),
                density=command(["shell", "wm", "density"]).strip(),
            ), indent=2) + "\n")
            reports = []
            def instrument(names, label, minimum, managed_font=False):
                classes = ",".join("com.jerreader.android." + name for name in names)
                args = ["shell", "am", "instrument", "-w", "-r", "-e", "class", classes]
                if managed_font: args += ["-e", "jerreaderFontScaleManaged", "true"]
                report = command(args + ["com.jerreader.android.test/androidx.test.runner.AndroidJUnitRunner"], timeout=1200)
                (output / ("instrumentation-" + label + ".txt")).write_text(report)
                reports.append(report)
                (output / "instrumentation.txt").write_text("\n".join(reports))
                print(report, flush=True)
                verify_report(report, minimum_tests=minimum)
            if scope == "full": instrument(REGRESSION_CLASSES, "regression", 17)
            # Global font changes can restart System UI while UiAutomation is
            # attached. Prepare each size outside instrumentation, then run
            # each reviewed UI task once in its own fresh process, serially.
            for index, test_class in enumerate(UI_CLASSES):
                font = "1.5" if test_class.endswith("#largeDarkLearningPages") else "1.0"
                command(["shell", "settings", "put", "system", "font_scale", font])
                time.sleep(2)
                print("Prepared UI task %s with system font scale %s" % (index + 1, font), flush=True)
                instrument([test_class], "ui-" + str(index + 1), 1, managed_font=True)
                preserve_screenshots(command, output, "ui-" + str(index + 1))
            verify_report("\n".join(reports), minimum_tests=len(UI_CLASSES) + (17 if scope == "full" else 0))
            if release_smoke:
                # Same owned disposable guest; use the actual deliverable after the
                # instrumented Debug suite. An in-place upgrade also verifies that
                # the deliverable retains the established signing identity.
                # No private signing material is read here.
                installed = command(["install", "-r", str(release_app)], timeout=120)
                if "Success" not in installed: raise RuntimeError("Release APK installation was not confirmed")
                start = command(["shell", "am", "start", "-W", "-n", "com.jerreader.android/.MainActivity"])
                command(["shell", "uiautomator", "dump", "/data/local/tmp/jerreader-release-ui.xml"])
                hierarchy = command(["shell", "cat", "/data/local/tmp/jerreader-release-ui.xml"])
                pid = command(["shell", "pidof", "com.jerreader.android"])
                verify_release_launch(start, hierarchy, pid)
                (output / "release-launch.txt").write_text(installed + start + "pid=" + pid)
                sha = hashlib.sha256()
                with release_app.open("rb") as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""): sha.update(block)
                (output / "release-apk.sha256").write_text(sha.hexdigest() + "  " + release_app.name + "\n")
                (output / "release-ui.xml").write_text(hierarchy)
                shot = subprocess.run([str(adb), "-s", serial, "exec-out", "screencap", "-p"],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=30).stdout
                if not shot.startswith(bytes.fromhex("89504e470d0a1a0a")): raise RuntimeError("Release screenshot was not PNG")
                (output / "screenshots/android-release-library.png").write_bytes(shot)
                check_guest_startup("release")
                print("Release APK upgraded in place, launched and rendered its library offline", flush=True)

        finally:
            if process.poll() is None:
                try: preserve_screenshots(command, output, "cleanup")
                except (RuntimeError, subprocess.TimeoutExpired): pass
                # Preserve guest-side evidence before destroying its temporary overlay.
                # Host resource samples alone cannot identify a System UI ANR.
                for filename, args in (
                    ("android-logcat.txt", ["logcat", "-b", "all", "-d"]),
                    ("android-last-anr.txt", ["shell", "dumpsys", "activity", "lastanr"]),
                    ("android-windows.txt", ["shell", "dumpsys", "window", "windows"]),
                    ("android-memory.txt", ["shell", "dumpsys", "meminfo"]),
                ):
                    try: (output / filename).write_text(command(args, timeout=20))
                    except (RuntimeError, subprocess.TimeoutExpired) as error:
                        (output / filename).write_text("Diagnostic unavailable: " + str(error))
                try: command(["emu", "kill"], timeout=15)
                except (RuntimeError, subprocess.TimeoutExpired): process.terminate()
            try: process.wait(timeout=30)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)

if __name__ == "__main__": main()
