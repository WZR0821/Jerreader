#!/usr/bin/env python3
"""Verify and copy the arm64-v8a Release split whose x86_64 twin passed the emulator smoke."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent

def dex_classes(data):
    if not data.startswith(b'dex\n'):
        raise ValueError('Unexpected DEX format')
    string_count, string_offset = struct.unpack_from('<II', data, 56)
    type_count, type_offset = struct.unpack_from('<II', data, 64)
    class_count, class_offset = struct.unpack_from('<II', data, 96)
    strings = struct.unpack_from('<' + 'I' * string_count, data, string_offset)
    types = struct.unpack_from('<' + 'I' * type_count, data, type_offset)
    for index in range(class_count):
        type_index = struct.unpack_from('<I', data, class_offset + index * 32)[0]
        offset = strings[types[type_index]]
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        end = data.index(b'\x00', offset)
        yield data[offset:end]

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()

def abi_element(elements, abi):
    matches = [item for item in elements
               if [(f.get('filterType'), f.get('value')) for f in item.get('filters', [])] == [('ABI', abi)]]
    if len(matches) != 1:
        raise ValueError('Expected exactly one Release APK for ' + abi)
    return matches[0]

def shared_payload(path):
    """CRC and size of every entry that must not depend on the ABI split."""
    with zipfile.ZipFile(path) as archive:
        return {info.filename: (info.CRC, info.file_size) for info in archive.infolist()
                if not info.filename.startswith(('lib/', 'META-INF/'))}

def native_libraries(path):
    with zipfile.ZipFile(path) as archive:
        return [name for name in archive.namelist() if name.startswith('lib/')]

def verify_split_pair(delivered, validated):
    """The phone APK may differ from the emulator-validated APK only in its native code."""
    if shared_payload(delivered) != shared_payload(validated):
        raise ValueError('The arm64-v8a APK differs from the validated x86_64 APK outside lib/')
    arm = native_libraries(delivered)
    x86 = native_libraries(validated)
    if not arm or any(not name.startswith('lib/arm64-v8a/') for name in arm):
        raise ValueError('The delivered APK must carry arm64-v8a native code only')
    if sorted(n.split('/', 2)[2] for n in arm) != sorted(n.split('/', 2)[2] for n in x86 if n.startswith('lib/x86_64/')):
        raise ValueError('The two ABI splits do not carry the same native libraries')

# Since 2.0.0 the emulator smoke counts a book as opened only when the reader
# shows it, and it checks that ML Kit can start. Device logs from older smoke
# runs (1.7.0 and 1.7.1 passed with a Release that could not open PDFs) cannot
# vouch for an APK.
RELEASE_SMOKE_STEPS = ('opened epub in ReaderActivity', 'opened pdf in ReaderActivity',
                       'created every ML Kit component registrar')

def verify_release_smoke(smoke):
    if not all(step in smoke for step in RELEASE_SMOKE_STEPS):
        raise ValueError('The Release candidate did not open an EPUB and a PDF and start ML Kit on the emulator')

def certificate(apksigner, path):
    result = subprocess.run([str(apksigner), 'verify', '--verbose', '--print-certs', str(path)],
        capture_output=True, text=True, check=True)
    # Keep personal certificate details out of logs and reports.
    values = re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]+)', result.stdout)
    if not values:
        raise ValueError('No verified signing identity')
    return values

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-log', type=Path, required=True)
    parser.add_argument('--device-log', type=Path, required=True)
    parser.add_argument('--previous-apk', type=Path, required=True)
    args = parser.parse_args()
    for directory, mode in ((args.build_log, 'android'), (args.device_log, 'android-test')):
        report = json.loads((directory / 'summary.json').read_text())
        if report['mode'] != mode or report['outcome'] != 'passed':
            raise ValueError('A successful safety-supervised stage is required')
    launch = (args.device_log / 'release-launch.txt').read_text()
    if 'Success' not in launch or 'Status: ok' not in launch:
        raise ValueError('Release installation and launch were not confirmed')
    apk_root = ROOT / 'androidApp/build/outputs/apk/release'
    metadata = json.loads((apk_root / 'output-metadata.json').read_text())['elements']
    if len(metadata) != 2:
        raise ValueError('Expected exactly the arm64-v8a and x86_64 Release splits')
    item = abi_element(metadata, 'arm64-v8a')
    validated_item = abi_element(metadata, 'x86_64')
    version_source = (ROOT / 'androidApp/build.gradle.kts').read_text()
    version = re.search(r'versionName\s*=\s*"([^"]+)"', version_source)[1]
    build = int(re.search(r'versionCode\s*=\s*(\d+)', version_source)[1])
    for element in (item, validated_item):
        if element['versionName'] != version or element['versionCode'] != build:
            raise ValueError('Unexpected application version')
    source = apk_root / item['outputFile']
    validated = apk_root / validated_item['outputFile']
    device = json.loads((args.device_log / 'android-device.json').read_text())
    instrumentation = (args.device_log / 'instrumentation.txt').read_text()
    total = sum(map(int, re.findall(r'OK \((\d+) tests?\)', instrumentation)))
    minimum = 22 if device['scope'] == 'full' else 5
    if total < minimum or len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$', instrumentation, re.MULTILINE)) != total or 'thousandLearningRecordsRemainSearchableAndScrollable' not in instrumentation:
        raise ValueError('The current reviewed UI set must pass, including large-list navigation')
    if (args.device_log / 'release-apk.sha256').read_text().split()[0] != digest(validated):
        raise ValueError('The APK differs from the installed and launched Release candidate')
    verify_release_smoke((args.device_log / 'release-smoke.txt').read_text())
    verify_split_pair(source, validated)
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools'
    toolset = next(p for p in sorted(sdk.iterdir(), reverse=True) if (p / 'apksigner').is_file() and (p / 'aapt2').is_file())
    if not (certificate(toolset / 'apksigner', source) == certificate(toolset / 'apksigner', validated)
            == certificate(toolset / 'apksigner', args.previous_apk)):
        raise ValueError('The APK would change the established signing identity')
    badging = subprocess.check_output([str(toolset / 'aapt2'), 'dump', 'badging', str(source)], text=True)
    if "name='com.jerreader.android'" not in badging or 'application-debuggable' in badging:
        raise ValueError('Unexpected package or debuggable Release')
    minimum = re.search(r"(?:minSdkVersion|sdkVersion):\s*'(\d+)'", badging)
    seen = set()
    markers = {'folder_restore': '从备份文件夹恢复'.encode(), 'combined_models': '中日句段翻译'.encode(),
               'staged_cover': '撤销新封面'.encode(), 'privacy_ui': 'API Key 只保存在本机安全存储'.encode(),
               'word_detail': '词条内容已复制到剪贴板。'.encode(), 'reader_chrome': '快速调整阅读进度'.encode(), 'interface_controls': '顶部栏透明度'.encode(), 'furigana': '原文注音'.encode()}
    found = set()
    with zipfile.ZipFile(source) as archive:
        if archive.testzip() is not None:
            raise ValueError('APK ZIP CRC failure')
        for name in archive.namelist():
            if not name.endswith('.dex'):
                continue
            if not re.fullmatch(r'classes(?:[1-9][0-9]*)?\.dex', name):
                raise ValueError('Unexpected duplicate DEX artifact name')
            data = archive.read(name)
            for descriptor in dex_classes(data):
                if descriptor in seen:
                    raise ValueError('Duplicate class definitions in the delivered APK')
                if b'LearningUiFixtureActivity' in descriptor:
                    raise ValueError('Debug fixture leaked into Release')
                seen.add(descriptor)
            found.update(key for key, value in markers.items() if value in data)
    if found != set(markers):
        raise ValueError('The APK is missing the current feature changes')
    output = ROOT / 'dist/Android'
    output.mkdir(parents=True, exist_ok=True)
    target = output / f'Jerreader-Android-{version}-build{build}-{datetime.now():%Y%m%d-%H%M%S}-release.apk'
    if target.exists():
        raise FileExistsError(target)
    shutil.copy2(source, target)
    target.chmod(0o644)
    sha = digest(target)
    if sha != digest(source):
        raise ValueError('Packaged APK differs from the verified APK')
    target.with_suffix('.apk.sha256').write_text(sha + '  ' + target.name + '\n')
    result = dict(file=str(target), version=version, build=build, bytes=target.stat().st_size,
        min_sdk=int(minimum[1]) if minimum else None, sha256=sha, abi='arm64-v8a',
        emulator_validated_split=dict(file=validated.name, sha256=digest(validated), abi='x86_64'),
        identical_outside_native_code=True,
        verified_signature=True, signing_identity_matches_previous=True,
        duplicate_class_definitions=0, class_definitions=len(seen), debug_fixture=False)
    target.with_suffix('.apk.validation.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))

if __name__ == '__main__':
    main()
