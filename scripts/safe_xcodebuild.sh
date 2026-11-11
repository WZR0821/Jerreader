#!/bin/bash
set -euo pipefail
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# Keep a single public entry point; the supervisor also covers host Swift tests.
exec /usr/bin/python3 "$SCRIPT_DIR/safe_ios_validation.py" "$@"
