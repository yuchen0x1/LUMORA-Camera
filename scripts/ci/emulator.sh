#!/usr/bin/env bash
set -euo pipefail

adb install -r apks/debug/app-debug.apk
adb install -r apks/androidTest/debug/app-debug-androidTest.apk

collect_failure() {
  local prefix="${1%.txt}"
  printf '%s' "$2" > "${prefix}-exit-code.txt"
  timeout --kill-after=5s 30s adb logcat -b all -d -v threadtime > "${prefix}-logcat.txt" 2>&1 || true
  timeout --kill-after=5s 30s adb shell dumpsys activity exit-info com.lumora.camera > "${prefix}-exit-info.txt" 2>&1 || true
  timeout --kill-after=5s 30s adb shell dumpsys activity lastanr > "${prefix}-anr.txt" 2>&1 || true
  timeout --kill-after=5s 30s adb shell dumpsys meminfo com.lumora.camera > "${prefix}-meminfo.txt" 2>&1 || true
  timeout --kill-after=5s 30s adb shell dumpsys window windows > "${prefix}-window.txt" 2>&1 || true
}

run_tests() {
  local report="$1"
  local status=0
  shift
  # A stuck instrumentation process must fail with diagnostics before the job's 60m limit.
  timeout --kill-after=30s 25m adb shell am instrument -w -r "$@" com.lumora.camera.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$report" || status=$?
  if (( status != 0 )); then
    collect_failure "$report" "$status"
    return "$status"
  fi
  # Require a positive JUnit summary and exactly one normal terminal as the last output.
  # adb itself may succeed even when AndroidJUnitRunner aborts or its output is truncated.
  if ! awk '
      /^OK \([1-9][0-9]* tests?\)$/ { ok = 1 }
      /^INSTRUMENTATION_CODE:/ { terminals++; normal = ($0 == "INSTRUMENTATION_CODE: -1") }
      NF { last = $0 }
      END { exit !(ok && terminals == 1 && normal && last == "INSTRUMENTATION_CODE: -1") }
    ' "$report"; then
    collect_failure "$report" 1
    return 1
  fi
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
