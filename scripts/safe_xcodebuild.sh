#!/bin/bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
readonly PROJECT_PATH="$REPO_ROOT/iosApp/JerreaderUnified.xcodeproj"
readonly PROJECT_FILE="$PROJECT_PATH/project.pbxproj"
readonly SCHEME="JerreaderUnified"
readonly BUILD_MODE="${1:-build}"
readonly CONFIGURATION="${JERREADER_CONFIGURATION:-Debug}"
readonly DERIVED_DATA_PATH="$REPO_ROOT/build/SafeDerivedData"
readonly LOCK_DIR="${TMPDIR:-/tmp}/jerreader-unified-safe-xcodebuild-${UID}.lock"
readonly MAX_LOAD_MULTIPLIER=2

fail() {
    printf 'safe_xcodebuild: %s\n' "$1" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Usage:
  scripts/safe_xcodebuild.sh build
  scripts/safe_xcodebuild.sh analyze
  JERREADER_ALLOW_SIMULATOR=1 \
  JERREADER_SIMULATOR_DESTINATION='platform=iOS Simulator,id=<UDID>' \
    scripts/safe_xcodebuild.sh test
  JERREADER_ALLOW_SIMULATOR=1 \
  JERREADER_SIMULATOR_DESTINATION='platform=iOS Simulator,id=<UDID>' \
    scripts/safe_xcodebuild.sh ui-test

Optional:
  JERREADER_CONFIGURATION=Debug|Release
  JERREADER_DRY_RUN=1
EOF
}

[[ "$#" -le 1 ]] || fail "additional xcodebuild arguments are not accepted"

case "$CONFIGURATION" in
    Debug|Release) ;;
    *) fail "JERREADER_CONFIGURATION must be Debug or Release" ;;
esac

case "$BUILD_MODE" in
    build|analyze|test|ui-test) ;;
    -h|--help|help)
        usage
        exit 0
        ;;
    *)
        usage >&2
        fail "unsupported mode: $BUILD_MODE"
        ;;
esac

[[ -f "$PROJECT_FILE" ]] || fail "expected project is missing: iosApp/JerreaderUnified.xcodeproj/project.pbxproj"

if [[ -n "${DEVELOPER_DIR:-}" ]]; then
    developer_dir="$DEVELOPER_DIR"
else
    developer_dir="$(xcode-select -p 2>/dev/null || true)"
fi

[[ -n "$developer_dir" ]] || fail "no Xcode Developer directory is configured"
[[ -x "$developer_dir/usr/bin/xcodebuild" ]] || fail "Developer directory does not contain xcodebuild"
[[ -d "$developer_dir/Platforms/iPhoneOS.platform" ]] || fail "Developer directory does not contain the iOS platform"
readonly XCODEBUILD_BIN="$developer_dir/usr/bin/xcodebuild"

if pgrep -x xcodebuild >/dev/null 2>&1; then
    fail "another xcodebuild process is already running"
fi

if pgrep -f 'GradleWrapperMain|org\.gradle\.launcher\.GradleMain' >/dev/null 2>&1; then
    fail "an Android/Gradle build is already running"
fi

logical_cpus="$(sysctl -n hw.logicalcpu 2>/dev/null || printf '1')"
one_minute_load="$(sysctl -n vm.loadavg 2>/dev/null | awk '{ print $2 }')"
if [[ "$logical_cpus" =~ ^[0-9]+$ ]] && [[ "$one_minute_load" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    load_limit=$((logical_cpus * MAX_LOAD_MULTIPLIER))
    if awk -v load="$one_minute_load" -v limit="$load_limit" 'BEGIN { exit !(load > limit) }'; then
        fail "system load is too high (${one_minute_load}; safety limit ${load_limit})"
    fi
fi

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    lock_pid=''
    if [[ -f "$LOCK_DIR/pid" ]]; then
        read -r lock_pid < "$LOCK_DIR/pid" || true
    fi

    if [[ "$lock_pid" =~ ^[0-9]+$ ]] && kill -0 "$lock_pid" 2>/dev/null; then
        fail "the build lock is held by process ${lock_pid}"
    fi

    rm -f "$LOCK_DIR/pid"
    rmdir "$LOCK_DIR" 2>/dev/null || fail "a stale build lock could not be cleared"
    mkdir "$LOCK_DIR" 2>/dev/null || fail "the build lock could not be acquired"
fi

printf '%s\n' "$$" > "$LOCK_DIR/pid"

cleanup() {
    rm -f "$LOCK_DIR/pid"
    rmdir "$LOCK_DIR" 2>/dev/null || true
}
trap cleanup EXIT HUP INT TERM

common_arguments=(
    -project "$PROJECT_PATH"
    -scheme "$SCHEME"
    -configuration "$CONFIGURATION"
    -derivedDataPath "$DERIVED_DATA_PATH"
    -jobs 2
    CODE_SIGNING_ALLOWED=NO
    CODE_SIGNING_REQUIRED=NO
    COMPILER_INDEX_STORE_ENABLE=NO
)

case "$BUILD_MODE" in
    build|analyze)
        command_arguments=(
            "$XCODEBUILD_BIN"
            "${common_arguments[@]}"
            -destination 'generic/platform=iOS'
            "$BUILD_MODE"
        )
        ;;
    test|ui-test)
        [[ "${JERREADER_ALLOW_SIMULATOR:-0}" == "1" ]] || fail "simulator use requires explicit approval"
        [[ -n "${JERREADER_SIMULATOR_DESTINATION:-}" ]] || fail "set one simulator destination using a UDID"
        [[ "$JERREADER_SIMULATOR_DESTINATION" == platform=iOS\ Simulator,id=* ]] || fail "simulator destination must use exactly one explicit UDID"

        test_filter='-skip-testing:JerreaderUITests'
        if [[ "$BUILD_MODE" == "ui-test" ]]; then
            test_filter='-only-testing:JerreaderUITests'
        fi

        command_arguments=(
            "$XCODEBUILD_BIN"
            "${common_arguments[@]}"
            -destination "$JERREADER_SIMULATOR_DESTINATION"
            -maximum-concurrent-test-simulator-destinations 1
            -parallel-testing-enabled NO
            "$test_filter"
            test
        )
        ;;
esac

printf 'safe_xcodebuild: repository validated: %s\n' "$REPO_ROOT"
printf 'safe_xcodebuild: mode=%s configuration=%s jobs=2\n' "$BUILD_MODE" "$CONFIGURATION"
printf 'safe_xcodebuild: command:'
printf ' %q' "${command_arguments[@]}"
printf '\n'

if [[ "${JERREADER_DRY_RUN:-0}" == "1" ]]; then
    printf 'safe_xcodebuild: dry run complete; no build was started\n'
    exit 0
fi

cd "$REPO_ROOT"
"${command_arguments[@]}"
