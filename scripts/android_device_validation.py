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
import xml.etree.ElementTree as ElementTree
import zipfile
import io
import safe_ios_validation as guard

ROOT = Path(__file__).resolve().parent.parent
REGRESSION_CLASSES = [
    "library.LibraryMilestoneOneTest", "library.DocumentImportTest",
    "reader.ReadiumPublicationIntegrityTest", "reader.ReaderTapTranslationTest",
    "reader.PdfLocatorFormatTest",
    "learning.AndroidOfflineExperienceTest",
    "backup.BackupInheritanceTest",
    "translation.AIProviderSettingsStoreTest",
    # 2026-11-16: these were in androidTest from the start but never listed, so
    # no device run had executed them.
    "learning.BookCoverCacheTest", "learning.ContextualAnalysisPersistenceTest",
    "reader.ReaderMilestoneTwoTest", "reader.ReaderWordLookupTest",
    "translation.TranslationCacheTest",
    # 2026-11-18, GitHub issue #2: sideways swipes while a book scrolls.
    "reader.ReaderScrollGestureTest",
]
UI_CLASSES = [
    "learning.LearningUiParityTest#japaneseLearningWorkflow",
    "learning.LearningUiParityTest#emptyLearningPages",
    "learning.LearningUiParityTest#largeDarkLearningPages",
    "learning.LearningUiParityTest#primaryTabsAndLocalModels",
    "learning.LearningUiParityTest#aiModelsProvidersAndModels",
    "learning.LearningUiParityTest#thousandLearningRecordsRemainSearchableAndScrollable",
    # Unlisted until 2026-11-16, like the regression classes above.
    "learning.LearningUiParityTest#contextualLookupActionsAndAI",
    "learning.LearningUiParityTest#contextualInputEditingClearsPreviousResult",
    "learning.LearningUiParityTest#contextualLargeFont",
    "learning.LearningUiParityTest#contextualPersistenceFailure",
    "learning.LearningUiParityTest#japaneseAndChineseLookupWorkWithoutNetwork",
    "learning.LearningUiParityTest#bundledDictionaryFindsNonCoreWordsAndHandlesMisses",
]
# The UI tasks that run at the large system font; every other one runs at 1.0.
LARGE_FONT_UI_CLASSES = ("#largeDarkLearningPages", "#contextualLargeFont")

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

PACKAGE = "com.jerreader.android"
SMOKE_TITLE = "读鼠发布冒烟测试"
# Release is split by ABI. The x86_64 split is what this Mac's emulator can run;
# packaging delivers the arm64-v8a split only after checking that both carry
# identical DEX, resources and assets.
DEBUG_APK = "androidApp/build/outputs/apk/debug/androidApp-x86_64-debug.apk"
RELEASE_APK = "androidApp/build/outputs/apk/release/androidApp-x86_64-release.apk"

def smoke_epub():
    """A tiny valid EPUB 3 with Japanese and Chinese text, built in memory."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        def add(name, text):
            archive.writestr(name, text.encode("utf-8"), compress_type=zipfile.ZIP_DEFLATED)
        add("META-INF/container.xml", '<?xml version="1.0" encoding="UTF-8"?>'
            '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>'
            '<rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/>'
            '</rootfiles></container>')
        add("EPUB/package.opf", '<?xml version="1.0" encoding="UTF-8"?>'
            '<package version="3.0" unique-identifier="id" xmlns="http://www.idpf.org/2007/opf">'
            '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
            '<dc:identifier id="id">jerreader-release-smoke</dc:identifier>'
            '<dc:title>' + SMOKE_TITLE + '</dc:title><dc:language>ja</dc:language>'
            '<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata>'
            '<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
            '<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>'
            '<spine><itemref idref="c1"/></spine></package>')
        add("EPUB/nav.xhtml", '<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" '
            'xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目次</title></head><body>'
            '<nav epub:type="toc"><ol><li><a href="c1.xhtml">第一章</a></li></ol></nav></body></html>')
        add("EPUB/c1.xhtml", '<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" '
            'xml:lang="ja"><head><title>第一章</title></head><body><h1>第一章</h1>'
            '<p>吾輩は猫である。名前はまだ無い。</p><p>这是发布前的冒烟测试。</p></body></html>')
    return buffer.getvalue()

def smoke_pdf():
    """A one-page PDF with a correct cross-reference table."""
    content = b"BT /F1 24 Tf 72 760 Td (Jerreader release smoke PDF) Tj ET"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
        b"/Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + content + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Title (Jerreader release smoke PDF) >>",
    ]
    data = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, body in enumerate(objects, 1):
        offsets.append(len(data))
        data += str(number).encode() + b" 0 obj\n" + body + b"\nendobj\n"
    start = len(data)
    data += b"xref\n0 " + str(len(objects) + 1).encode() + b"\n0000000000 65535 f \n"
    for offset in offsets:
        data += ("%010d 00000 n \n" % offset).encode()
    data += (b"trailer\n<< /Size " + str(len(objects) + 1).encode() + b" /Root 1 0 R /Info 6 0 R >>\n"
             b"startxref\n" + str(start).encode() + b"\n%%EOF\n")
    return bytes(data)

def resumed_activity(activities):
    """Name of the resumed Jerreader activity in `dumpsys activity activities`, if any."""
    match = re.search(r"(?:topResumedActivity=|ResumedActivity: ?)ActivityRecord\{[^}]*?\b"
                      + re.escape(PACKAGE) + r"/([.\w$]+)", activities)
    return match[1] if match else None

def node_center(hierarchy, label):
    """Centre of the first on-screen node whose text or description is exactly `label`."""
    for node in ElementTree.fromstring(hierarchy).iter("node"):
        if label in (node.get("text"), node.get("content-desc")):
            bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if bounds:
                left, top, right, bottom = map(int, bounds.groups())
                if right > left and bottom > top:
                    return (left + right) // 2, (top + bottom) // 2
    raise RuntimeError("Release UI did not show " + label)

def verify_no_release_crash(crashes, pid_before, pid_after):
    if "Process: " + PACKAGE in crashes or ("FATAL EXCEPTION" in crashes and PACKAGE in crashes):
        raise RuntimeError("Release APK crashed during the smoke test")
    if not re.fullmatch(r"[0-9]+", pid_after.strip()) or pid_after.strip() != pid_before.strip():
        raise RuntimeError("Release APK process restarted or died during the smoke test")

def verify_release_launch(start, hierarchy, pid):
    if "Status: ok" not in start or not re.fullmatch(r"[0-9]+", pid.strip()):
        raise RuntimeError("Release APK did not stay running after launch")
    if not all(value in hierarchy for value in ("com.jerreader.android", "书架", "导入电子书")):
        raise RuntimeError("Release APK did not render its initial library")

def verify_release_reader(hierarchy, label):
    """ReaderActivity alone is not proof: a book that fails to open still lands
    there and shows ReaderFailureView (1.7.0–1.7.1 Release PDFs did)."""
    if PACKAGE not in hierarchy or "无法打开这本书" in hierarchy:
        raise RuntimeError("Release reader could not open the smoke " + label)

def verify_release_components(logcat):
    """ML Kit (OCR, on-device translation) finds its parts through Firebase
    component registrars created by reflection. If R8 strips their
    constructors the app still launches, but every ML Kit call fails later."""
    if "InvalidRegistrarException" in logcat or "Invalid component registrar" in logcat:
        raise RuntimeError("Release APK could not create an ML Kit component registrar")


def main():
    destination, output_arg, scope = sys.argv[1:]
    if scope not in ("full", "ui"): raise RuntimeError("Unexpected Android validation scope")
    match = re.fullmatch(r"platform=Android,serial=(emulator-5580),avd=([A-Za-z0-9_-]+)", destination)
    if not match or os.environ.get("JERREADER_ALLOW_SIMULATOR") != "1":
        raise RuntimeError("Missing approved single emulator destination")
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
    app = ROOT / DEBUG_APK
    test = ROOT / "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk"
    release_smoke = os.environ.get("JERREADER_ANDROID_RELEASE_SMOKE") == "1"
    release_app = ROOT / RELEASE_APK
    for path in (adb, emulator, app, test, *([release_app] if release_smoke else [])):
        if not path.is_file(): raise RuntimeError("Required prebuilt artifact or SDK tool missing: " + str(path))
    def command(args, timeout=60):
        result = subprocess.run([str(adb), "-s", serial, *args], text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode: raise RuntimeError(result.stdout)
        return result.stdout
    def wait_for_stable_resources(label):
        # Same rule as Supervisor.cooldown: memory/disk stop the run, load only
        # delays it for a bounded time (user decision, 2026-11-13).
        print(label + "; waiting up to 5 minutes for 30 continuous seconds of stable resources", flush=True)
        stable_since = None
        deadline = time.monotonic() + guard.COOL_TIMEOUT
        while True:
            sample = guard.health()
            reason = guard.unsafe(sample, starting=True)
            if reason: raise RuntimeError(reason)
            now = time.monotonic()
            stable_since = (stable_since if stable_since is not None else now) if guard.cool(
                sample, allow_high_load=os.environ.get("JERREADER_SESSION_ALLOW_HIGH_LOAD") == "1") else None
            if stable_since is not None and now - stable_since >= guard.COOL_SECONDS: break
            if now >= deadline:
                print(label + "; load did not settle (load=%.2f); continuing" % sample["load"], flush=True)
                break
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
                font = "1.5" if test_class.endswith(LARGE_FONT_UI_CLASSES) else "1.0"
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
                # Stage two small books inside the app's private files while the
                # debuggable build still allows run-as; `install -r` keeps them.
                # Opening them in the R8-shrunk Release exercises Readium's
                # EPUB and PDF navigators, which have no consumer keep rules.
                books = []
                for name, mime, data in (("jerreader-release-smoke.epub", "application/epub+zip", smoke_epub()),
                                         ("jerreader-release-smoke.pdf", "application/pdf", smoke_pdf())):
                    (output / name).write_bytes(data)
                    command(["push", str(output / name), "/data/local/tmp/" + name], timeout=60)
                    command(["shell", "cat /data/local/tmp/%s | run-as %s sh -c 'mkdir -p files/release-smoke && "
                             "cat > files/release-smoke/%s'" % (name, PACKAGE, name)])
                    size = command(["shell", "run-as", PACKAGE, "stat", "-c", "%s", "files/release-smoke/" + name])
                    if size.strip() != str(len(data)): raise RuntimeError("Release smoke book was not staged")
                    books.append((name, mime))
                home = command(["shell", "run-as", PACKAGE, "pwd"]).strip()
                if not re.fullmatch(r"/data/(?:user/0|data)/" + re.escape(PACKAGE), home):
                    raise RuntimeError("Unexpected app data directory for the release smoke")
                command(["logcat", "-b", "crash", "-c"])
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
                def screenshot(label):
                    shot = subprocess.run([str(adb), "-s", serial, "exec-out", "screencap", "-p"],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=30).stdout
                    if not shot.startswith(bytes.fromhex("89504e470d0a1a0a")): raise RuntimeError("Release screenshot was not PNG")
                    (output / "screenshots" / ("android-release-" + label + ".png")).write_bytes(shot)
                def wait_for_activity(name, label):
                    deadline = time.monotonic() + 90
                    while True:
                        activities = command(["shell", "dumpsys", "activity", "activities"], timeout=30)
                        if resumed_activity(activities) == name: return
                        if time.monotonic() > deadline:
                            (output / ("release-" + label + "-activities.txt")).write_text(activities)
                            raise RuntimeError("Release APK did not reach " + name + " for " + label)
                        time.sleep(2)
                def dump(label):
                    # Remove the last dump first so a failed one cannot pass stale UI.
                    command(["shell", "rm", "-f", "/data/local/tmp/jerreader-release-ui.xml"])
                    command(["shell", "uiautomator", "dump", "/data/local/tmp/jerreader-release-ui.xml"], timeout=60)
                    text = command(["shell", "cat", "/data/local/tmp/jerreader-release-ui.xml"])
                    (output / ("release-" + label + "-ui.xml")).write_text(text)
                    return text
                def still_healthy():
                    verify_no_release_crash(command(["logcat", "-b", "crash", "-d"]), pid,
                                            command(["shell", "pidof", PACKAGE]))
                smoke = []
                for name, mime in books:
                    label = name.rsplit(".", 1)[1]
                    command(["shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
                             "-d", "file://%s/files/release-smoke/%s" % (home, name), "-t", mime,
                             "-n", PACKAGE + "/.MainActivity"])
                    wait_for_activity(".reader.ReaderActivity", label)
                    time.sleep(8)  # let the navigator render its first page
                    still_healthy()
                    screenshot("reader-" + label)
                    verify_release_reader(dump("reader-" + label), label)
                    command(["shell", "input", "keyevent", "4"])
                    wait_for_activity(".MainActivity", label + "-back")
                    still_healthy()
                    smoke.append("opened " + label + " in ReaderActivity and returned to the library")
                release_log = command(["logcat", "-d", "--pid=" + pid.strip()], timeout=60)
                (output / "release-process-logcat.txt").write_text(release_log)
                verify_release_components(release_log)
                smoke.append("created every ML Kit component registrar")
                for index, tab in enumerate(("学习", "设置", "书架"), 1):
                    x, y = node_center(dump("before-" + tab), tab)
                    command(["shell", "input", "tap", str(x), str(y)])
                    time.sleep(3)
                    if tab not in dump("after-" + tab): raise RuntimeError("Release tab did not render: " + tab)
                    still_healthy()
                    screenshot("tab-" + str(index))
                    smoke.append("opened the " + tab + " tab")
                if SMOKE_TITLE not in dump("final-library"):
                    raise RuntimeError("Imported smoke EPUB is not on the Release library shelf")
                (output / "release-smoke.txt").write_text("\n".join(smoke) + "\n")
                print("Release smoke passed: " + "; ".join(smoke), flush=True)

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
