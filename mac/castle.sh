#!/bin/bash
# castle: connect the Galaxy XR over USB and run the Mac streamer in this terminal. Ctrl-C quits and cleans up.
# Installed by setup.sh as ~/.mind-castle/bin/castle (a wrapper that runs this file, so `git pull` updates it).
#   castle [streamer args...]       e.g. castle --virtual-display
# Env: CASTLE_PORT (Mac port, 7420; the headset always connects to its own 7420), ANDROID_SERIAL (pick a device), MIND_CASTLE_HOME (~/.mind-castle),
#      CASTLE_ALLOW_EMULATOR=1 (also accept emulator-* devices).
set -u
REPO=$(cd "$(dirname "$0")/.." && pwd)
MC_HOME=${MIND_CASTLE_HOME:-$HOME/.mind-castle}
PORT=${CASTLE_PORT:-7420}
DEVICE_PORT=7420 # what the headset app connects to
PKG=dev.mindcastle
APK=$MC_HOME/mind-castle.apk
BIN=$REPO/mac/.build/release/castle-streamer-b

say() { printf 'castle: %s\n' "$*"; }

[ -x "$BIN" ] || { say "streamer not built - run $REPO/setup.sh"; exit 1; }
ADB=$(command -v adb 2>/dev/null || true)
[ -z "$ADB" ] && [ -x "$MC_HOME/platform-tools/adb" ] && ADB=$MC_HOME/platform-tools/adb
[ -z "$ADB" ] && [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ] && ADB=$HOME/Library/Android/sdk/platform-tools/adb # Android Studio
[ -n "$ADB" ] || { say "adb not found - run $REPO/setup.sh"; exit 1; }

# Prints the serial of the headset to use ("" if none ready); notes an unauthorized device on stderr once.
pick_device() {
    "$ADB" devices 2>/dev/null | awk -v want="${ANDROID_SERIAL:-}" -v emu="${CASTLE_ALLOW_EMULATOR:-0}" '
        NR > 1 && NF >= 2 {
            if (want != "" && $1 != want) next
            if (emu != "1" && $1 ~ /^emulator-/) next
            if ($2 == "device") { print $1; exit }
            if ($2 == "unauthorized") unauth = 1
        }
        END { if (unauth) print "UNAUTHORIZED" }'
}

# Sets up one headset: port reverse, app install check, launch.
connect() {
    local s=$1 model
    model=$("$ADB" -s "$s" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    "$ADB" -s "$s" reverse "tcp:$DEVICE_PORT" "tcp:$PORT" >/dev/null || { say "adb reverse failed on $s"; return 1; }
    say "headset $s (${model:-unknown model}): headset port $DEVICE_PORT -> Mac port $PORT"
    if ! "$ADB" -s "$s" shell pm path "$PKG" 2>/dev/null | grep -q '^package:'; then
        if [ -f "$APK" ]; then
            say "installing the headset app from $APK"
            "$ADB" -s "$s" install -r "$APK" >/dev/null || { say "install failed"; return 1; }
        else
            say "the Mind Castle app is not installed on this headset. Install it once from the Mac that builds it"
            say "(headset/: ./gradlew installDebug), or put an APK at $APK and re-run castle."
            return 0
        fi
    fi
    "$ADB" -s "$s" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 && say "launched $PKG on the headset"
}

# Wait for a headset.
said_wait=0 said_auth=0
while :; do
    dev=$(pick_device)
    case "$dev" in
        "") [ $said_wait = 0 ] && say "waiting for the Galaxy XR on USB (plug it in, USB debugging on)..." && said_wait=1 ;;
        UNAUTHORIZED) [ $said_auth = 0 ] && say "headset found but not authorized: put it on and accept \"Allow USB debugging\" for this Mac" && said_auth=1 ;;
        *) break ;;
    esac
    sleep 1
done
connect "$dev"

# Replug watcher: re-run the reverse (and relaunch) whenever a headset (re)appears without it.
(
    last=$dev
    while sleep 2; do
        cur=$(pick_device)
        case "$cur" in ""|UNAUTHORIZED) last=""; continue ;; esac
        if [ "$cur" != "$last" ] || ! "$ADB" -s "$cur" reverse --list 2>/dev/null | grep -q "tcp:$DEVICE_PORT tcp:$PORT"; then
            say "headset (re)connected"
            connect "$cur"
        fi
        last=$cur
    done
) &
WATCH=$!

STREAMER=""
cleanup() {
    trap - INT TERM EXIT
    kill "$WATCH" 2>/dev/null
    [ -n "$STREAMER" ] && kill -TERM "$STREAMER" 2>/dev/null && wait "$STREAMER" 2>/dev/null
    d=$(pick_device)
    case "$d" in ""|UNAUTHORIZED) ;; *) "$ADB" -s "$d" reverse --remove "tcp:$DEVICE_PORT" >/dev/null 2>&1 && say "removed port reverse" ;; esac
}
trap 'cleanup; exit 130' INT TERM
trap cleanup EXIT

# The streamer runs as this shell's child in the terminal's process group, so macOS attributes Screen Recording,
# Accessibility and Microphone to this terminal app. (`wait` keeps Ctrl-C responsive; cleanup stops it.)
say "starting the streamer on port $PORT (Ctrl-C to quit)"
"$BIN" --port "$PORT" "$@" &
STREAMER=$!
wait "$STREAMER"
status=$?
STREAMER=""
exit $status
