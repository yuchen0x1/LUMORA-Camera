#!/usr/bin/env bash
set -euo pipefail

adb install -r apks/debug/app-debug.apk
adb install -r apks/androidTest/debug/app-debug-androidTest.apk

run_tests() {
  local report="$1"
  shift
  adb shell am instrument -w "$@" com.lumora.camera.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$report"
  # adb itself may succeed even when AndroidJUnitRunner aborts.
  grep -Eq '^OK \([1-9][0-9]* tests?\)$' "$report"
}

run_tests instrumentation-default.txt -e notClass com.lumora.camera.CameraEnduranceTest

api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$api" == 35 ]]; then
  trap 'adb shell wm size reset; adb shell wm density reset' EXIT
  adb shell wm size 1600x2560
  adb shell wm density 280
  run_tests instrumentation-tablet.txt -e class com.lumora.camera.CameraSafeAreaTest
fi
