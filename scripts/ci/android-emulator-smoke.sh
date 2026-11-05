#!/usr/bin/env bash
# Disposable-emulator smoke test. It does not touch any real device or signing key.
set -Eeuo pipefail

apk=${1:?Usage: android-emulator-smoke.sh APP.apk API_LEVEL}
api_level=${2:?Missing API level}
package=io.github.tigaotg.openkinguard.debug.pilotv4
component="$package/io.github.tigaotg.openkinguard.MainActivity"
logs=emulator-logs
mkdir -p "$logs"

assert_app_alive() {
  local attempt
  for attempt in {1..15}; do
    if adb shell pidof "$package" | tr -d '\r' | grep -Eq '^[0-9]+'; then
      printf 'PASS: %s: application process is running\n' "$1" | tee -a "$logs/results.txt"
      return 0
    fi
    sleep 2
  done
  printf 'FAIL: %s: application process is not running\n' "$1" | tee -a "$logs/results.txt"
  return 1
}

start_app() {
  local scenario=$1
  adb shell am start -W -n "$component" | tee -a "$logs/activity-start.txt"
  assert_app_alive "$scenario"
}

printf 'Android emulator API: %s\n' "$api_level" | tee "$logs/results.txt"
adb install -r "$apk" | tee "$logs/install.txt"

# 1. Cold launch; the startup permission and listener checks must not crash.
start_app 'cold launch'

# 2. Foreground re-entry should perform another safe check.
adb shell input keyevent KEYCODE_HOME
sleep 2
start_app 'return from background'

# 3. Android app-settings round trip triggers a normal foreground resume.
adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$package" \
  | tee -a "$logs/activity-start.txt"
sleep 2
adb shell input keyevent KEYCODE_BACK
start_app 'return from system settings'

# 4. Runtime notification denial is applicable on Android 13+ only.
if (( api_level >= 33 )); then
  adb shell pm revoke "$package" android.permission.POST_NOTIFICATIONS || true
  adb shell am force-stop "$package"
  start_app 'launch with notifications denied'
fi

# 5. Restart the app process; do not delete app data.
adb shell am force-stop "$package"
start_app 'force-stop followed by explicit user launch'

# Check failures belonging to OpenKinGuard, not unrelated emulator apps.
adb logcat -d -v threadtime -s AndroidRuntime:E > "$logs/runtime-errors.txt" || true
if grep -Fq "Process: $package," "$logs/runtime-errors.txt"; then
  printf 'FAIL: app process reported an AndroidRuntime crash\n' | tee -a "$logs/results.txt"
  exit 1
fi

printf 'PASS: all basic emulator startup checks completed\n' | tee -a "$logs/results.txt"
