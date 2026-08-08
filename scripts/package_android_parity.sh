#!/bin/sh

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUTPUT_DIR="$PROJECT_ROOT/dist/Android"
SOURCE_APK="$PROJECT_ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
TARGET_APK="$OUTPUT_DIR/Jerreader-Android-1.0.2-parity.apk"
CHECKSUM_FILE="$TARGET_APK.sha256"

cd "$PROJECT_ROOT"

# The project lives in an iCloud-synced folder, whose file provider sometimes
# leaves "<name> 2.dex" duplicates inside build/, which then breaks dex merging.
find androidApp/build shared/build -name "* [0-9].*" -delete 2>/dev/null || true

./gradlew \
    :shared:testAndroidHostTest \
    :androidApp:testDebugUnitTest \
    :androidApp:lintDebug \
    :androidApp:assembleDebug

mkdir -p "$OUTPUT_DIR"
install -m 0644 "$SOURCE_APK" "$TARGET_APK"

if command -v shasum >/dev/null 2>&1; then
    (cd "$OUTPUT_DIR" && shasum -a 256 "$(basename "$TARGET_APK")") > "$CHECKSUM_FILE"
else
    (cd "$OUTPUT_DIR" && sha256sum "$(basename "$TARGET_APK")") > "$CHECKSUM_FILE"
fi

echo "APK: $TARGET_APK"
echo "SHA-256: $CHECKSUM_FILE"
