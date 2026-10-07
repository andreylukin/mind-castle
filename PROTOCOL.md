# Mind Castle wire protocol (v0)

One TCP connection. The Mac listens on `127.0.0.1:7420`; the headset connects to
`127.0.0.1:7420` through `adb reverse tcp:7420 tcp:7420` (USB). Port 7000 is avoided because macOS AirPlay Receiver owns it.

Every message: `u8 type | u32 windowId (big-endian) | u32 len (big-endian) | payload[len]`.
`windowId` is the macOS CGWindowID; 0 when not window-specific.

## Mac -> headset

| type | name | payload |
|---|---|---|
| 1 | WINDOW_LIST | UTF-8 JSON `[{"id":123,"app":"Google Chrome","title":"...","w":1512,"h":945}]` (w/h in pixels) |
| 2 | CODEC_CONFIG | H.264 Annex-B SPS+PPS (`00 00 00 01` start codes). Sent before the first frame and whenever the stream restarts or resizes |
| 3 | FRAME | `u64 ptsMicros (BE)` + one H.264 Annex-B access unit (non-key) |
| 4 | KEYFRAME | same as FRAME, IDR access unit |
| 5 | WINDOW_GONE | empty |

## Headset -> Mac

| type | name | payload |
|---|---|---|
| 10 | SUBSCRIBE | JSON `{"ids":[123,456]}` — full set of windows to stream; others stop |
| 11 | FOCUS | empty — raise window `windowId` and activate its app so the Mac keyboard types into it |
| 12 | CLICK | JSON `{"x":0.5,"y":0.25}` — normalized to the window's content (0..1, top-left origin) |
| 13 | SCROLL | JSON `{"x":0.5,"y":0.25,"dx":0,"dy":-3}` — position normalized, deltas in lines |
| 14 | REQUEST_KEYFRAME | empty |

On connect the Mac sends WINDOW_LIST immediately and then every ~2 s (also a heartbeat: the headset reconnects after 6 s of silence).

## v1 additions: tracing and control mode

All Mac timestamps are **Mac host-clock microseconds** (`mach_absolute_time` → µs, the same clock as
ScreenCaptureKit / `CMClockGetHostTimeClock`). FRAME/KEYFRAME `ptsMicros` is already on this clock
(the SCK capture time). Headset timestamps are `SystemClock.elapsedRealtimeNanos()/1000`.

### Mac -> headset

| type | name | payload |
|---|---|---|
| 6 | POINTER | JSON `{"dx":3.5,"dy":-1,"buttons":1,"sx":0,"sy":-2,"mods":["cmd"]}` — only while control mode is on. `dx/dy` raw mouse deltas in Mac points since last message; `buttons` bitmask of currently held buttons (1 left, 2 right); `sx/sy` scroll deltas in lines (positive `sy` = scroll up), 0 if none; `mods` held modifiers from `cmd`,`opt`,`ctrl`,`shift`. Coalesced, at most ~120/s. |
| 7 | MODE | JSON `{"control":true}` — control mode toggled (hotkey ⌃⌥⌘M on the Mac). Also sent on connect. |
| 8 | PONG | `u64 headsetT0` (echoed from PING) + `u64 macNowUs` |

### Headset -> Mac

| type | name | payload |
|---|---|---|
| 15 | PING | `u64 headsetNowUs`. Sent every 1 s. Headset estimates `offset = macNowUs - (t0 + t1)/2` using the lowest-RTT sample of the last ~10. |
| 16 | FRAME_REPORT | JSON array, batched every ~500 ms: `[{"id":630,"pts":123,"recv":456,"out":789}]` — `pts` as received; `recv` = frame fully read from socket, `out` = decoder output released to the Surface; both **converted to Mac µs** via the offset. |

### Control mode semantics
- Mac: an event tap swallows all physical mouse/trackpad events (they do not reach Mac apps) and forwards them as POINTER. Keyboard events pass through untouched to the frontmost Mac app. Events the streamer injects itself (CLICK/SCROLL) are tagged and not swallowed. Toggling off, or the client disconnecting, restores normal mouse.
- Headset: draws its own cursor over the panels and owns the layout.
  - No modifier keys. Same UX as native XR panels: hovering within a band around a panel's edge highlights its **outline**.
  - Drag on the outline → move the panel. Drag a **corner** of the outline → resize (keep aspect ratio).
  - Two-finger scroll while hovering the outline → push/pull the panel (depth).
  - Inside the content: click → `CLICK` (the Mac focuses + clicks that window, so typing goes there); two-finger scroll → `SCROLL`.

## v2: one cursor, location decides ownership (supersedes v1 "Control mode semantics" for the headset)

Headset -> Mac, new:

| type | name | payload |
|---|---|---|
| 17 | MOUSE | JSON `{"kind":"move","x":0.42,"y":0.13,"button":0}` — `kind` ∈ `move` (hover), `down`, `up`, `drag` (move with button held); `button` 0 left, 1 right; x/y normalized to the window (0..1, top-left, title bar included). Sent only while the cursor is over that window's **content**. `down` focuses + raises the window first (as CLICK did). Moves/drags coalesced to ≤120/s. CLICK (12) remains for compatibility. |

Headset UX (control mode on):
- The cursor lives on the panel surfaces (drawn just in front, at the panel's depth). Panels sit on a cylinder ~1.2 m around the user and face the user (re-oriented when dropped, then world-stable).
- **Content** → Mac mouse: hover/move, click, drag-select, right-click (two-finger click on the trackpad arrives as POINTER `buttons` bit 2), scroll → SCROLL.
- **Grab bar** (a pill under each panel, like visionOS) → drag moves along the cylinder (yaw + height); two-finger scroll on the bar → distance (radius). **Corners** → resize (aspect kept).
- **Look-to-jump**: when the user touches the trackpad after >0.5 s idle, the cursor jumps to the panel the head (or eyes, if available) points at. Looking never changes keyboard focus; only a click does.

## v3: the cursor belongs to the window

Mac -> headset, new:

| type | name | payload |
|---|---|---|
| 9 | CURSOR | `u16 hotspotX | u16 hotspotY` (in image pixels) `| u16 pointsW | u16 pointsH` (logical size) `| PNG bytes` — the Mac's current system cursor image. Sent while control mode is on, only when the image changes (poll ~60 Hz, compare a hash), and once on entering control mode. |

Headset UX:
- **Capture**: a click (MOUSE down) on a window's content captures the cursor into that window. While captured, the cursor is clamped to the content rect; pushing past an edge accumulates "overshoot" and only after ~60 dp of continued push in that direction does it leave (past the bottom edge it lands on the grab bar). Look-to-jump also releases it (to the looked-at panel). Hovering a window without clicking does not capture.
- **Native gain**: while over a window's content, 1 Mac point of trackpad motion = 1 point of that window's content (i.e. panel dp per window point), so it feels like the Mac. Outside content (gaps, bars, picker), keep the current gain.
- **Cursor image**: over window content, draw the latest CURSOR image at its logical size × (panel dp per window point), hotspot at the pointer location; elsewhere draw the arrow (first CURSOR received, or the white dot fallback).

## v4: comfort + control basics (features 1–4)

Mac -> headset, new:

| type | name | payload |
|---|---|---|
| 20 | COMMAND | JSON `{"cmd":"…", …}` — a spatial command from a global Mac hotkey. Sent whenever a headset is connected (control mode on or off). |

Hotkeys (all ⌃⌥⌘ + key, swallowed by the streamer's hotkey tap; key repeat allowed for move/size/depth):

| keys | cmd | effect on headset |
|---|---|---|
| ⌃⌥⌘M | (existing) | toggle control mode |
| ⌃⌥⌘ ← / → | `{"cmd":"nudge","dtheta":-1}` / `+1` | move the **active panel** one step (5°) along the arc |
| ⌃⌥⌘ ↑ / ↓ | `{"cmd":"nudge","dy":+1}` / `-1` | move active panel up/down one step (5 cm) |
| ⌃⌥⌘ = / - | `{"cmd":"depth","d":-1}` / `+1` | bring active panel closer / push farther (10 cm) |
| ⌃⌥⌘ ] / [ | `{"cmd":"size","d":+1}` / `-1` | grow / shrink active panel 10% (aspect kept) |
| ⌃⌥⌘ C | `{"cmd":"center"}` | put active panel straight ahead at the editor preset |
| ⌃⌥⌘ 1 / 2 / 3 | `{"cmd":"preset","name":"editor"|"side"|"glance"}` | apply a sharpness/size preset to the active panel |
| ⌃⌥⌘ Z / ⇧Z | `{"cmd":"undo"}` / `{"cmd":"redo"}` | undo/redo the last layout change (any source: bar drag, resize, hotkey) |
| ⌃⌥⌘ R | `{"cmd":"recenter"}` | re-center the whole arc on the current head pose (same as the headset recenter button) |
| ⌃⌥⌘ T | `{"cmd":"tidy"}` | re-lay all shown panels in a row in front at eye level (undoable) |
| ⌃⌥⌘ P | `{"cmd":"passthrough"}` | toggle "show the room" (shell hidden) |
| ⌃⌥⌘ . / , | `{"cmd":"dim","d":+1}` / `-1` | environment dim level 0..5 (0 = full passthrough, 5 = pure black) |
| ⌃⌥⌘ ⇧ ←→↑↓ | `{"cmd":"cutout","dw":…,"dh":…}` | widen/narrow (←→) and raise/lower top edge (↑↓) of the laptop cutout |

**Active panel** = the window panel the cursor is captured in, else the one last clicked, else the one the head points at.

Headset behavior:
- **Presets** (1): `editor` = straight ahead, r 0.9 m, ~50° wide (sharpest: largest angular size for code); `side` = r 1.1 m, ~35° wide; `glance` = r 1.4 m, ~25° wide (status/reference only). All constants in one place, tuned on device.
- **Restore** (2): persist the layout (per window: app + title key, θ, y, r, w, h; plus center/forward, dim, passthrough, cutout) to app storage on every change (debounced). On launch/reconnect, re-subscribe to remembered windows that exist in WINDOW_LIST (match by app+title, then by app alone if unique) and place them where they were. Windows not present are remembered for later.
- **Undo** (3): bounded stack (50) of layout snapshots; every committed change (drag end, resize end, hotkey) pushes.
- **Environment** (4): dim level 5→0 blends the shell from opaque black to transparent (alpha steps), passthrough toggle hides the shell entirely; cutout size persisted.

## v5: voice commands (hold space)

Mac -> headset, new:

| type | name | payload |
|---|---|---|
| 21 | VOICE | JSON `{"state":"listening"|"thinking"|"done"|"error","text":"…"}` — push-to-talk status for the headset HUD (shown in the picker header; no new panels). `text` = live transcript while listening, the action summary when done, the error otherwise. |

The voice agent's spatial tools call the same layout operations as COMMAND (20), extended with window targeting by name: COMMAND may carry `"window": "<app or title substring>"` to pick the target panel instead of the active one, and new cmds `show` / `hide` / `focus` (`{"cmd":"show","window":"Slack"}`).

Mac details (Voice.swift):
- Push-to-talk: only in control mode. Space keyDown always reaches the Mac app; if still held at 350 ms the streamer posts one tagged Backspace and starts the mic. Space autorepeats are swallowed while held; any other key during the first 350 ms makes it plain typing; space with a modifier is untouched. Utterances are capped at 30 s.
- VOICE sequence: `listening ""`, `listening "<cumulative transcript>"` per delta (replaces), `thinking ""` on release, `thinking "<transcript>"`, then `done "<summary>"` or `error "<message>"`.
- COMMAND targeting: the Mac resolves the spoken name against WINDOW_LIST and sends `"window"` = that window's app name (or its title when the app has several windows) plus `"id"` (its CGWindowID); prefer `id`. Step fields (`dtheta`, `dy`, `d`) may be ±1..10 = that many steps. `show`/`hide`/`focus` always carry `window` + `id`.
- Lab: inject `{"type":"voice","text":"…"}` runs the planner on that transcript (no mic); the reply carries `"result"` (calls, summary, timings). Mac-side tools (open_url, slack_*, focus_app, search_web) are only logged in lab.

## v6: host identity

Mac -> headset, new:

| type | name | payload |
|---|---|---|
| 22 | HELLO | JSON `{"host":"Work MacBook Pro","id":"00000000-0000-0000-0000-000000000000","version":"d477183"}` — sent **first** on every connect, before WINDOW_LIST. |

- `host`: the Mac's ComputerName (System Settings > General > Sharing), for display.
- `id`: a random UUID created once per Mac and stored in `~/.mind-castle/id` (outside the repo, so it survives rebuilds and re-clones). Key per-host state (layouts) on this, not on `host`.
- `version`: `git rev-parse --short HEAD` of the build (`-dirty` if the mac/ tree had local changes; `unknown` if built outside git), written by `mac/build.sh` to `castle-version` next to the binary.
