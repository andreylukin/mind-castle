# Mind Castle XR test lab

Lets an agent work on the headset UX in the Android XR emulator with nobody wearing a headset. The head is
driven over the emulator's gRPC API, the trackpad goes through the lab streamer's inject socket, layout and
cursor state come from logcat, and screenshots come from `screencap`.

Rules: everything targets `emulator-5554` (`lab.SERIAL`), never the Galaxy XR. The live streamer on 7420 is never
touched. The emulator's 7420 is reversed to the lab streamer on 7421.

## Setup (once)

```sh
cd lab
python3 -m venv .venv && .venv/bin/pip install grpcio grpcio-tools
cp ~/Library/Android/sdk/emulator/lib/emulator_controller.proto gen/
.venv/bin/python -m grpc_tools.protoc -Igen --python_out=gen --grpc_python_out=gen gen/emulator_controller.proto
```

The emulator must be running with `-grpc 8554` (no token).

## Running

```sh
export LAB_BACKEND=fake        # or real (default); see Backends
.venv/bin/python lab.py start  # lab streamer on 7421/7422 + adb reverse (emulator only)
.venv/bin/python scenarios/smoke.py [--no-deploy]
.venv/bin/python scenarios/facing.py
.venv/bin/python scenarios/drag_bar.py
.venv/bin/python scenarios/capture.py
```

Each scenario restarts the app (`--no-restart` skips that), opens the 3 lab windows from the picker with cursor
clicks, and prints `PASS`/`FAIL` lines with evidence. It exits non-zero on any failure. Screenshots and
`results.json` go to `out/<scenario>-<timestamp>/`. Each shot is saved full size (2558 px) plus a
`_s.png` at 1200 px, which is the one to look at.

CLI, one command per call: `lab.py start|stop|deploy [--no-build]|restart|recenter|head YAW PITCH|head_move X Y Z|
mode 1|pointer DX DY BUTTONS SX SY|move DX DY|click|drag DX DY|scroll LINES|shot NAME|state`.

Library: `import lab` (with `sys.path` at `lab/`), then:

| primitive | notes |
|---|---|
| `head(yaw, pitch)` | absolute, in degrees from the app's forward; yaw is positive to the right, pitch positive up |
| `head_rotate(dyaw, dpitch)`, `head_move(x, y, z)` | relative; meters for the move |
| `recenter()` | puts the head at (0, 0) **and** re-anchors the layout (see quirks) |
| `mode(on)`, `pointer(dx, dy, buttons, sx, sy)` | raw inject. dx/dy are Mac points with dy positive = down; `sy` positive = scroll up |
| `move_smooth`, `press`, `release`, `click(buttons)`, `drag(dx, dy)`, `scroll(lines)` | trackpad gestures |
| `keepalive(on)` | 200 ms zero POINTERs (on after `mode(True)`); turn it off to let the app go idle |
| `state()` | parsed `pointer:` line: cursor θ/t, hover, panels {id: θ, y, r, w, h}, center, plus last `capture:` and `control mode` |
| `cursor_to(theta, t)`, `panel_point(st, id, u, v)`, `bar_v(p)` | closed-loop cursor placement in Desk coordinates |
| `shot(name)` | saves a PNG plus its 1200 px downscale and returns the downscale's path |
| `Img(path)`, `measure_panel(img)`, `project(θ, y, r)`, `lit_near(img, xy)` | stdlib pixel checks (sips → BMP) |
| `command(cmd, **fields)` | v4 COMMAND through the inject socket; returns the app's `command: ...` log text |
| `clear_layout()`, `window_titles()` | forget the persisted layout (scenarios do this unless `fresh=False`); get {id: title} from the streamer's `lab: window list` line |
| `embedded_channels()` | system_server input-channel count (leak watch) |
| `deploy()` | gradle `assembleDebug` (JDK 17), install on the emulator, restart the app |
| `start_lab()` / `stop_lab()` | lab streamer lifecycle |
| `streamer_log()`, `wait_streamer(re)` | Mac-side evidence, e.g. `lab: would MOUSE down window 9001 at (0.35,0.5)` |

## Head sign conventions (measured)

- `RotationRadian` is **relative** to the current orientation. Two `y=+0.35` events turn twice as far.
- `+y` turns the view **left**, and `+x` pitches it **up**, so `head_rotate` sends `y = -yaw`, `x = +pitch`.
  Yaw-then-pitch composes as expected (head-local). A 79° single event once looked wrong, so the harness
  sends turns in steps of at most 20° anyway.
- `head()` keeps its own pose bookkeeping in `out/.head.json` and returns to (0, 0) cleanly. Anything that
  rotates the emulator head outside the harness (the emulator UI, raw gRPC) breaks the bookkeeping. Run
  `lab.py recenter` to fix it. Scenarios begin with a frame check: when the head looks at the picker, the
  picker must appear centered and square-on.
- Screenshot projection: the head sits at the cylinder center. f ≈ 357 px for a 600 px image (about 80° FOV,
  square). `project()` uses this.

## Emulator quirks

- **RECENTER re-anchors the layout.** An emulator RECENTER re-anchors the activity space to the *current*
  head pose, then resets the head to neutral. With the head turned 40°, the whole layout ends up 40° off
  (the app logs `head: recenter` with `facing=(0.64, 0, -0.77)`). `recenter()` therefore sends two RECENTERs.
  The app's anchor resets on launch, so scenarios recenter and then relaunch.
- `setXrOptions(passthrough_coefficient=0)` has no visible effect while the app is in full space, because the
  app sets its own passthrough opacity.
- The emulator H.264 decoder (`c2.goldfish`) is slow, ~100 ms per frame with 1 s spikes. The app decodes on
  its network thread, so video sits ahead of POINTER in the same TCP stream. With 2+ windows at 57 fps from
  the real streamer, POINTER lags by seconds (the picker clicks miss). The fake backend sends 2 fps
  (`FAKE_FPS`).
- `state()` only sees what the app logs: a `pointer:` line about once per second while POINTERs flow.
  `state()` waits for a line logged ≥0.4 s after the call, so in-flight pointers are counted.
- **Look-to-jump**: any POINTER after ≥0.5 s idle jumps the cursor to the panel the head points at. The
  keepalive prevents that during scripted moves. Scenarios turn it off on purpose to test the jump.

- **system_server input-channel leak**: each SpatialPanel window holds an `Embedded{}` input channel in
  system_server (`dumpsys input`). They are never freed, not even by a force-stop: about 80 leak per app
  launch, plus one per hover on/off. Around 1000, system_server aborts on its fd-leak check, which kills the
  app. After that, app content is **head-locked**: head rotation moves the passthrough but not the panels.
  `lab.embedded_channels()` reports the count, and scenarios print it. Reboot the guest
  (`adb -s emulator-5554 reboot`) well before the limit. The frame check fails on head-locked content.

## Backends

- `real`: `castle-testwin` (mac/.build/release) + `castle-streamer --lab --only-app castle-testwin --port 7421
  --inject-port 7422` from **mac/.build/lab-bin** (mac-control's signed copy; `LAB_STREAMER` overrides it).
  SCK capture is most reliable when the streamer stays a child of a live shell, so for long sessions start both
  from a background shell rather than `lab.py start`. Don't run two streamers capturing castle-testwin at
  once, because starting or stopping an SCStream in one process interrupts the other process's streams. The windows are real, so ids change on every testwin launch. It needs Screen
  Recording TCC for the *current* build. After a rebuild, SCK failed with "user declined TCCs" and running
  streams died with "application connection being interrupted".
- `fake` (`fake_streamer.py`): the same ports and inject protocol, with no Mac capture. It lists 3 windows
  (9001 Lab Editor 1800×1464, 9002 Lab Browser 1600×1344, 9003 Lab Terminal 1400×1000 px) and loops an
  ffmpeg `testsrc2` clip per window, with a coloured border per window (red, lime, blue). It logs headset
  input in the real streamer's `lab: would ...` format. Fake-only inject: `{"type":"fake_resize","id":..,"w":..,"h":..}`.

Whatever already listens on 7422 is used as is. To switch backends, run `lab.py stop` (it only kills the lab
streamer this harness starts) and then `LAB_BACKEND=... lab.py start`.

## Scenarios

- `smoke.py`: deploy, connect, open the 3 windows via picker clicks, check SUBSCRIBE and decoding, then
  shots at yaw −40/0/+40.
- `facing.py`: radius bounds from state, plus a visual test. For each panel, look at its center and compare
  the left and right edge heights on screen (a 1° yaw error gives ~1.3%). A panel moved up checks top vs
  bottom width (tilt toward the eye).
- `drag_bar.py`: checks that grab bars are drawn where they hit-test, a bar drag including a mid-drag render,
  real-user paths (pause then press, glance away then press, exit a captured window onto the bar), depth
  scroll, corner resize, and that bars follow Mac-side resizes.
- `shell.py`: the laptop cutout spans forward ±25° as seen from the head, and follows a recenter.
- `leak.py`: the system_server `Embedded{}` input-channel count before and after N hover on/off cycles, an app
  relaunch, and a force-stop.
- `restore.py`: arrange 3 panels (bar drag, COMMAND nudge/depth, corner resize). Then force-stop and relaunch,
  and expect the same layout. Then restart castle-testwin (real; with fake, the streamer restarts with
  `FAKE_ID_BASE`), so the window IDs change, and expect the same layout matched by title.
- `commands.py`: every v4 spatial COMMAND changes the active panel by its spec (5°, 5 cm, 10 cm, ×1.1,
  presets). Then 12× undo, 12× redo, coalescing, bar drag as an undo step, active = last click, and recenter.
- `environment.py`: mean luma at dim 5..0 (black → room, monotonic, even), banding lines at overlaps, the
  passthrough toggle, and cutout width and top changes measured on screen.
- `load.py [seconds]` (real backend): 3 windows at full rate for 60 s while the cursor sweeps. It asserts PING
  rtt p95 < 100 ms (from the app's `clock offset=… rtt=` lines, about one every 10 s) and POINTER→app latency
  p95 < 250 ms / max < 1 s, without growth over the run. Latency is measured with right-button presses in empty
  space, timed to the immediate `pointer:` line; this includes logcat delay, so it's an upper bound.
- `lying.py`: start upright with 2 windows; pitch the head +70° and send a COMMAND recenter. Then expect: the
  layout in front of the eyes (lit-mask IoU with the upright shot), a click capturing into a window, and the
  layout still anchored after a relaunch. Pitch back to 0 + recenter and the geometry must match the start. An
  emulator RECENTER while pitched is recorded as an observation only, since it also resets the emulator head.
- `capture.py`: hover without capture, click to capture with MOUSE at the right spot, native gain, edge
  clamp and overshoot exit, drag-select staying captured, right click, scroll, look-to-jump, and the I-beam
  cursor image.
