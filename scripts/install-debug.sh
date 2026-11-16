#!/usr/bin/env bash
# Builds the debug app with the next debug build number (shown on the About page, e.g. 0.5.3-debug.12) and installs it on
# a phone over adb. Usage: scripts/install-debug.sh [adb serial]   (the first connected device when none is given)
set -euo pipefail
counter="$HOME/.chapterplayer-debug-build"
n=$(( $(cat "$counter" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$counter"
./gradlew :androidApp:assembleDebug -q
serial="${1:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
MSYS_NO_PATHCONV=1 adb -s "$serial" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
echo "Installed debug build $n on $serial"
