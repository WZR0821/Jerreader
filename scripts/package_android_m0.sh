#!/bin/sh

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUTPUT_DIR="$PROJECT_ROOT/dist/Android"
SOURCE_APK="$PROJECT_ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
TARGET_APK="$OUTPUT_DIR/Jerreader-Android-M0-installable.apk"
CHECKSUM_FILE="$TARGET_APK.sha256"

cd "$PROJECT_ROOT"
./gradlew --no-daemon \
    :shared:testAndroidHostTest \
    :androidApp:testDebugUnitTest \
    :androidApp:assembleDebug

mkdir -p "$OUTPUT_DIR"
install -m 0644 "$SOURCE_APK" "$TARGET_APK"

if command -v shasum >/dev/null 2>&1; then
    (cd "$OUTPUT_DIR" && shasum -a 256 "$(basename "$TARGET_APK")") > "$CHECKSUM_FILE"
elif command -v sha256sum >/dev/null 2>&1; then
    (cd "$OUTPUT_DIR" && sha256sum "$(basename "$TARGET_APK")") > "$CHECKSUM_FILE"
else
    echo "No SHA-256 utility found (shasum or sha256sum)." >&2
    exit 1
fi

echo "APK: $TARGET_APK"
echo "SHA-256: $CHECKSUM_FILE"
