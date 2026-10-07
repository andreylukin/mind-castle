# Mind Castle

Your Mac's windows as live panels around you in a Samsung Galaxy XR. A small Mac command-line app (`castle-streamer`) captures individual windows with ScreenCaptureKit, encodes them as low-latency H.264, and streams them over USB to the headset app (`dev.mindcastle`). In control mode the Mac's trackpad drives a cursor across the panels, the laptop screen goes black, and the keyboard keeps typing into whichever window you clicked.

- `mac/`: the streamer (Swift), `setup.sh` helpers and the `castle` launcher
- `headset/`: the Android XR app (Kotlin, Jetpack XR)
- `lab/`: emulator test harness
- `PROTOCOL.md`: the wire protocol

## Requirements

- A Mac on macOS 14 or later (Apple silicon or Intel), with Xcode Command Line Tools. No Homebrew, no admin rights needed.
- A Galaxy XR with developer options and USB debugging on, and a USB-C cable.
- The headset app installed on the headset once (see [Headset app](#headset-app)).
- Optional: an OpenAI API key for hold-space voice commands.

## Install

```sh
git clone https://github.com/andreylukin/mind-castle mind-castle && cd mind-castle && ./setup.sh
```

`setup.sh` is safe to re-run any time (for example after `git pull`). It:

1. checks for the Xcode Command Line Tools (if missing, run `xcode-select --install`);
2. builds the streamer (`mac/build.sh`);
3. finds `adb`, or downloads Google's Android platform-tools into `~/.mind-castle/platform-tools`;
4. installs the `castle` launcher into `~/.mind-castle/bin` and prints the line to add it to your PATH;
5. checks Screen Recording, Accessibility and Microphone for your terminal app and opens the right System Settings pane for anything missing;
6. offers to store your OpenAI key in the login Keychain (typed at a hidden prompt).

`./setup.sh --check` only reports and changes nothing. `--no-prompt` skips the questions and the Settings panes.

## Daily use

1. Plug the headset into the Mac and put it on.
2. In the terminal you granted permissions to, run `castle`.

`castle` waits for the headset, sets up the USB port forward (`adb reverse tcp:7420 tcp:7420`), launches the headset app, and runs the streamer in the foreground. Unplugging and replugging is handled. Ctrl-C quits and cleans up. Extra arguments go to the streamer, e.g. `castle --virtual-display`.

In the headset, pick windows from the picker. They stream live as panels you can move, resize and push back.

### Hotkeys (on the Mac keyboard)

| keys | action |
|---|---|
| ⌃⌥⌘M | control mode on/off: the trackpad drives the headset cursor, the laptop screen goes black, typing goes to the window you clicked |
| ⌃⌥⌘ ← → | move the active panel along the arc |
| ⌃⌥⌘ ↑ ↓ | move the active panel up / down |
| ⌃⌥⌘ = / - | bring the active panel closer / push it farther |
| ⌃⌥⌘ ] / [ | grow / shrink the active panel |
| ⌃⌥⌘C | put the active panel straight ahead |
| ⌃⌥⌘ 1 / 2 / 3 | size preset: editor / side / glance |
| ⌃⌥⌘T | tidy the panels |
| ⌃⌥⌘Z / ⇧Z | undo / redo the last layout change |
| ⌃⌥⌘R | re-center everything on where you're looking |
| ⌃⌥⌘P | show the room (passthrough) on/off |
| ⌃⌥⌘ . / , | darker / lighter surroundings |
| ⌃⌥⌘⇧ ← → ↑ ↓ | resize the laptop cutout |

The *active panel* is the one the cursor is in, else the one you last clicked, else the one you're looking at. Without a headset connected, the panel hotkeys pass through to your apps (⌃⌥⌘M is always taken).

**Follows your Mac:** switching windows on the Mac (Raycast window hotkeys, ⌘-Tab, a click) makes that window the active panel and moves the cursor onto it. Launchers (Raycast, Spotlight, Alfred) pop up as a floating panel in front of you while they're open. To add other launcher apps, list them in `~/.config/mind-castle/overlays.json`, e.g. `{"apps": ["Raycast", "Spotlight", "Alfred"]}`.

**Voice:** in control mode, hold Space and speak (e.g. "put Slack on the left", "show the terminal"), then release. A quick tap still types a space. This needs Microphone permission and an OpenAI key.

**Bail-out:** press ⌃⌥⌘M again, unplug the headset (control mode turns off automatically), or press Ctrl-C in the `castle` terminal. The screen and mouse are always restored.

## Headset app

Build and install it once from a Mac with the Android SDK: `cd headset && ./gradlew installDebug`. To install it from another Mac, copy the APK (`headset/app/build/outputs/apk/debug/app-debug.apk`) to `~/.mind-castle/mind-castle.apk` there. `castle` installs it when the headset doesn't have it.

## Troubleshooting

- **Black or frozen panels, or "user declined TCCs" in the log:** Screen Recording isn't granted to the terminal you run `castle` from. Turn it on in System Settings > Privacy & Security > Screen & System Audio Recording, quit and reopen that terminal, and run `./setup.sh --check`. Always start `castle` from that same terminal app; macOS grants permissions per app.
- **Clicks, hotkeys or control mode do nothing:** Accessibility is missing for your terminal (Privacy & Security > Accessibility).
- **Managed (MDM) work Mac:** the toggles may be greyed out, or reset by policy, if you aren't an admin. `setup.sh` says so when it detects MDM enrollment. Ask IT to allow your terminal app for Screen Recording (a PPPC profile with "Allow Standard User to Set System Service") and Accessibility. Voice also needs Microphone; everything else works without it.
- **"headset found but not authorized":** the first time you connect the headset to a new Mac, put it on and accept "Allow USB debugging" (tick "Always allow from this computer").
- **"waiting for the Galaxy XR" forever:** check the cable (data, not charge-only), that USB debugging is on, and `adb devices`. With several devices attached, pick one with `ANDROID_SERIAL=<serial> castle`.
- **Port 7420 in use:** another `castle`/streamer is already running on this Mac. Quit it, or run `CASTLE_PORT=7421 castle` (the headset still connects to its port 7420; `castle` forwards it to 7421).
- **Voice says "no OpenAI key":** run `security add-generic-password -U -s mind-castle-openai -a openai -w`.
