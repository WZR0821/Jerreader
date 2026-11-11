#!/bin/bash
set -euo pipefail
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
if [[ "${1:-}" == "test" && "$#" == 1 ]]; then
  exec /usr/bin/python3 "$SCRIPT_DIR/safe_ios_validation.py" android-test
fi
exec /usr/bin/python3 "$SCRIPT_DIR/safe_ios_validation.py" android "$@"
