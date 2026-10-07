#!/usr/bin/env python3
"""v7 focus-follow + overlays (fake backend: inject {"type":"focus"} / {"type":"overlay"}).
FOCUS_CHANGED for a shown window makes it active, flashes it and puts the cursor on its center; for a hidden
window it gets shown next to the active panel; far off the head yaw (> 35°) a ➤ arrow points at it.
OVERLAY shows a floating panel at the head's yaw in front of everything; hiding removes it; show/hide cycles
don't grow system_server input channels.
Usage: focus.py"""
import math
import time

from _common import check, finish, fmt, lab, setup, window_ids


def focus(pid):
    t0 = time.time()
    lab.inject({"type": "focus", "id": pid})
    line = lab.wait_log(r"focus-follow: ", t0, 5)
    time.sleep(0.8)
    return line, lab.state()


st = setup(windows=2)
a, b = window_ids(st)
c = next(i for i in lab.window_titles() if i not in (a, b))  # the one window not shown
lab.head(0, 0)

line, st = focus(b)
check("focus on a shown window: app logs focus-follow … active", line and str(b) in line and "active" in line, line)
check("…the cursor jumps onto it", st["hover"] and st["hover"]["id"] == b, f"hover={st['hover']} cursor={st['cursor']}")
lab.head(math.degrees(st["panels"][b]["theta"]), 0)
lab.shot("focus_shown_flash")

line, st = focus(c)
check("focus on a hidden window: it gets shown (focus-follow … shown)", line and str(c) in line and "shown" in line, line)
check("…and is now a panel", c in st["panels"], "; ".join(f"{i}: {fmt(p)}" for i, p in st["panels"].items()))
if c in st["panels"]:
    pc, pb = st["panels"][c], st["panels"][b]
    check("…placed next to the active panel (within 70° of it)", abs(math.degrees(pc["theta"] - pb["theta"])) < 70,
          f"{c}: {fmt(pc)} vs active {b}: {fmt(pb)}")
lab.head(0, 0)
lab.shot("focus_hidden_shown")

# Far off the head yaw: look 90° left, focus a, expect the arrow cue 1 m ahead (visual).
lab.head(-90, 0)
line, st = focus(a)
path = lab.shot("focus_far_arrow")
print(f"      (look: a ➤ arrow ~1 m ahead pointing toward window {a} at θ {math.degrees(st['panels'][a]['theta']):+.0f}°) {path}")
check("focus far off the head yaw still activates the window", line and str(a) in line, line)
lab.head(0, 0)

# Overlays: show/hide cycles, in front at the head's yaw, channels flat.
lab.head(30, 0)
n0 = lab.embedded_channels()
t0 = time.time()
lab.inject({"type": "overlay", "id": 9100, "app": "Raycast", "visible": True, "w": 1500, "h": 900})
time.sleep(2.5)
p_on = lab.shot("overlay_on")
lab.inject({"type": "overlay", "id": 9100, "visible": False})
time.sleep(1.5)
p_off = lab.shot("overlay_off")
on_l, off_l = lab.Img(p_on, 150), lab.Img(p_off, 150)
center = lambda im: sum(max(im.rgb(x, y)) for x in range(55, 95) for y in range(55, 95)) / 1600
check("overlay visible in front at the head's yaw, gone when hidden", center(on_l) > center(off_l) + 20,
      f"center brightness on {center(on_l):.0f} off {center(off_l):.0f} | {p_on} {p_off}")
olog = lab.logs(t0, r"overlay")
print("      app overlay log:", olog[-3:])
for _ in range(5):
    lab.inject({"type": "overlay", "id": 9100, "visible": True, "w": 1500, "h": 900})
    time.sleep(1.0)
    lab.inject({"type": "overlay", "id": 9100, "visible": False})
    time.sleep(0.8)
time.sleep(2)
n1 = lab.embedded_channels()
check("6 overlay show/hide cycles: input channels flat (one reused slot)", n1 - n0 <= 2, f"{n0} -> {n1}")
lab.head(0, 0)
finish()
