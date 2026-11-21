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

run_tests instrumentation-default.txt -e notClass com.lumora.camera.CameraEnduranceTest,com.lumora.camera.update.AndroidInstallerFlowTest

# The CI emulator is disposable and starts with the default unknown-sources app-op.
# Cancel the genuine system installer; never confirm installation or clear app data.
(
  trap 'adb shell appops set com.lumora.camera REQUEST_INSTALL_PACKAGES default' EXIT
  adb shell appops set com.lumora.camera REQUEST_INSTALL_PACKAGES allow
  run_tests instrumentation-realinstaller.txt -e class com.lumora.camera.update.AndroidInstallerFlowTest -e realInstaller true
  adb logcat -d -s RealInstallerTest:I > instrumentation-realinstaller-metrics.txt
  grep -q 'returned RESULT_CANCELED .*retired APK released' instrumentation-realinstaller-metrics.txt
)

api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$api" == 35 ]]; then
  trap 'adb shell wm size reset; adb shell wm density reset' EXIT
  # Real 360dp display bounds, not only a floating window inside a wider status bar.
  adb shell wm size 720x1920
  adb shell wm density 320
  run_tests instrumentation-narrow.txt -e class 'com.lumora.camera.CameraThemeTest#font200InNarrowActivityKeepsSettingsDeviceInfoAndEditorReachable'
  adb shell wm size reset
  adb shell wm density reset
  run_tests instrumentation-endurance.txt -e class com.lumora.camera.CameraEnduranceTest -e endurance true -e previewSeconds 60
  adb logcat -d -s LumoraEndurance:I > instrumentation-endurance-metrics.txt
  grep -q 'PASS completed=100 retainedJpegs=200' instrumentation-endurance-metrics.txt
  adb shell wm size 1600x2560
  adb shell wm density 280
  run_tests instrumentation-tablet.txt -e class com.lumora.camera.CameraSafeAreaTest
fi
