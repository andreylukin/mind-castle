#!/usr/bin/env python3
"""Cursor capture (PROTOCOL v3): click into content captures, native gain, clamping + overshoot exit,
drag-select never exits, no look-to-jump after a pause (disabled), hover alone never captures; plus MOUSE/SCROLL reaching the Mac.
Usage: capture.py [--no-restart]"""
import math
import re
import sys
import time

from _common import check, finish, fmt, lab, setup, window_ids

OVERSHOOT = 60.0


def plane_uv(st, pid):
    p = st["panels"][pid]
    c = st["cursor"]
    return p["r"] * math.tan(c["theta"] - p["theta"]), p["r"] * c["t"] - p["y"]


def gain(st, pid):
    p = st["panels"][pid]
    return p["w"] / lab.WINDOW_PTS[pid] if pid in lab.WINDOW_PTS else None


def mac_lines(since, pat, timeout=2.0):
    return lab.wait_streamer(pat, since, timeout)


st = setup(windows=3, restart="--no-restart" not in sys.argv)
a, b = window_ids(st)[:2]
lab.head(math.degrees(st["panels"][a]["theta"]), 0)

# --- Hover alone never captures.
st = lab.cursor_to(*lab.panel_point(st, a, -0.3 * st["panels"][a]["w"] / 2, 0), st)
check("hovering a window's content without clicking does not capture",
      st["hover"] and st["hover"]["id"] == a and st["hover"]["zone"] == "CONTENT" and st["captured"] is None,
      f"hover={st['hover']} capture={st['capture']}")

# --- Click captures; MOUSE down/up land at the clicked spot.
p = st["panels"][a]
u, v = plane_uv(st, a)
want = ((u + p["w"] / 2) / p["w"], (p["h"] / 2 - v) / p["h"])
log0 = lab.streamer_log_size()
t0 = time.time()
lab.click()
st = lab.state()
downs = mac_lines(log0, rf"would MOUSE down window {a} ")
ups = mac_lines(log0, rf"would MOUSE up window {a} ")
got = tuple(map(float, re.search(r"at \(([\d.]+),([\d.]+)\)", downs[-1]).groups())) if downs else None
check("click in content captures the cursor into that window", st["captured"] == a, f"capture={st['capture']}")
check("click sends MOUSE down + up to the Mac at the clicked spot", downs and ups and got
      and abs(got[0] - want[0]) < 0.01 and abs(got[1] - want[1]) < 0.01, f"want {want[0]:.3f},{want[1]:.3f}; Mac: {downs[-1:]} {ups[-1:]}")
lab.shot("captured")

# --- Native gain: 100 Mac points = 100 window points.
k = gain(st, a)
u0, v0 = plane_uv(st, a)
lab.move_smooth(100, 0)
st = lab.state()
u1, _ = plane_uv(st, a)
if k:
    check("native gain while captured: 100 pt of trackpad = 100 window pt", abs((u1 - u0) - 100 * k) < 3,
          f"Δu={u1 - u0:.1f} dp, expected {100 * k:.1f} dp (k={k:.3f} dp/pt)")

# --- Clamp at the right edge, then overshoot exit.
p = st["panels"][a]
u0, _ = plane_uv(st, a)
k = k or 1.0
lab.move_smooth((p["w"] / 2 - u0 + OVERSHOOT / 2) / k, 0)  # past the edge by half the overshoot
st = lab.state()
u1, _ = plane_uv(st, a)
check("pushing past the right edge clamps the cursor to the edge (still captured)",
      st["captured"] == a and abs(u1 - p["w"] / 2) < 2, f"u={u1:.1f} edge={p['w'] / 2:.1f} capture={st['capture']}")
# One continuous push from the clamped edge: the overshoot resets on any event without outward motion.
lab.move_smooth((OVERSHOOT + 10) / k, 0)
st = lab.state()
check("continued push past OVERSHOOT (60 dp) releases the cursor out of the right edge",
      st["captured"] is None and "overshoot" in (st["capture"] or ""), f"capture={st['capture']} hover={st['hover']} cursor={st['cursor']}")
lab.shot("overshoot_exit")

# --- Drag-select never exits: press inside, drag far past the edge, release.
st = lab.cursor_to(*lab.panel_point(st, a, 0, 0), st)
lab.click()
log0 = lab.streamer_log_size()
lab.press()
lab.move_smooth(1500, 0, buttons=1)
st = lab.state()
held = st["captured"] == a
lab.release()
st2 = lab.state()
drags = mac_lines(log0, rf"would MOUSE drag window {a} ")
check("drag-select past the edge stays captured and clamped (MOUSE drag keeps flowing, x stays <= 1)",
      held and st2["captured"] == a and drags and all(float(re.search(r"at \(([\d.]+),", l)[1]) <= 1.0 for l in drags),
      f"captured while held={held} after release={st2['capture']}; {len(drags)} drags, last {drags[-1:]}")

# --- Right click and scroll inside content.
log0 = lab.streamer_log_size()
lab.click(2)
lab.scroll(-3)
lab.scroll(0.4)
lab.scroll(0.4)
time.sleep(0.3)
rd = mac_lines(log0, rf"would MOUSE down right window {a} ")
sc = mac_lines(log0, rf"would SCROLL window {a} ")
check("two-finger click sends a right-button MOUSE down", bool(rd), f"{rd[-1:]}")
check("scroll in content sends SCROLL lines (fractions carried over)", len(sc) >= 2,
      " | ".join(sc[-4:]))

# --- Look-to-jump is disabled on purpose (it made the cursor "bounce" after pauses): captured in A, look at B,
# rest > 0.5 s, plain move -> the cursor stays in A (still captured), nothing is clicked.
lab.head(math.degrees(st["panels"][b]["theta"]), 0)
before = lab.state()
lab.keepalive(False)
time.sleep(1.0)
log0 = lab.streamer_log_size()
t0 = time.time()
lab.move_smooth(3, 0)
lab.keepalive(True)
st = lab.state()
caps = lab.logs(t0, r"capture: ")
check("no look-to-jump: after a pause + plain move the cursor stays where it was (not on the looked-at window)",
      st["hover"] and st["hover"]["id"] == a and abs(st["cursor"]["theta"] - before["cursor"]["theta"]) < 0.02,
      f"before {before['cursor']} after {st['cursor']} hover={st['hover']}")
check("no look-to-jump: capture is kept", st["captured"] == a and not any("look" in c for c in caps),
      f"capture={st['capture']} new capture lines={caps}")
downs = mac_lines(log0, r"would MOUSE down", timeout=0.5)
check("no click / Mac focus change from the pause + move", not downs, f"{downs}")
lab.shot("after_pause_move")

# --- Cursor image over content: Mac I-beam drawn at the window's scale.
lab.cursor_image("ibeam")
st = lab.cursor_to(*lab.panel_point(st, b, 0, 0), st)
lab.head(math.degrees(st["panels"][b]["theta"]), 0)
path = lab.shot("ibeam_over_content")
print(f"      (look: I-beam cursor at screen center, sized like a {16}-pt Mac cursor on that window) {path}")
lab.cursor_image("arrow")
lab.head(0, 0)
finish()
