#!/bin/sh
#
# One release, both platforms: an IPA and an APK carrying the same version.
#
# The version comes from `version.properties` and nowhere else. Android reads it
# in `androidApp/build.gradle.kts`; `syncIosVersion` writes it into
# `iosApp/Version.xcconfig`, which the Xcode project references at the *project*
# level. No target may set MARKETING_VERSION or CURRENT_PROJECT_VERSION — a
# target-level value silently overrides the xcconfig, and that override is
# exactly how the two apps drifted apart before 1.3.0.
#
# Usage: ./scripts/release.sh [--android-only|--ios-only]
set -eu

PROJECT_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$PROJECT_ROOT"

# Xcode and Gradle both run without a login shell here, so the toolchains this
# repository builds with are named explicitly. See docs/MAINTENANCE.md.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
    JAVA_HOME="$PROJECT_ROOT/.tools/jdk17/Contents/Home"
    export JAVA_HOME
fi
if [ -z "${DEVELOPER_DIR:-}" ] && [ -d "$HOME/Downloads/Xcode.app/Contents/Developer" ]; then
    DEVELOPER_DIR="$HOME/Downloads/Xcode.app/Contents/Developer"
    export DEVELOPER_DIR
fi

BUILD_ANDROID=yes
BUILD_IOS=yes
case "${1:-}" in
    --android-only) BUILD_IOS=no ;;
    --ios-only) BUILD_ANDROID=no ;;
    "") ;;
    *) echo "usage: $0 [--android-only|--ios-only]" >&2; exit 2 ;;
esac

VERSION_NAME=$(sed -n 's/^versionName=//p' version.properties)
VERSION_CODE=$(sed -n 's/^versionCode=//p' version.properties)
if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
    echo "version.properties is missing versionName or versionCode" >&2
    exit 1
fi
echo "==> Jerreader $VERSION_NAME ($VERSION_CODE)"

DEST="$HOME/Desktop"
# The repository lives under a path with Chinese characters, which some transfer
# paths mangle. Deliverables are copied out to a plain ASCII location.
mkdir -p "$DEST"

./gradlew syncIosVersion --no-daemon -q

if [ "$BUILD_ANDROID" = yes ]; then
    echo "==> Android: tests"
    ./gradlew :core:testAndroidHostTest :androidApp:testDebugUnitTest --no-daemon -q

    echo "==> Android: release APK"
    # Signed with the debug key on purpose. Every sideloaded build the user has
    # installed so far carries that signature, and Android refuses to upgrade
    # across a signature change — switching to the release keystore would force
    # an uninstall and take the local library with it. See
    # docs/MAINTENANCE.md 「身份与签名」 before changing this.
    ./gradlew :androidApp:assembleRelease --no-daemon -q

    APK=$(find androidApp/build/outputs/apk/release -name "*.apk" | head -1)
    if [ -z "$APK" ]; then
        echo "no APK was produced" >&2
        exit 1
    fi
    cp "$APK" "$DEST/Jerreader-$VERSION_NAME-$VERSION_CODE.apk"
    echo "    $DEST/Jerreader-$VERSION_NAME-$VERSION_CODE.apk"
fi

if [ "$BUILD_IOS" = yes ]; then
    echo "==> iOS: archive"
    STAGE=$(mktemp -d)
    trap 'rm -rf "$STAGE"' EXIT

    # Unsigned: this produces a sideloadable payload, not a store submission.
    # Signing for distribution needs the user's own certificate and profile.
    xcodebuild \
        -project iosApp/JerreaderUnified.xcodeproj \
        -scheme JerreaderUnified \
        -sdk iphoneos \
        -configuration Release \
        -derivedDataPath "$STAGE/DerivedData" \
        CODE_SIGNING_ALLOWED=NO \
        CODE_SIGNING_REQUIRED=NO \
        CODE_SIGN_IDENTITY="" \
        build

    APP="$STAGE/DerivedData/Build/Products/Release-iphoneos/Jerreader.app"
    if [ ! -d "$APP" ]; then
        echo "no .app was produced at $APP" >&2
        exit 1
    fi

    # An IPA is a zip with the bundle under Payload/.
    mkdir -p "$STAGE/Payload"
    cp -R "$APP" "$STAGE/Payload/"
    (cd "$STAGE" && zip -qry "Jerreader.ipa" Payload)
    cp "$STAGE/Jerreader.ipa" "$DEST/Jerreader-$VERSION_NAME-$VERSION_CODE.ipa"
    echo "    $DEST/Jerreader-$VERSION_NAME-$VERSION_CODE.ipa"
fi

echo "==> done"
