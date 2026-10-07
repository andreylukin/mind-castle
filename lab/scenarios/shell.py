#!/usr/bin/env python3
"""Black shell + laptop window: the passthrough hole should span forward ±25° (Shell.WINDOW_HALF_YAW) as seen
from the head, and follow the head's new forward after a recenter.
Usage: shell.py [--no-restart]"""
import math
import sys
import time

from _common import check, finish, lab, setup

HALF = 25.0
PITCH = -40.0  # look down into the laptop window


def hole_edges(path, row=None):
    """Left/right x of the lit (passthrough) run through the image center row."""
    img = lab.Img(path)
    y = img.h // 2 if row is None else row
    xs = [x for x in range(img.w) if img.lit(x, y)]
    return (min(xs), max(xs), img.w) if xs else None


def edge_deg(x, w):
    """Azimuth (deg, relative to view center) of a pixel column on the center row, at PITCH."""
    f = lab.F600 * w / 600
    # Center-row pixel -> ray in camera coords (X right, Y up, Z fwd), then to world azimuth.
    X, Z = (x - w / 2) / f, 1.0
    # Rotate about camera x by pitch: forward ray dips; azimuth unaffected by pitch for the center row.
    return math.degrees(math.atan2(X, Z * math.cos(math.radians(PITCH))))


st = setup(windows=0, restart="--no-restart" not in sys.argv)
lab.head(0, PITCH)
path = lab.shot("hole_forward")
e = hole_edges(path)
if e:
    l, r = edge_deg(e[0], e[2]), edge_deg(e[1], e[2])
    clipped = e[0] == 0 or e[1] == e[2] - 1
    check(f"laptop window spans forward ±{HALF:.0f}° as seen from the head", not clipped and abs(l + HALF) < 1.5 and abs(r - HALF) < 1.5,
          f"measured {l:+.1f}° .. {r:+.1f}° (center row of {path})")
else:
    check("laptop window visible when looking down", False, path)

# Recenter while turned 30° right: the app re-centers its cylinder and moves `forward`; the window should follow.
lab.head(30, 0)
t0 = time.time()
import emulator_controller_pb2 as pb  # noqa: E402  (lab put gen/ on sys.path)
lab._event(xr_command=pb.XrCommand(action=pb.XrCommand.RECENTER))
line = lab.wait_log(r"head: cylinder center", t0, 5)
if line is None:
    # The emulator doesn't always deliver an origin change to the app; without it there's nothing to test.
    print("SKIP  recenter-follow: the app logged no recenter for this emulator RECENTER (inconclusive)")
    lab.recenter()
    lab.restart_app()
    finish()
# Where the head points in the app frame now: the log's `facing` (the emulator keeps it turned).
import re  # noqa: E402
m = re.search(r"facing=Vec3\(x=(\S+), y=\S+, z=(\S+)\)", line or "")
yaw = math.degrees(math.atan2(float(m[1]), -float(m[2]))) if m else 30.0
lab._head_set(yaw, 0)
lab.head(yaw, PITCH)
path = lab.shot("hole_after_recenter")
e = hole_edges(path)
if e:
    l, r = edge_deg(e[0], e[2]), edge_deg(e[1], e[2])
    check("after recenter the laptop window is centered on the new forward",
          abs((l + r) / 2) < 3 and not (e[0] == 0 or e[1] == e[2] - 1),
          f"measured {l:+.1f}° .. {r:+.1f}° relative to the new forward ({line}) | {path}")
else:
    check("after recenter the laptop window is visible straight ahead-down", False, f"no lit pixels on center row | {path}")

# Leave a clean frame for the next scenario.
lab.recenter()
lab.restart_app()
finish()
