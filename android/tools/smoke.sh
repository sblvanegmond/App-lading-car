#!/usr/bin/env bash
# Installs the APK on a running emulator, opens the screens, photographs
# them and fails when the app crashes. Used by .github/workflows/android.yml.
#
#   bash android/tools/smoke.sh Laadmoment.apk screenshots
set -uo pipefail

APK="$1"
OUT="$2"
PKG=nl.laadmoment.app
mkdir -p "$OUT"

shot() {
  sleep "${2:-2}"
  adb exec-out screencap -p > "$OUT/$1.png"
}

ui_contains() {
  timeout 20 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "$1"
}

start_app() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" "$@" >/dev/null
}

adb install -r "$APK" || exit 1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb logcat -c

start_app
# Wait for real prices and weather to arrive (the emulator has internet).
for _ in $(seq 1 40); do
  sleep 2
  if ui_contains "Komende uren"; then break; fi
done
shot 1-hoofdscherm 3
adb shell cat /sdcard/ui.xml > "$OUT/hoofdscherm.xml" 2>/dev/null || true

adb shell input swipe 540 1900 540 700 500
shot 2-midden
adb shell input swipe 540 1900 540 500 500
shot 3-uren
adb shell input swipe 540 1900 540 500 500
adb shell input swipe 540 1900 540 500 500
shot 4-zon-per-dag

start_app --es screen settings
shot 5-instellingen 4

adb shell cmd uimode night yes
start_app
shot 6-donker 6
adb shell cmd uimode night no

adb logcat -d > "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  grep -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt"
  echo "::error::De app is gecrasht op de emulator"
  exit 1
fi
if ! adb shell pidof "$PKG" >/dev/null; then
  echo "::error::De app draait niet meer"
  exit 1
fi
grep -E "WM-|Laadmoment" "$OUT/logcat.txt" | tail -40 || true
echo "Geen crash gezien."
