#!/usr/bin/env python3
"""Lying down: recenter re-anchors the whole layout to the head's full orientation (|pitch| < 15° snaps upright).
Start upright with 2 windows -> pitch the head +70° (looking at the ceiling) -> recenter via COMMAND -> the layout
is in front of the eyes, the cursor works, the shell is black with the cutout below-forward -> relaunch while
still pitched -> still anchored to the ceiling -> pitch back to 0 + recenter -> same geometry as at the start.
Also records what an emulator RECENTER does while pitched (observation only: it also resets the emulator head).
Usage: lying.py"""
import time

from _common import check, finish, lab, setup, window_ids

PITCH = 70.0


def mask(path, size=150, thresh=12):
    img = lab.Img(path, size)
    return [[img.lit(x, y, thresh) for x in range(img.w)] for y in range(img.h)]


def iou(a, b):
    inter = sum(1 for ra, rb in zip(a, b) for x, y in zip(ra, rb) if x and y)
    union = sum(1 for ra, rb in zip(a, b) for x, y in zip(ra, rb) if x or y)
    return inter / union if union else 1.0


def center_lit_fraction(m, frac=0.4):
    h, w = len(m), len(m[0])
    ys = range(int(h * (0.5 - frac / 2)), int(h * (0.5 + frac / 2)))
    xs = range(int(w * (0.5 - frac / 2)), int(w * (0.5 + frac / 2)))
    return sum(m[y][x] for y in ys for x in xs) / (len(ys) * len(xs))


def recenter_cmd():
    t0 = time.time()
    line = lab.command("recenter")
    cyl = lab.wait_log(r"head: cylinder center", t0, 6)
    time.sleep(1.5)
    return line, cyl


st = setup(windows=2)
lab.head(0, 0)
up0 = lab.shot("upright_start")
m0 = mask(up0)

# Look at the ceiling and recenter through the app (COMMAND = the headset's recenter path).
lab.head(0, PITCH)
before = lab.shot("pitched_before_recenter")
line, cyl = recenter_cmd()
check("COMMAND recenter accepted while pitched", line == "recenter" and cyl is not None, f"{line} | {cyl}")
p1 = lab.shot("pitched_after_recenter")
m1 = mask(p1)
check("after recenter at +70° the layout is in front of the eyes (screen center mostly content)",
      center_lit_fraction(m1) > 0.35, f"center lit fraction {center_lit_fraction(m1):.2f} | {p1}")
check("the pitched view matches the upright layout geometry (lit-mask IoU with the upright shot ≥ 0.7)",
      iou(m0, m1) >= 0.7, f"IoU {iou(m0, m1):.2f} | {up0} vs {p1}")

# Cursor still works: sweep across, click into a window, capture starts.
st = lab.state()
a = window_ids(st)[0]
st = lab.cursor_to(*lab.panel_point(st, a, 0, 0), st)
t0 = time.time()
lab.click()
cap = lab.wait_log(r"capture: start", t0, 5)
check("cursor click lands on a window while lying down", cap is not None and str(a) in cap, f"{cap} hover={st['hover']}")
lab.move_smooth(0, 2000)  # leave the window downward, toward the cutout
lab.shot("pitched_cursor_out")

# Relaunch while still pitched: still anchored to the ceiling.
lab.restart_app()
lab.wait_connected()
time.sleep(3)
p2 = lab.shot("pitched_after_relaunch")
check("relaunch while pitched keeps the layout anchored in front of the eyes (IoU with pre-relaunch ≥ 0.7)",
      iou(m1, mask(p2)) >= 0.7, f"IoU {iou(m1, mask(p2)):.2f} | {p2}")

# Emulator RECENTER while pitched (observation).
import emulator_controller_pb2 as pb  # noqa: E402
t0 = time.time()
lab._event(xr_command=pb.XrCommand(action=pb.XrCommand.RECENTER))
emu = lab.wait_log(r"head: cylinder center", t0, 6)
time.sleep(1.5)
pe = lab.shot("pitched_after_emulator_recenter")
print(f"      observation: emulator RECENTER while pitched -> app log {emu!r}; shot {pe}")
# The emulator resets its head to neutral on RECENTER: bring bookkeeping in line, then put the head back at +70.
lab._head_set(0, 0)

# Back upright: pitch 0 + recenter -> same as the start.
lab.head(0, 0)
line, cyl = recenter_cmd()
up1 = lab.shot("upright_end")
check("pitch back to 0 + recenter: upright layout identical to the start (lit-mask IoU ≥ 0.85)",
      iou(m0, mask(up1)) >= 0.85, f"IoU {iou(m0, mask(up1)):.2f} | {up0} vs {up1} | {cyl}")
st = lab.state()
check("upright again: cylinder facing is level (|y| of facing < 0.26 ≈ 15°)", cyl and abs(float(
    __import__("re").search(r"facing=Vec3\(x=\S+, y=(\S+),", cyl)[1])) < 0.26, cyl)
finish()
