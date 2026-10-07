#!/usr/bin/env python3
"""Facing: every panel squarely faces the viewer. Geometry from state() plus a visual check: look straight at
each panel (head yaw = panel θ) and compare the panel's left/right edge heights on screen.
Repeats after moving a panel with its grab bar (panels are re-oriented when dropped).
Usage: facing.py [--no-restart]"""
import math
import sys

from _common import check, finish, fmt, lab, setup, window_ids

TOL = 0.03  # 3% edge-height asymmetry ~ 2-3 degrees of yaw error for a laptop-sized panel at 1.2 m


def visual(st, pid, tag):
    p = st["panels"][pid]
    deg = math.degrees(p["theta"])
    # Look at the panel's center (pitch toward its height) so perspective is symmetric left/right.
    pitch = math.degrees(math.atan2(p["y"], p["r"]))
    lab.head(deg, pitch)
    path = lab.shot(f"{tag}_{pid}_yaw{deg:+.0f}")
    m = lab.measure_panel(lab.Img(path))
    if m is None:
        return check(f"[{tag}] panel {pid} visible at screen center when looking at it", False, f"{path}: center pixel dark")
    # Use the upper halves of the edges (center row -> top edge): the laptop cutout can sit right under a panel
    # and merge with its bottom edge in the lit mask.
    cy = lab.Img(path).h // 2
    lh, rh = cy - m["left"][0], cy - m["right"][0]
    m["ratio"] = lh / rh if rh else 0
    ok = abs(m["ratio"] - 1) <= TOL and abs(m["center_offset"]) <= 0.03 and not m["clipped"]
    # Top vs bottom width: equal if the panel also tilts toward the eye; an upright panel above eye level
    # has its top edge farther away (narrower). Reported separately: Desk only yaws panels.
    tilt = math.degrees(math.atan2(p["y"], p["r"]))
    if abs(tilt) > 5:
        check(f"[{tag}] panel {pid} at {tilt:+.0f}° elevation tilts to face the eye (top width == bottom width)",
              abs(m["ratio_tb"] - 1) <= TOL, f"top_w={m['top_w']} bottom_w={m['bottom_w']} ratio={m['ratio_tb']:.3f} | {path}")
    # Positive yaw error = panel's left edge nearer (taller) than its right.
    err = math.degrees(math.asin(max(-1, min(1, (m["ratio"] - 1) / (m["ratio"] + 1) * 2 * p["r"] / p["w"]))))
    return check(f"[{tag}] panel {pid} faces the viewer (left/right edge heights equal)", ok,
                 f"{fmt(p)} | upper left_h={lh} right_h={rh} ratio={m['ratio']:.3f} "
                 f"(~{err:+.1f}° yaw error) center_offset={m['center_offset']:+.3f} | {path}")


st = setup(windows=3, restart="--no-restart" not in sys.argv)
try:
    # Geometry: Desk keeps each panel on the cylinder around the head and yawed by -θ (faces the axis).
    c = st["center"]
    check("cylinder center is the head position (x≈0 at neutral head)", c and abs(c[0]) < 1, f"center={c}")
    for pid in [0] + window_ids(st):
        p = st["panels"][pid]
        check(f"panel {pid} radius within 0.5..2.8 m", 0.5 * 800 <= p["r"] <= 2.8 * 800, fmt(p))
    # Park the cursor on the picker so hover outlines don't touch the measured panels.
    st = lab.cursor_to(*lab.panel_point(st, 0, 0, 0), st)
    for pid in window_ids(st) + [0]:
        visual(st, pid, "initial")
    # Move the first window with its bar (up, clear of the picker and its neighbour) and check it still faces the viewer.
    pid = window_ids(st)[0]
    lab.head(0, 0)
    p = st["panels"][pid]
    st = lab.cursor_to(*lab.panel_point(st, pid, 0, lab.bar_v(p)), st)
    check("cursor on the panel's grab bar", st["hover"] and st["hover"]["zone"] == "BAR", f"hover={st['hover']} cursor={st['cursor']}")
    lab.drag(0, -300)
    st = lab.state()
    st = lab.cursor_to(*lab.panel_point(st, 0, 0, 0), st)
    check("drag moved the panel", abs(st["panels"][pid]["y"] - p["y"]) > 100, f"{fmt(p)} -> {fmt(st['panels'][pid])}")
    visual(st, pid, "moved")
finally:
    lab.head(0, 0)
finish()
