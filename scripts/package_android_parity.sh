#!/bin/sh

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUTPUT_DIR="$PROJECT_ROOT/dist/Android"
SOURCE_APK="$PROJECT_ROOT/androidApp/build/outputs/apk/debug/androidApp-arm64-v8a-debug.apk"

cd "$PROJECT_ROOT"

# Java must preserve non-ASCII workspace paths when spawning build processes.
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
if [ -z "${JAVA_HOME:-}" ] && [ -x "$PROJECT_ROOT/.tools/jdk17/Contents/Home/bin/java" ]; then
    export JAVA_HOME="$PROJECT_ROOT/.tools/jdk17/Contents/Home"
fi
command -v python3 >/dev/null 2>&1 || { echo 'Python 3 is required to read APK metadata.' >&2; exit 1; }


scripts/safe_android_build.sh

# Read the artifact's actual version; a stale hard-coded name can mislabel a new build.
ARTIFACT_VERSION=$(python3 - <<'PY'
import json
import re
from pathlib import Path

metadata = json.loads(Path('androidApp/build/outputs/apk/debug/output-metadata.json').read_text())
# Debug is split by ABI as well; phones take the arm64-v8a split.
elements = [e for e in metadata['elements'] if e['outputFile'] == 'androidApp-arm64-v8a-debug.apk']
assert len(elements) == 1
element = elements[0]
version = element['versionName']
assert re.fullmatch(r'[A-Za-z0-9._-]+', version), 'Invalid artifact version'
print(f"{version}-build{int(element['versionCode'])}")
PY
)
TARGET_APK="$OUTPUT_DIR/Jerreader-Android-$ARTIFACT_VERSION-debug.apk"
CHECKSUM_FILE="$TARGET_APK.sha256"

mkdir -p "$OUTPUT_DIR"
install -m 0644 "$SOURCE_APK" "$TARGET_APK"

if command -v shasum >/dev/null 2>&1; then
    (cd "$OUTPUT_DIR" && shasum -a 256 "$(basename "$TARGET_APK")") > "$CHECKSUM_FILE"
else
    (cd "$OUTPUT_DIR" && sha256sum "$(basename "$TARGET_APK")") > "$CHECKSUM_FILE"
fi

echo "APK: $TARGET_APK"
echo "SHA-256: $CHECKSUM_FILE"
