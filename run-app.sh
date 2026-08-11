#!/usr/bin/env bash
# Build, install, and open the debug app on a connected Android device.
# Usage: ./run-app.sh [device-serial]

set -euo pipefail

APP_ID="com.example.remotepc"
ACTIVITY="${APP_ID}/.MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if ! command -v adb >/dev/null 2>&1; then
  echo "Error: adb was not found. Install Android SDK Platform-Tools and add it to PATH." >&2
  exit 1
fi

if [[ ! -x ./gradlew ]]; then
  echo "Error: ./gradlew is not executable." >&2
  exit 1
fi

serial="${1:-}"
if [[ -z "$serial" ]]; then
  mapfile -t devices < <(adb devices | awk '$2 == "device" { print $1 }')

  case "${#devices[@]}" in
    0)
      echo "Error: no Android device or emulator is connected." >&2
      exit 1
      ;;
    1)
      serial="${devices[0]}"
      ;;
    *)
      echo "Error: more than one device is connected. Pass a serial:" >&2
      printf '  %s\n' "${devices[@]}" >&2
      echo "Example: $0 <device-serial>" >&2
      exit 1
      ;;
  esac
fi

if ! adb -s "$serial" get-state >/dev/null 2>&1; then
  echo "Error: device '$serial' is not available." >&2
  exit 1
fi

echo "Building debug APK..."
./gradlew --no-daemon :app:assembleDebug

echo "Installing on $serial..."
adb -s "$serial" install -r "$APK_PATH"

echo "Launching $APP_ID..."
adb -s "$serial" shell am force-stop "$APP_ID"
adb -s "$serial" shell am start -W -n "$ACTIVITY"

echo "Done. The app is running on $serial."
