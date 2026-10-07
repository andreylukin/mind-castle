#!/bin/bash
# Mind Castle Mac setup. Idempotent; no sudo, no Homebrew. Safe to re-run any time (e.g. after `git pull`).
#   ./setup.sh               build, fetch adb if needed, install `castle`, walk through permissions and the voice key
#   ./setup.sh --check       report only: change nothing, open nothing, prompt for nothing
#   ./setup.sh --no-prompt   do everything that needs no answers (no Settings panes, no key prompt)
#   ./setup.sh --skip-build / --download-adb (fetch Google's platform-tools even if adb is on PATH)
# State lives in $MIND_CASTLE_HOME (default ~/.mind-castle): bin/castle, platform-tools/, id.
set -u
REPO=$(cd "$(dirname "$0")" && pwd)
MC_HOME=${MIND_CASTLE_HOME:-$HOME/.mind-castle}
BIN=$REPO/mac/.build/release/castle-streamer-b
PT_URL=https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
CHECK=0 PROMPT=1 BUILD=1 FORCE_ADB=0
for a in "$@"; do
    case "$a" in
        --check) CHECK=1 PROMPT=0 BUILD=0 ;;
        --no-prompt) PROMPT=0 ;;
        --skip-build) BUILD=0 ;;
        --download-adb) FORCE_ADB=1 ;;
        -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
        *) echo "unknown option $a (see --help)"; exit 2 ;;
    esac
done

problems=0
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok() { printf '  \033[32mok\033[0m  %s\n' "$*"; }
info() { printf '      %s\n' "$*"; }
bad() { printf '  \033[31m!!\033[0m  %s\n' "$*"; problems=$((problems + 1)); }
ask() { [ $PROMPT = 1 ] || return 1; printf '      %s [y/N] ' "$1"; read -r r; [ "$r" = y ] || [ "$r" = Y ]; }
settings() { [ $PROMPT = 1 ] && open "x-apple.systempreferences:com.apple.preference.security?$1" 2>/dev/null; }

# --- 1. Xcode Command Line Tools ------------------------------------------------------------------------------
step "Xcode Command Line Tools"
if xcode-select -p >/dev/null 2>&1 && xcrun --find swift >/dev/null 2>&1; then
    ok "$(xcode-select -p), $(xcrun swift --version 2>/dev/null | head -1)"
else
    bad "missing. Install them (no admin needed on most Macs), then re-run ./setup.sh:"
    info "xcode-select --install"
    [ $CHECK = 1 ] || exit 1
fi

# --- 2. Build the streamer -------------------------------------------------------------------------------------
step "castle-streamer"
if [ $BUILD = 1 ]; then
    log=$MC_HOME/build.log
    mkdir -p "$MC_HOME"
    info "building (first build takes a minute)..."
    if "$REPO/mac/build.sh" >"$log" 2>&1; then
        ok "built $(cat "$REPO/mac/.build/release/castle-version" 2>/dev/null) -> mac/.build/release/castle-streamer-b"
    else
        bad "build failed; last lines of $log:"
        tail -15 "$log" | sed 's/^/      /'
        exit 1
    fi
elif [ -x "$BIN" ]; then
    ok "present ($(cat "$REPO/mac/.build/release/castle-version" 2>/dev/null || echo "unknown version"))"
else
    bad "not built yet (run ./setup.sh without --check/--skip-build)"
fi

# --- 3. adb (Android platform-tools) ---------------------------------------------------------------------------
step "adb"
ADB=$(command -v adb 2>/dev/null || true)
[ $FORCE_ADB = 1 ] && ADB=""
[ -z "$ADB" ] && [ -x "$MC_HOME/platform-tools/adb" ] && ADB=$MC_HOME/platform-tools/adb
[ -z "$ADB" ] && [ $FORCE_ADB = 0 ] && [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ] && ADB=$HOME/Library/Android/sdk/platform-tools/adb
if [ -n "$ADB" ]; then
    ok "$ADB ($("$ADB" version 2>/dev/null | head -1))"
elif [ $CHECK = 1 ]; then
    bad "not found (setup will download Google's platform-tools into $MC_HOME/platform-tools)"
else
    info "downloading Google's platform-tools from $PT_URL"
    tmp=$(mktemp -d)
    if curl -fsSL --retry 2 -o "$tmp/pt.zip" "$PT_URL"; then
        size=$(stat -f %z "$tmp/pt.zip")
        if [ "$size" -lt 5000000 ]; then
            bad "download too small ($size bytes), not a platform-tools zip"
        elif ! unzip -tq "$tmp/pt.zip" >/dev/null 2>&1; then
            bad "download is not a valid zip"
        else
            mkdir -p "$MC_HOME"
            rm -rf "$MC_HOME/platform-tools"
            unzip -q "$tmp/pt.zip" -d "$MC_HOME"
            ADB=$MC_HOME/platform-tools/adb
            if "$ADB" version >/dev/null 2>&1; then
                sig=$(codesign -dv "$ADB" 2>&1 | sed -n 's/^Authority=//p' | head -1)
                ok "$ADB ($("$ADB" version | head -1); $((size / 1048576)) MB zip, verified; codesign: ${sig:-no Developer ID})"
            else
                bad "adb from the download doesn't run"
            fi
        fi
    else
        bad "download failed (offline or blocked?). Alternative: install Android platform-tools any way you like so adb is on PATH."
    fi
    rm -rf "$tmp"
fi

# --- 4. castle launcher ----------------------------------------------------------------------------------------
step "castle launcher"
LAUNCHER=$MC_HOME/bin/castle
want="#!/bin/bash
# Mind Castle launcher (installed by $REPO/setup.sh)
exec \"$REPO/mac/castle.sh\" \"\$@\""
if [ $CHECK = 1 ]; then
    [ -x "$LAUNCHER" ] && ok "$LAUNCHER" || bad "not installed ($LAUNCHER)"
else
    mkdir -p "$MC_HOME/bin"
    if [ "$(cat "$LAUNCHER" 2>/dev/null)" != "$want" ]; then printf '%s\n' "$want" >"$LAUNCHER"; fi
    chmod +x "$LAUNCHER" "$REPO/mac/castle.sh"
    ok "$LAUNCHER"
fi
case ":$PATH:" in
    *":$MC_HOME/bin:"*) ok "on PATH" ;;
    *) info "not on PATH yet. To add it (zsh), run once and open a new terminal:"
       info "echo 'export PATH=\"$MC_HOME/bin:\$PATH\"' >> ~/.zshrc"
       info "(or run it directly: $LAUNCHER)" ;;
esac

# --- 5. Permissions --------------------------------------------------------------------------------------------
step "permissions (granted to your terminal app)"
term=${TERM_PROGRAM:-your terminal}
case "$term" in Apple_Terminal) term=Terminal ;; iTerm.app) term=iTerm ;; vscode) term="VS Code" ;; esac
info "macOS attributes these to the app you run castle from: $term. Run castle from the same app."
managed=0
profiles status -type enrollment 2>/dev/null | grep -q "MDM enrollment: Yes" && managed=1
admin=0
dseditgroup -o checkmember -m "$USER" admin >/dev/null 2>&1 && admin=1
mdm_tcc=""
f="/Library/Application Support/com.apple.TCC/MDMOverrides.plist"
[ -r "$f" ] && mdm_tcc=$(plutil -p "$f" 2>/dev/null | grep -oE '"(ScreenCapture|Accessibility|Microphone|PostEvent|ListenEvent)"' | sort -u | tr -d '"' | tr '\n' ' ')
[ $managed = 1 ] && info "this Mac is MDM-managed$([ $admin = 1 ] && echo "" || echo " and you are not an admin")."
[ -n "$mdm_tcc" ] && info "MDM privacy rules exist for: $mdm_tcc(they can pre-allow or block these)"

if [ ! -x "$BIN" ]; then
    bad "can't check permissions until the streamer is built"
else
    flag=--check-permissions
    [ $PROMPT = 1 ] && flag=--request-permissions # registers the terminal in each list and shows prompts
    report=$("$BIN" $flag 2>/dev/null)
    val() { printf '%s\n' "$report" | sed -n "s/^$1=//p"; }
    blocked_hint() {
        if [ $managed = 1 ] && [ $admin = 0 ]; then
            info "On a managed Mac without admin rights, the toggle may be greyed out or reset by MDM. If so, ask IT"
            info "to allow \"$term\" for $1 (a PPPC profile; Screen Recording needs \"Allow Standard User to Set System Service\")."
        fi
    }
    sc=$(val screen_capture)
    if [ "$sc" = ok ]; then ok "Screen Recording"
    else
        bad "Screen Recording: ${sc:-unknown}"
        info "System Settings > Privacy & Security > Screen & System Audio Recording: turn on \"$term\", then quit and reopen $term."
        blocked_hint "Screen Recording"; settings Privacy_ScreenCapture
    fi
    if [ "$(val accessibility)" = granted ]; then ok "Accessibility (clicks, hotkeys, control mode)"
    else
        bad "Accessibility: missing"
        info "System Settings > Privacy & Security > Accessibility: turn on \"$term\" (add it with + if it's not listed), then reopen $term."
        blocked_hint "Accessibility"; settings Privacy_Accessibility
    fi
    case "$(val microphone)" in
        granted) ok "Microphone (hold-space voice)" ;;
        restricted) info "Microphone: restricted by policy (voice commands unavailable; everything else works)" ;;
        undetermined) info "Microphone: not asked yet (macOS asks the first time you hold space in control mode)" ;;
        *) info "Microphone: denied (only needed for voice). System Settings > Privacy & Security > Microphone: turn on \"$term\"."
           settings Privacy_Microphone ;;
    esac
fi

# --- 6. OpenAI key (voice, optional) ---------------------------------------------------------------------------
step "OpenAI API key (optional, for hold-space voice commands)"
if security find-generic-password -s mind-castle-openai -a openai >/dev/null 2>&1; then
    ok "in your login Keychain (service mind-castle-openai)"
elif [ -n "${OPENAI_API_KEY:-}" ]; then
    ok "from \$OPENAI_API_KEY (the Keychain is preferred)"
else
    info "not set. Voice commands need it; everything else works without."
    if ask "Add it to your Keychain now? (typed at a hidden prompt, never shown)"; then
        security add-generic-password -U -s mind-castle-openai -a openai -w && ok "saved"
    else
        info "later: security add-generic-password -U -s mind-castle-openai -a openai -w"
    fi
fi

# --- Summary ---------------------------------------------------------------------------------------------------
step "summary"
if [ $problems = 0 ]; then
    ok "ready. Plug in the Galaxy XR and run: castle"
else
    info "$problems item(s) above need attention; re-run ./setup.sh after fixing them."
fi
[ $problems = 0 ]
