#!/usr/bin/env python3
"""Package a verified unsigned generic-device Release build, preserving Unix modes."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import stat
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
MACH_MAGICS = {bytes.fromhex(value) for value in ('feedface', 'cefaedfe', 'feedfacf', 'cffaedfe', 'cafebabe', 'bebafeca', 'cafebabf', 'bfbafeca')}

def package(logdir: Path, output_directory: Path):
    logdir = logdir.resolve()
    summary = json.loads((logdir / 'summary.json').read_text())
    if summary['mode'] != 'build' or summary['outcome'] != 'passed':
        raise ValueError('A successful safe_xcodebuild.sh build is required')
    logs = list(logdir.glob('*-build.log'))
    if len(logs) != 1: raise ValueError('Missing unique Release build log')
    log = logs[0].read_text()
    if not all(value in log for value in ('-configuration Release', 'generic/platform=iOS', 'CODE_SIGNING_ALLOWED=NO', '** BUILD SUCCEEDED **')):
        raise ValueError('The recorded build is not a successful unsigned generic iOS Release build')
    app = ROOT / '.build/SafeDerivedData/Build/Products/Release-iphoneos/Jerreader.app'
    info = plistlib.loads((app / 'Info.plist').read_bytes())
    if info.get('CFBundleSupportedPlatforms') != ['iPhoneOS'] or info.get('CFBundleName') != 'Jerreader':
        raise ValueError('Unexpected app/platform')
    executable = app / info['CFBundleExecutable']
    if not executable.is_file() or not os.access(executable, os.X_OK): raise ValueError('Main executable is not executable')
    macho_count = 0
    for path in app.rglob('*'):
        if path.name in ('_CodeSignature', 'embedded.mobileprovision') or path.suffix in ('.epub', '.xctest'):
            raise ValueError('Signing data or test fixture in Release app: ' + str(path.relative_to(app)))
        if path.is_file() and not path.is_symlink():
            with path.open('rb') as stream: magic = stream.read(4)
            if magic in MACH_MAGICS:
                macho_count += 1
                arches = subprocess.check_output(['/usr/bin/xcrun', 'lipo', '-archs', str(path)], text=True).strip()
                if arches != 'arm64': raise ValueError('Non-device architecture: ' + arches)
                commands = subprocess.check_output(['/usr/bin/xcrun', 'otool', '-l', str(path)], text=True)
                if re.search(r'cmd LC_CODE_SIGNATURE\b', commands): raise ValueError('Binary already contains a code signature')
                if re.search(r'cryptid\s+[1-9]', commands): raise ValueError('Encrypted executable')
    version, build = info['CFBundleShortVersionString'], info['CFBundleVersion']
    if not re.fullmatch(r'[0-9.]+', version) or not str(build).isdigit(): raise ValueError('Invalid version')
    output_directory.mkdir(parents=True, exist_ok=True)
    target = output_directory / f'Jerreader-{version}-build{build}-{datetime.now():%Y%m%d-%H%M%S}-unsigned.ipa'
    archive_root = 'Payload/' + app.name
    with zipfile.ZipFile(target, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in [app, *sorted(app.rglob('*'))]:
            relative = path.relative_to(app)
            name = archive_root + ('/' + relative.as_posix() if relative.parts else '')
            mode = path.lstat().st_mode
            if stat.S_ISDIR(mode): name += '/'
            entry = zipfile.ZipInfo(name)
            entry.create_system = 3
            entry.external_attr = mode << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            if stat.S_ISLNK(mode): data = os.readlink(path).encode()
            elif stat.S_ISDIR(mode): data = b''
            elif stat.S_ISREG(mode): data = path.read_bytes()
            else: raise ValueError('Unsupported file type')
            archive.writestr(entry, data)
    with zipfile.ZipFile(target) as archive:
        if archive.testzip() is not None: raise ValueError('ZIP CRC validation failed')
        binary = archive.getinfo(archive_root + '/' + info['CFBundleExecutable'])
        if not (binary.external_attr >> 16) & 0o111: raise ValueError('ZIP lost execute permissions')
        if plistlib.loads(archive.read(archive_root + '/Info.plist')) != info: raise ValueError('Packaged metadata differs')
        for path in app.rglob('*'):
            if path.is_file() and not path.is_symlink():
                packaged = archive.read(archive_root + '/' + path.relative_to(app).as_posix())
                if hashlib.sha256(packaged).digest() != hashlib.sha256(path.read_bytes()).digest():
                    raise ValueError('Packaged file differs: ' + str(path.relative_to(app)))
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix('.ipa.sha256').write_text(digest + '  ' + target.name + '\n')
    result = dict(file=str(target), bytes=target.stat().st_size, version=version, build=build,
                  minimum_ios=info['MinimumOSVersion'], sha256=digest, unsigned=True,
                  arm64_only=True, macho_files=macho_count, zip_crc_verified=True,
                  executable_permissions_preserved=True, packaged_files_match=True, test_fixtures=False)
    target.with_suffix('.ipa.validation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
    return target

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--validation-log', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'dist/iOS')
    args = parser.parse_args()
    package(args.validation_log, args.output)
