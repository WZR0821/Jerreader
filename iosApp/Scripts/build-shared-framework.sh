#!/bin/sh
#
# Builds the shared Kotlin framework for whatever slice Xcode is currently
# building, and stages it where FRAMEWORK_SEARCH_PATHS expects it.
#
# The staging step exists because Gradle's output directory is named after the
# Kotlin target (`iosX64`, `iosSimulatorArm64`, …) while Xcode's search path can
# only be written in terms of its own variables. Copying into
# `$CONFIGURATION/$SDK_NAME` lets one static search path serve every slice.
#
# `:core` is what gets linked, not `:ui`. Compose Multiplatform publishes no
# Intel-simulator artifacts, so a Compose-bearing framework cannot be built on
# an Intel Mac at all — while everything the reader *decides* lives in `:core`,
# which has no such limit. See docs/BUILD.md.
set -eu

PROJECT_ROOT="$SRCROOT/.."
cd "$PROJECT_ROOT"

# Xcode's build environment does not inherit a login shell, so the JDK this
# repository builds with has to be named explicitly.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
    JAVA_HOME="$PROJECT_ROOT/.tools/jdk17/Contents/Home"
    export JAVA_HOME
fi

case "${PLATFORM_NAME:-iphonesimulator}" in
    iphonesimulator)
        case "${ARCHS:-x86_64}" in
            *arm64*) KOTLIN_TARGET="IosSimulatorArm64"; GRADLE_DIR="iosSimulatorArm64" ;;
            *)       KOTLIN_TARGET="IosX64";            GRADLE_DIR="iosX64" ;;
        esac
        ;;
    *)
        KOTLIN_TARGET="IosArm64"; GRADLE_DIR="iosArm64"
        ;;
esac

case "${CONFIGURATION:-Debug}" in
    Debug) KOTLIN_BUILD="Debug"; GRADLE_BUILD="debug" ;;
    *)     KOTLIN_BUILD="Release"; GRADLE_BUILD="release" ;;
esac

./gradlew --quiet ":core:link${KOTLIN_BUILD}Framework${KOTLIN_TARGET}"

BUILT="core/build/bin/${GRADLE_DIR}/${GRADLE_BUILD}Framework/JerreaderCore.framework"
if [ ! -d "$BUILT" ]; then
    echo "error: expected framework at $BUILT" >&2
    exit 1
fi

STAGE="core/build/xcode-frameworks/${CONFIGURATION}/${SDK_NAME}"
mkdir -p "$STAGE"
rm -rf "$STAGE/JerreaderCore.framework"
cp -R "$BUILT" "$STAGE/"
