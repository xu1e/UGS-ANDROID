#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug "$@"
printf '\nAPK: app/build/outputs/apk/debug/app-debug.apk\n'
