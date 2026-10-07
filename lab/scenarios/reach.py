#!/usr/bin/env python3
"""Reach + tidy: the device layout that got the user stuck: one window far round the arc (θ≈+115°) and one
~35° above eye level. From the picker, plain cursor moves alone must reach both (hover on their content).
Then ⌃⌥⌘T tidy (COMMAND "tidy") must bring every panel into one row in front.
Usage: reach.py"""
import math
import time

from _common import check, finish, fmt, lab, setup, window_ids


def capture(st, pid):
    st = lab.cursor_to(*lab.panel_point(st, pid, 0, 0), st)
    lab.click()
    time.sleep(0.3)
    return lab.state()


def nudge_to(st, pid, theta_deg=None, y=None):
    """Move window [pid] with nudge COMMANDs (it must be the active = captured panel)."""
    p = st["panels"][pid]
    if theta_deg is not None:
        steps = round((theta_deg - math.degrees(p["theta"])) / 5)
        for _ in range(abs(steps)):
            lab.command("nudge", dtheta=1 if steps > 0 else -1)
    if y is not None:
        steps = round((y - p["y"]) / 40)
        for _ in range(abs(steps)):
            lab.command("nudge", dy=1 if steps > 0 else -1)
    time.sleep(0.5)
    return lab.state()


def release_capture(st, pid):
    """Leave the captured window through its bottom edge (overshoot exit)."""
    p = st["panels"][pid]
    k = p["w"] / lab.WINDOW_PTS[pid] if pid in lab.WINDOW_PTS else 0.8
    cv = st["cursor"]["t"] * p["r"] - p["y"]
    lab.move_smooth(0, (cv + p["h"] / 2 + 70) / k)
    return lab.state()


st = setup(windows=3)
a, b, c = window_ids(st)
# Arrange the stuck layout: b far right (θ≈115°), c high (≈35° above eye level).
lab.head(math.degrees(st["panels"][b]["theta"]), 0)
st = capture(st, b)
st = nudge_to(st, b, theta_deg=115)
st = release_capture(st, b)
lab.head(math.degrees(st["panels"][c]["theta"]), 0)
st = capture(st, c)
yc = st["panels"][c]["r"] * math.tan(math.radians(35))
st = nudge_to(st, c, y=yc)
st = release_capture(st, c)
check("layout arranged: one window at θ≈115°, one ≈35° above eye level",
      abs(math.degrees(st["panels"][b]["theta"]) - 115) < 3 and abs(math.degrees(math.atan2(st["panels"][c]["y"], st["panels"][c]["r"])) - 35) < 3,
      f"{b}: {fmt(st['panels'][b])}; {c}: {fmt(st['panels'][c])}")
lab.head(0, 0)
lab.shot("stuck_layout")

for target, what in ((b, "θ≈+115°"), (c, "35° above eye level")):
    st = lab.cursor_to(*lab.panel_point(st, 0, 0, 0), st)  # start on the picker
    check(f"[{what}] start on the picker", st["hover"] and st["hover"]["id"] == 0, f"hover={st['hover']}")
    p = st["panels"][target]
    st = lab.cursor_to(*lab.panel_point(st, target, 0, 0), st, tries=5)
    check(f"[{what}] plain moves from the picker reach window {target}'s content",
          st["hover"] and st["hover"]["id"] == target and st["hover"]["zone"] == "CONTENT",
          f"hover={st['hover']} cursor={st['cursor']} target {fmt(p)}")
    lab.head(math.degrees(p["theta"]), math.degrees(math.atan2(p["y"], p["r"])))
    lab.shot(f"reached_{target}")
    lab.head(0, 0)

# Tidy: everything in one row in front.
line = lab.command("tidy")
time.sleep(1.0)
st = lab.state()
ws = [st["panels"][i] for i in (a, b, c)]
spans = sorted((p["theta"] - math.atan(p["w"] / 2 / p["r"]), p["theta"] + math.atan(p["w"] / 2 / p["r"])) for p in ws)
overlap = any(spans[i][1] > spans[i + 1][0] + 1e-3 for i in range(len(spans) - 1))
check("tidy: all windows in one row (same height)", max(p["y"] for p in ws) - min(p["y"] for p in ws) < 5,
      "; ".join(fmt(p) for p in ws) + f" | app: {line}")
check("tidy: the row is in front (all window centers within ±60° of forward)", all(abs(math.degrees(p["theta"])) <= 60 for p in ws),
      "; ".join(f"{math.degrees(p['theta']):+.0f}°" for p in ws))
check("tidy: no overlaps along the arc", not overlap, str([(round(math.degrees(x0)), round(math.degrees(x1))) for x0, x1 in spans]))
lab.head(0, 0)
lab.shot("after_tidy")
time.sleep(1.1)
line = lab.command("undo")
st = lab.state()
check("tidy is undoable", abs(math.degrees(st["panels"][b]["theta"]) - 115) < 3, f"{fmt(st['panels'][b])} | {line}")
finish()
