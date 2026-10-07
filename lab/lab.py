#!/usr/bin/env python3
"""Mind Castle XR emulator test lab: fake head (emulator gRPC), fake trackpad (lab streamer inject
socket), screenshots and Desk state from logcat. Only ever talks to emulator-5554.

Library:  import lab; lab.start_lab(); lab.mode(True); lab.head(-40, 0); lab.shot("left"); lab.state()
CLI:      lab/.venv/bin/python lab/lab.py <command> [args]   (see `help`)
"""
import json
import math
import os
import re
import socket
import subprocess
import sys
import threading
import time
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(HERE, "gen"))

SERIAL = "emulator-5554"  # never the real headset
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
GRPC = "localhost:8554"
PKG = "dev.mindcastle"
LAB_PORT, INJECT_PORT, DEVICE_PORT = 7421, 7422, 7420
# Signed copy kept by mac-control: the plain release binary loses Screen Recording TCC after rebuilds.
STREAMER = os.environ.get("LAB_STREAMER", os.path.join(REPO, "mac/.build/lab-bin/castle-streamer"))
TESTWIN = os.path.join(REPO, "mac/.build/release/castle-testwin")
JAVA_HOME = "/opt/homebrew/opt/openjdk@17"

RUN = os.environ.get("LAB_RUN") or time.strftime("%Y%m%d-%H%M%S")
OUT = os.path.join(HERE, "out", RUN)


def log(*a):
    print("[lab]", *a, flush=True)


# ---------------------------------------------------------------- adb


def adb(*args, timeout=30, check=True, binary=False):
    r = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace').strip()}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


# ---------------------------------------------------------------- head (emulator gRPC)

_stub = None


def _grpc():
    global _stub
    if _stub is None:
        import grpc
        import emulator_controller_pb2_grpc as g
        _stub = g.EmulatorControllerStub(grpc.insecure_channel(GRPC))
    return _stub


def _event(**kw):
    import emulator_controller_pb2 as pb
    _grpc().streamInputEvent(iter([pb.InputEvent(**kw)]), timeout=5)


# Our own bookkeeping of the emulator head (yaw, pitch degrees in the app's frame), persisted across processes.
HEAD_FILE = os.path.join(HERE, "out", ".head.json")


def _head_get():
    try:
        with open(HEAD_FILE) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def _head_set(yaw, pitch):
    os.makedirs(os.path.dirname(HEAD_FILE), exist_ok=True)
    with open(HEAD_FILE, "w") as f:
        json.dump([yaw, pitch], f)


def recenter(settle=0.8):
    """Put the head at yaw 0 / pitch 0 in the app's frame.

    Emulator RECENTER does two things: the system re-anchors the activity space (and so the whole app layout)
    to the *current* head pose, and the emulator resets the head to its neutral pose. With the head turned, the
    layout ends up rotated by that turn. A second RECENTER (head now neutral) re-anchors to neutral, so two in a
    row always give layout frame == head frame. The app logs `head: recenter` + a new cylinder center for each."""
    import emulator_controller_pb2 as pb
    for _ in range(2):
        _event(xr_command=pb.XrCommand(action=pb.XrCommand.RECENTER))
        time.sleep(0.5)
    _head_set(0.0, 0.0)
    time.sleep(settle)


ROT_STEP_S = float(os.environ.get("LAB_ROT_STEP_S", "0.3"))


def head_rotate(yaw_deg=0.0, pitch_deg=0.0, settle=0.8):
    """Relative rotation in the head's own frame. yaw > 0 turns right, pitch > 0 looks up
    (emulator RotationRadian: +y turns left, +x looks up; angles are relative to the current orientation)."""
    import emulator_controller_pb2 as pb
    # Large single events misbehave (a 79° yaw came out as a rolled, partial turn), so step in <= 20° chunks.
    for axis, deg in (("y", -yaw_deg), ("x", pitch_deg)):
        n = max(1, math.ceil(abs(deg) / 20)) if deg else 0
        for _ in range(n):
            _event(xr_head_rotation_event=pb.RotationRadian(**{axis: math.radians(deg / n)}))
            time.sleep(ROT_STEP_S)
    time.sleep(settle)


def head(yaw_deg=0.0, pitch_deg=0.0, settle=1.0):
    """Absolute head pose (degrees from the app's forward; yaw right +, pitch up +).
    Undo the current pitch, turn by the yaw difference, re-apply pitch, so yaw stays about world-up.
    Does not use RECENTER (that would move the layout); call recenter() once to establish the frame."""
    cur = _head_get()
    if cur is None:
        recenter()
        cur = [0.0, 0.0]
    cy, cp = cur
    if cp:
        head_rotate(0, -cp, settle=0)
    head_rotate(yaw_deg - cy, 0, settle=0)
    if pitch_deg:
        head_rotate(0, pitch_deg, settle=0)
    _head_set(yaw_deg, pitch_deg)
    time.sleep(settle)


def head_move(x=0.0, y=0.0, z=0.0, settle=0.8):
    """Relative head translation in meters."""
    import emulator_controller_pb2 as pb
    _event(xr_head_movement_event=pb.Translation(delta_x=x, delta_y=y, delta_z=z))
    time.sleep(settle)


def passthrough(coef):
    import emulator_controller_pb2 as pb
    _grpc().setXrOptions(pb.XrOptions(passthrough_coefficient=float(coef)), timeout=5)


# ---------------------------------------------------------------- trackpad (lab streamer inject socket)

_inj = None
_inj_r = None
_inj_lock = threading.Lock()
_buttons = 0
_last_ptr = 0.0


RECONNECTS = []  # (time, error) each time the headset dropped mid-run; scenarios report these
_expected_drop_until = 0.0  # set when the harness itself restarts the app or streamer


def _relock_after_reconnect(timeout=20.0):
    """Called with _inj_lock held: wait until the headset is back, then turn control mode on again."""
    end = time.time() + timeout
    while time.time() < end:
        time.sleep(0.5)
        _inj.sendall((json.dumps({"type": "mode", "control": True}) + "\n").encode())
        if json.loads(_inj_r.readline() or b"{}").get("ok"):
            time.sleep(0.5)
            return
    raise TimeoutError("headset did not reconnect to the lab streamer")


def inject(obj, timeout=3.0):
    """Send one JSON command to the inject socket; returns the parsed reply."""
    global _inj, _inj_r
    with _inj_lock:
        for attempt in range(4):
            try:
                if _inj is None:
                    _inj = socket.create_connection(("127.0.0.1", INJECT_PORT), timeout=timeout)
                    _inj.settimeout(timeout)
                    _inj_r = _inj.makefile("rb")
                _inj.sendall((json.dumps(obj) + "\n").encode())
                reply = json.loads(_inj_r.readline() or b"{}")
                if not reply.get("ok"):
                    err = str(reply.get("error", ""))
                    if obj.get("type") != "mode" and ("no headset client" in err or "control mode is off" in err):
                        if time.time() > _expected_drop_until:
                            RECONNECTS.append((time.time(), err))
                        log(f"headset dropped ({err}); waiting for reconnect")
                        _relock_after_reconnect()
                        continue
                    raise RuntimeError(f"inject {obj}: {reply}")
                return reply
            except (OSError, ValueError):
                _inj = None
                if attempt >= 1:
                    raise


def mode(on=True):
    inject({"type": "mode", "control": bool(on)})
    if on:
        keepalive(True)


def command(cmd, timeout=5.0, **fields):
    """PROTOCOL v4 COMMAND via the inject socket (as if a Mac hotkey fired). Returns the app's `command: ...` log line."""
    _ensure_tail()
    t0 = time.time()
    inject({"type": "command", "cmd": cmd, **fields})
    line = wait_log(r"command: ", t0, timeout)
    return line.split("command: ", 1)[1] if line else None


def clear_layout():
    """Forget the app's persisted layout (SharedPreferences "castle"); debug build, so run-as works."""
    expect_drop()
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "run-as", PKG, "rm", "-f", "shared_prefs/castle.xml", check=False)


def window_titles():
    """{id: title} from the newest `lab: window list` streamer line (real and fake backends log it)."""
    hits = [l for l in streamer_log().splitlines() if "lab: window list" in l]
    if not hits:
        return {}
    out = {}
    for part in hits[-1].split("lab: window list", 1)[1].split(","):
        if ":" in part:
            i, t = part.strip().split(":", 1)
            out[int(i)] = t.strip()
    return out


def cursor_image(name="arrow"):
    inject({"type": "cursor", "name": name})


def pointer(dx=0.0, dy=0.0, buttons=None, sx=0.0, sy=0.0):
    """One POINTER: dx/dy Mac points (dy + = down), buttons bitmask (1 left, 2 right; None = keep), sy + = scroll up."""
    global _buttons, _last_ptr
    if buttons is not None:
        _buttons = buttons
    inject({"type": "pointer", "dx": dx, "dy": dy, "buttons": _buttons, "sx": sx, "sy": sy})
    _last_ptr = time.time()


# Keepalive: a zero POINTER every 200 ms so the app never sees >0.5 s idle, which would trigger
# look-to-jump on the next touch. It also keeps the 5 s `pointer:` log line coming.
_ka_on = threading.Event()


def _ka_loop():
    while True:
        _ka_on.wait()
        if time.time() - _last_ptr > 0.2:
            try:
                pointer()
            except Exception as e:  # noqa: BLE001 - keep the thread alive across streamer restarts
                log("keepalive:", e)
                time.sleep(1)
        time.sleep(0.05)


threading.Thread(target=_ka_loop, daemon=True).start()


def keepalive(on=True):
    """Pause (False) to let the app go idle, e.g. to test look-to-jump."""
    (_ka_on.set if on else _ka_on.clear)()


def move_smooth(dx, dy, steps=None, dt=0.008, buttons=None):
    """Move by (dx, dy) Mac points in small steps, like a finger on the trackpad."""
    steps = steps or max(1, int(max(abs(dx), abs(dy)) / 4))
    sent = [0.0, 0.0]
    for i in range(1, steps + 1):
        x, y = dx * i / steps, dy * i / steps
        pointer(x - sent[0], y - sent[1], buttons)
        sent = [x, y]
        time.sleep(dt)


def press(buttons=1):
    pointer(0, 0, buttons)
    time.sleep(0.05)


def release():
    pointer(0, 0, 0)
    time.sleep(0.05)


def click(buttons=1):
    press(buttons)
    release()


def drag(dx, dy, steps=None):
    """Press, move by (dx, dy) Mac points, release."""
    press(1)
    move_smooth(dx, dy, steps, buttons=1)
    release()


def scroll(lines, steps=None, dx=0.0):
    """Two-finger scroll; lines > 0 = scroll up (pushes a panel away when on its bar)."""
    steps = steps or max(1, int(abs(lines)))
    for _ in range(steps):
        pointer(0, 0, None, sx=dx / steps, sy=lines / steps)
        time.sleep(0.016)


# ---------------------------------------------------------------- logcat -> state

_lines = deque(maxlen=5000)  # (host time, line)
_tail = None


def _tail_loop():
    global _tail
    while True:
        _tail = subprocess.Popen([ADB, "-s", SERIAL, "logcat", "-T", "1", "-v", "brief", "-s", "Castle:*"],
                                 stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
        for line in _tail.stdout:
            _lines.append((time.time(), line.rstrip("\n")))
        time.sleep(1)


def _kill_tail():
    if _tail is not None:
        _tail.kill()


import atexit  # noqa: E402
atexit.register(_kill_tail)


def _ensure_tail():
    if _tail is None:
        # History (timestamped 0 so it never counts as "since"), then follow.
        for line in adb("logcat", "-d", "-v", "brief", "-s", "Castle:*", check=False).splitlines()[-2000:]:
            _lines.append((0.0, line))
        threading.Thread(target=_tail_loop, daemon=True).start()
        time.sleep(0.5)


def logs(since=0.0, pattern=None):
    """Castle log lines seen since host time [since], optionally filtered by regex."""
    _ensure_tail()
    rx = re.compile(pattern) if pattern else None
    return [l for t, l in list(_lines) if t >= since and (rx is None or rx.search(l))]


def wait_log(pattern, since, timeout=10.0):
    """First Castle log line matching [pattern] after [since], or None after [timeout] s."""
    end = time.time() + timeout
    while time.time() < end:
        hit = logs(since, pattern)
        if hit:
            return hit[0]
        time.sleep(0.1)
    return None


_F = r"(-?[\d.]+(?:E-?\d+)?)"
_PANEL = re.compile(r"(\d+)@\(θ " + _F + r" y " + _F + r" r " + _F + r" " + _F + r"x" + _F + r"\)")
_HOVER = re.compile(r"hover=(null|Hover\(id=(\d+), zone=(\w+), cx=(-?\d+), cy=(-?\d+)\))")
_CURSOR = re.compile(r"cursor=\(θ " + _F + r", t " + _F + r"\)")
_CENTER = re.compile(r"center=Vec3\(x=" + _F + r", y=" + _F + r", z=" + _F + r"\)")


def parse_pointer(line):
    c = _CURSOR.search(line)
    h = _HOVER.search(line)
    ce = _CENTER.search(line)
    panels = {int(m[1]): dict(theta=float(m[2]), y=float(m[3]), r=float(m[4]), w=float(m[5]), h=float(m[6]))
              for m in _PANEL.finditer(line)}
    hover = None
    if h and h[1] != "null":
        hover = dict(id=int(h[2]), zone=h[3], cx=int(h[4]), cy=int(h[5]))
    return dict(cursor=dict(theta=float(c[1]), t=float(c[2])) if c else None, hover=hover,
                center=[float(ce[i]) for i in (1, 2, 3)] if ce else None, panels=panels)


def state(fresh=True, timeout=7.0):
    """Desk state from the latest `pointer:` log line (+ last capture/control lines).
    fresh=True waits for a line logged after this call (keepalive must be on; up to ~5 s)."""
    _ensure_tail()
    t0 = time.time()
    line = None
    if fresh:
        if not _ka_on.is_set():
            pointer()
        # Lines logged right after the call may predate pointers still in flight (adb tunnel, video ahead of
        # them in the TCP stream): only trust a line logged >= 0.4 s after the call.
        line = wait_log(r"pointer: ", t0 + 0.4, timeout)
        if line is None:
            raise TimeoutError("no fresh `pointer:` line (is control mode on and the app connected?)")
    else:
        hits = logs(0, r"pointer: ")
        line = hits[-1] if hits else None
    st = parse_pointer(line) if line else {}
    cap = logs(0, r"capture: ")
    st["capture"] = cap[-1].split("capture: ", 1)[1] if cap else None
    st["captured"] = None
    if st["capture"] and st["capture"].startswith("start"):
        st["captured"] = int(re.search(r"window (\d+)", st["capture"])[1])
    ctl = logs(0, r"control mode ")
    st["control"] = ctl[-1].endswith("true") if ctl else None
    return st


def panel_angle_deg(p):
    """Yaw of a panel as seen from the cylinder axis (deg, + right)."""
    return math.degrees(p["theta"])


# ---------------------------------------------------------------- screenshots


def shot(name):
    """Full-res PNG + 1200 px downscale under lab/out/<run>/. Returns the downscale's path."""
    os.makedirs(OUT, exist_ok=True)
    full = os.path.join(OUT, f"{name}.png")
    small = os.path.join(OUT, f"{name}_s.png")
    data = adb("exec-out", "screencap", "-p", binary=True, timeout=20)
    with open(full, "wb") as f:
        f.write(data)
    subprocess.run(["sips", "-Z", "1200", full, "--out", small], capture_output=True, timeout=20, check=True)
    log("shot", small)
    return small


class Img:
    """Downscaled screenshot as raw pixels (via sips -> 32-bit BMP; stdlib only)."""

    def __init__(self, path, size=600):
        import struct
        import tempfile
        bmp = os.path.join(tempfile.gettempdir(), f"lab-{os.getpid()}.bmp")
        subprocess.run(["sips", "-s", "format", "bmp", "-Z", str(size), path, "--out", bmp], capture_output=True, check=True, timeout=20)
        d = open(bmp, "rb").read()
        os.unlink(bmp)
        off = struct.unpack("<I", d[10:14])[0]
        self.w, h, _, bpp = struct.unpack("<iiHH", d[18:30])
        assert bpp == 32, bpp
        self.h = abs(h)
        masks = struct.unpack("<III", d[54:66])  # R, G, B bitfield masks
        self.shift = [(m & -m).bit_length() - 1 for m in masks]
        self.px = d[off:off + self.w * self.h * 4]
        self.bottom_up = h > 0

    def rgb(self, x, y):
        if self.bottom_up:
            y = self.h - 1 - y
        v = int.from_bytes(self.px[(y * self.w + x) * 4:(y * self.w + x) * 4 + 4], "little")
        return tuple((v >> s) & 255 for s in self.shift)

    def lit(self, x, y, thresh=12):
        # The black shell renders as pure (0,0,0); the picker background is #151515.
        return max(self.rgb(x, y)) > thresh


def measure_panel(img, cx=None, cy=None, inset=6):
    """Lit blob through (cx, cy) (default: image center): its horizontal span on that row, and the vertical
    extent of the blob near its left and right edges. A panel squarely facing a camera that looks straight at its
    center has equal left/right heights; a panel yawed by e degrees gives ratio ~ (r + w/2 sin e)/(r - w/2 sin e)."""
    cx = img.w // 2 if cx is None else cx
    cy = img.h // 2 if cy is None else cy
    if not img.lit(cx, cy):
        return None
    x0 = cx
    while x0 > 0 and img.lit(x0 - 1, cy):
        x0 -= 1
    x1 = cx
    while x1 < img.w - 1 and img.lit(x1 + 1, cy):
        x1 += 1

    def vext(x):
        a = b = cy
        while a > 0 and img.lit(x, a - 1):
            a -= 1
        while b < img.h - 1 and img.lit(x, b + 1):
            b += 1
        return a, b
    def hext(y):
        a = b = cx
        while a > 0 and img.lit(a - 1, y):
            a -= 1
        while b < img.w - 1 and img.lit(b + 1, y):
            b += 1
        return b - a + 1
    la, lb = vext(x0 + inset)
    ra, rb = vext(x1 - inset)
    lh, rh = lb - la + 1, rb - ra + 1
    ta, tb = vext(cx)
    tw, bw = hext(ta + inset), hext(tb - inset)
    return dict(x0=x0, x1=x1, left=(la, lb), right=(ra, rb), left_h=lh, right_h=rh, ratio=lh / rh,
                top_w=tw, bottom_w=bw, ratio_tb=tw / bw,
                center_offset=((x0 + x1) / 2 - img.w / 2) / img.w, clipped=x0 == 0 or x1 == img.w - 1)


F600 = 357.0  # focal length in px for a 600 px screenshot (measured: ~80° FOV); scales with image size


def head_pose():
    """(yaw, pitch) degrees the harness last set."""
    return tuple(_head_get() or (0.0, 0.0))


def measure_dark_panel(img, lo=12, hi=40, min_count=8, box=None):
    """Bounding box of picker-coloured pixels (#151515-ish: lo < max channel < hi), independent of the background
    (black shell or passthrough). Returns the same keys as measure_panel, or None."""
    cols = [0] * img.w
    rows = [0] * img.h
    first = {}
    last = {}
    bx0, by0, bx1, by1 = box or (0, 0, img.w, img.h)
    for y in range(by0, by1):
        for x in range(bx0, bx1):
            if lo < max(img.rgb(x, y)) < hi:
                cols[x] += 1
                rows[y] += 1
                first.setdefault(x, y)
                last[x] = y
    xs = [x for x, c in enumerate(cols) if c > min_count]
    ys = [y for y, c in enumerate(rows) if c > min_count]
    if not xs or not ys:
        return None
    x0, x1 = xs[0], xs[-1]
    lh = cols[x0 + 4] if x0 + 4 < img.w else 0
    rh = cols[x1 - 4] if x1 >= 4 else 0
    return dict(x0=x0, x1=x1, y0=ys[0], y1=ys[-1], left_h=lh, right_h=rh, ratio=lh / rh if rh else 0,
                center_offset=((x0 + x1) / 2 - img.w / 2) / img.w, clipped=x0 == 0 or x1 == img.w - 1)


def project_world(P, size=600):
    """Pixel (x, y) in a [size] px screenshot of point P = (x, y, z) dp relative to the head (x right, y up,
    z toward the start view). None if behind the camera."""
    yaw, pitch = (math.radians(a) for a in head_pose())
    right = (math.cos(yaw), 0.0, math.sin(yaw))
    up = (-math.sin(yaw) * math.sin(pitch), math.cos(pitch), math.cos(yaw) * math.sin(pitch))
    fwd = (math.sin(yaw) * math.cos(pitch), math.sin(pitch), -math.cos(yaw) * math.cos(pitch))
    X, Y, Z = (sum(a * b for a, b in zip(P, axis)) for axis in (right, up, fwd))
    if Z <= 1:
        return None
    f = F600 * size / 600
    return size / 2 + f * X / Z, size / 2 - f * Y / Z


def project(theta, y, r, u=0.0, size=600):
    """Pixel of a point on the cylinder: angle [theta], height [y] dp, radius [r], plus [u] dp along the
    panel's right axis (no pitch). Assumes the head sits at the cylinder center."""
    return project_world((r * math.sin(theta) + u * math.cos(theta), y, -r * math.cos(theta) + u * math.sin(theta)), size)


def project_panel(p, u=0.0, v=0.0, size=600):
    """Pixel of plane point (u right, v up, dp from center) on Desk panel [p] (dict θ/y/r), with the panel pitched
    toward the eye by atan2(y, r) as Desk does since v4."""
    th, ph = p["theta"], math.atan2(p["y"], p["r"])
    c = (p["r"] * math.sin(th), p["y"], -p["r"] * math.cos(th))
    right = (math.cos(th), 0.0, math.sin(th))
    up = (-math.sin(ph) * math.sin(th), math.cos(ph), math.sin(ph) * math.cos(th))
    return project_world(tuple(c[i] + right[i] * u + up[i] * v for i in range(3)), size)


def lit_near(img, xy, rad=3):
    """Any lit pixel within [rad] px of xy?"""
    if xy is None:
        return False
    x, y = int(round(xy[0])), int(round(xy[1]))
    return any(img.lit(i, j) for i in range(max(0, x - rad), min(img.w, x + rad + 1))
               for j in range(max(0, y - rad), min(img.h, y + rad + 1)))


# ---------------------------------------------------------------- app + streamer lifecycle


def link_rtt_ms():
    """Latest PING round trip the app measured (ms), from its `clock offset=... rtt=... us` line, or None.
    Seconds here mean the app is behind on its socket (video ahead of POINTER), so every state() is stale."""
    hits = logs(0, r"clock offset=")
    m = re.search(r"rtt=(\d+) us", hits[-1]) if hits else None
    return int(m[1]) / 1000 if m else None


def embedded_channels():
    """Input channels of embedded (SpatialPanel) windows held by system_server. They leak: ~80 per app launch and
    one per hover on/off, never freed even after force-stop. system_server aborts on its fd-leak check at some point
    (seen at 22:12:30: `aborting due to fd leak ... InputChannel::openInputChannelPair`), which kills the app."""
    out = adb("shell", "dumpsys", "input", check=False)
    return out.split("Connections:", 1)[-1].count("Embedded{}")


def app_pid():
    return adb("shell", "pidof", PKG, check=False).strip() or None


def expect_drop(seconds=30.0):
    global _expected_drop_until
    _expected_drop_until = time.time() + seconds


def restart_app(wait_connected=True, timeout=30):
    expect_drop()
    t0 = time.time()
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    _ensure_tail()
    if wait_connected and not wait_log(r"head: cylinder center", t0, timeout):
        raise TimeoutError("app did not report its cylinder center")


def deploy(build=True):
    """assembleDebug in headset/, install on the emulator only, restart the app."""
    hs = os.path.join(REPO, "headset")
    if build:
        env = dict(os.environ, JAVA_HOME=JAVA_HOME)
        r = subprocess.run(["./gradlew", "-q", "assembleDebug"], cwd=hs, env=env, capture_output=True, text=True, timeout=600)
        if r.returncode != 0:
            raise RuntimeError("gradle failed:\n" + r.stdout[-3000:] + r.stderr[-3000:])
    apk = os.path.join(hs, "app/build/outputs/apk/debug/app-debug.apk")
    adb("install", "-r", apk, timeout=120)
    log("installed", apk)
    restart_app()


def _listening(port):
    try:
        socket.create_connection(("127.0.0.1", port), timeout=0.5).close()
        return True
    except OSError:
        return False


STREAMER_LOG = os.path.join(HERE, "out", "streamer.log")


BACKEND = os.environ.get("LAB_BACKEND", "real")  # "real" = castle-streamer --lab + castle-testwin, "fake" = fake_streamer.py


def start_lab(backend=None, timeout=30):
    """Lab streamer on 7421/7422 if nothing listens there yet; adb reverse 7420 -> 7421 (emulator only).
    backend "real": castle-testwin + `castle-streamer --lab`; "fake": fake_streamer.py (no Mac capture needed).
    Whatever already listens on 7422 is used as is."""
    backend = backend or BACKEND
    os.makedirs(os.path.dirname(STREAMER_LOG), exist_ok=True)
    if not _listening(INJECT_PORT):
        f = open(STREAMER_LOG, "a")
        if backend == "fake":
            cmd = [sys.executable, os.path.join(HERE, "fake_streamer.py")]
        else:
            if subprocess.run(["pgrep", "-f", "castle-testwin"], capture_output=True).returncode != 0:
                subprocess.Popen([TESTWIN], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
                log("started castle-testwin")
                time.sleep(1.5)
            cmd = [STREAMER, "--lab", "--only-app", "castle-testwin"]
            log("note: SCK capture is most reliable when the streamer stays a child of a live shell; "
                "for long real-backend sessions launch it from a background shell (README)")
        subprocess.Popen(cmd + ["--port", str(LAB_PORT), "--inject-port", str(INJECT_PORT)],
                         stdout=f, stderr=subprocess.STDOUT, start_new_session=True)
        log(f"started {backend} lab streamer, log:", STREAMER_LOG)
        end = time.time() + timeout
        while not (_listening(LAB_PORT) and _listening(INJECT_PORT)):
            if time.time() > end:
                raise TimeoutError("lab streamer did not come up; see " + STREAMER_LOG)
            time.sleep(0.3)
    adb("reverse", f"tcp:{DEVICE_PORT}", f"tcp:{LAB_PORT}")
    _ensure_tail()


def stop_lab():
    """Stop the lab streamer this module starts (exact command line; never the live streamer on 7420 or others' lab runs)."""
    expect_drop(60)
    subprocess.run(["pkill", "-f", f"castle-streamer --lab --only-app castle-testwin --port {LAB_PORT}"], capture_output=True)
    subprocess.run(["pkill", "-f", "fake_streamer.py"], capture_output=True)
    end = time.time() + 10
    while _listening(INJECT_PORT) and time.time() < end:
        time.sleep(0.2)


def streamer_log(since_bytes=0):
    try:
        with open(STREAMER_LOG) as f:
            f.seek(since_bytes)
            return f.read()
    except FileNotFoundError:
        return ""


def wait_streamer(pattern, since_bytes=0, timeout=3.0):
    """Streamer log lines matching [pattern] after byte offset [since_bytes]; waits up to [timeout] s for one."""
    rx = re.compile(pattern)
    end = time.time() + timeout
    while True:
        hits = [l for l in streamer_log(since_bytes).splitlines() if rx.search(l)]
        if hits or time.time() > end:
            return hits
        time.sleep(0.1)


def streamer_log_size():
    try:
        return os.path.getsize(STREAMER_LOG)
    except FileNotFoundError:
        return 0


def wait_connected(timeout=25):
    """Control mode on and wait for a `pointer:` line: POINTER only reaches the app once it's connected."""
    end = time.time() + timeout
    while time.time() < end:
        try:
            mode(True)
            return state(timeout=7)
        except (TimeoutError, OSError, RuntimeError) as e:
            log("waiting for app connection:", e)
    raise TimeoutError("app never connected to the lab streamer")


# ---------------------------------------------------------------- geometry helpers for scenarios


def cursor_to(theta, t, st=None, tries=3, tol=0.004):
    """Closed-loop move of the cursor to cylinder angle [theta] (rad) / elevation tan [t].
    Open-loop gain away from content is 1 dp per point at the hovered panel's radius; over content it's native gain,
    so a couple of corrections may be needed."""
    st = st or state()
    for _ in range(tries):
        c = st["cursor"]
        if abs(c["theta"] - theta) < tol and abs(c["t"] - t) < tol:
            return st
        r = _cursor_r(st)
        g = _gain(st)
        move_smooth((theta - c["theta"]) * r / g, -(t - c["t"]) * r / g)
        st = state()
    return st


def _cursor_r(st):
    h = st.get("hover")
    if h and h["id"] in st["panels"]:
        return st["panels"][h["id"]]["r"]
    return next(iter(st["panels"].values()))["r"] if st["panels"] else 960.0


def _gain(st):
    """dp per Mac point where the cursor is; native gain over window content (panel w / window points)."""
    h = st.get("hover")
    if h and h["zone"] == "CONTENT" and h["id"] != 0:
        w = WINDOW_PTS.get(h["id"])
        if w:
            return st["panels"][h["id"]]["w"] / w
    return 1.0


# id -> window width in Mac points (WINDOW_LIST pixels / 2), for native gain over content. Known for the fake backend.
WINDOW_PTS = {9001: 900, 9002: 800, 9003: 700} if BACKEND == "fake" else {}


def panel_point(st, pid, u, v):
    """(theta, t) of plane point (u right, v up, dp from center) on panel [pid]."""
    p = st["panels"][pid]
    return p["theta"] + math.atan(u / p["r"]), (p["y"] + v) / p["r"]


BAR_GAP, BAR_H = 14.0, 10.0


def bar_v(p):
    return -p["h"] / 2 - BAR_GAP - BAR_H / 2


# ---------------------------------------------------------------- CLI


def _cli():
    cmds = {
        "start": lambda b=None: start_lab(b), "stop": lambda: stop_lab(), "deploy": lambda *a: deploy(build="--no-build" not in a),
        "restart": lambda: restart_app(), "recenter": lambda: recenter(),
        "head": lambda y="0", p="0": head(float(y), float(p)),
        "head_move": lambda x="0", y="0", z="0": head_move(float(x), float(y), float(z)),
        "passthrough": lambda c: passthrough(float(c)),
        "mode": lambda on="1": mode(on not in ("0", "off", "false")),
        "pointer": lambda dx="0", dy="0", b="0", sx="0", sy="0": pointer(float(dx), float(dy), int(b), float(sx), float(sy)),
        "move": lambda dx, dy: move_smooth(float(dx), float(dy)),
        "click": lambda: click(), "drag": lambda dx, dy: drag(float(dx), float(dy)),
        "scroll": lambda n: scroll(float(n)),
        "shot": lambda name="shot": shot(name),
        "state": lambda: print(json.dumps(state(), indent=1)),
    }
    if len(sys.argv) < 2 or sys.argv[1] not in cmds:
        print("usage: lab.py " + " | ".join(cmds))
        return
    mode_needed = sys.argv[1] in ("pointer", "move", "click", "drag", "scroll", "state")
    if mode_needed:
        keepalive(True)
    r = cmds[sys.argv[1]](*sys.argv[2:])
    if r is not None:
        print(r)


if __name__ == "__main__":
    _cli()
