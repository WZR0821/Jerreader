#!/usr/bin/env python3
"""Verify and copy the already built, device-validated Release APK."""
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
    if len(metadata) != 1:
        raise ValueError('Expected one universal Release APK')
    item = metadata[0]
    version_source = (ROOT / 'androidApp/build.gradle.kts').read_text()
    version = re.search(r'versionName\s*=\s*"([^"]+)"', version_source)[1]
    build = int(re.search(r'versionCode\s*=\s*(\d+)', version_source)[1])
    if item['versionName'] != version or item['versionCode'] != build:
        raise ValueError('Unexpected application version')
    source = apk_root / item['outputFile']
    device = json.loads((args.device_log / 'android-device.json').read_text())
    instrumentation = (args.device_log / 'instrumentation.txt').read_text()
    total = sum(map(int, re.findall(r'OK \((\d+) tests?\)', instrumentation)))
    minimum = 22 if device['scope'] == 'full' else 5
    if total < minimum or len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$', instrumentation, re.MULTILINE)) != total or 'thousandLearningRecordsRemainSearchableAndScrollable' not in instrumentation:
        raise ValueError('The current reviewed UI set must pass, including large-list navigation')
    if (args.device_log / 'release-apk.sha256').read_text().split()[0] != digest(source):
        raise ValueError('The APK differs from the installed and launched Release candidate')
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools'
    toolset = next(p for p in sorted(sdk.iterdir(), reverse=True) if (p / 'apksigner').is_file() and (p / 'aapt2').is_file())
    if certificate(toolset / 'apksigner', source) != certificate(toolset / 'apksigner', args.previous_apk):
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
        min_sdk=int(minimum[1]) if minimum else None, sha256=sha,
        verified_signature=True, signing_identity_matches_previous=True,
        duplicate_class_definitions=0, class_definitions=len(seen), debug_fixture=False)
    target.with_suffix('.apk.validation.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))

if __name__ == '__main__':
    main()
