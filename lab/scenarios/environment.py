#!/usr/bin/env python3
"""Environment (PROTOCOL v4 feature 4): dim 5..0 blends the shell from black to clear, passthrough toggle hides
it, cutout commands resize the laptop window; plus a banding check where shell pieces overlap.
Usage: environment.py"""
import math
import time

from _common import check, finish, lab, setup

VIEW = (-110.0, 10.0)  # yaw/pitch with only shell (no panels, no cutout) in view


def mean_luma(img, box=None):
    x0, y0, x1, y1 = box or (0, 0, img.w, img.h)
    tot = n = 0
    for y in range(y0, y1, 2):
        for x in range(x0, x1, 2):
            r, g, b = img.rgb(x, y)
            tot += 0.2126 * r + 0.7152 * g + 0.0722 * b
            n += 1
    return tot / n


def row_profile(img, y):
    return [sum(img.rgb(x, y)) / 3 for x in range(img.w)]


def seams(img, rows=(100, 150, 200)):
    """Narrow vertical lines that are darker (or brighter) than both sides in every sampled row: overlap bands."""
    hits = None
    for y in rows:
        pr = row_profile(img, y)
        cand = set()
        for x in range(4, img.w - 4):
            side = (pr[x - 4] + pr[x + 4]) / 2
            if side > 20 and abs(pr[x] - side) > 0.12 * side and abs(pr[x - 4] - pr[x + 4]) < 0.05 * side:
                cand.add(x // 2)
        hits = cand if hits is None else hits & cand
    return sorted(hits or [])


def env(cmd, **kw):
    line = lab.command(cmd, **kw)
    time.sleep(0.8)
    return line


st = setup(windows=0)
start = env("dim", d=0)
check("env command answers with the current env", start and "dim=5" in start, start)

lab.head(*VIEW)
lumas = {}
paths = {}
for level in (5, 4, 3, 2, 1, 0):
    if level < 5:
        env("dim", d=-1)
    paths[level] = lab.shot(f"dim{level}")
    lumas[level] = mean_luma(lab.Img(paths[level], 300))
print("      mean luma by dim level:", {k: round(v, 1) for k, v in lumas.items()})
check("dim 5 is (near) pure black", lumas[5] < 3, f"luma {lumas[5]:.1f} | {paths[5]}")
check("dim 0 shows the room (bright)", lumas[0] > 40, f"luma {lumas[0]:.1f} | {paths[0]}")
mono = all(lumas[k] < lumas[k - 1] for k in range(5, 0, -1))
check("each dim step lets more room through (monotonic)", mono, str({k: round(v, 1) for k, v in lumas.items()}))
lin = [abs(lumas[k] / max(lumas[0], 1) - (1 - k / 5)) for k in range(6)]
check("dim steps are roughly even (luma ∝ 1 - level/5, ±0.12)", max(lin) < 0.12, f"deviation {[round(d, 2) for d in lin]}")
room_seams = set(seams(lab.Img(paths[0], 600)))  # lines in the room itself (planks, door frames) at dim 0
for level in (4, 3, 2, 1):
    sm = [x for x in seams(lab.Img(paths[level], 600)) if not any(abs(x - r) <= 2 for r in room_seams)]
    check(f"dim {level}: no banding / double-alpha lines where shell pieces overlap", not sm,
          f"{len(sm)} seam columns at x≈{sm[:12]} (600 px) | {paths[level]}")
env("dim", d=5)

# "Show the room" hides the shell entirely, toggling back restores it.
line = env("passthrough")
p = lab.shot("passthrough_on")
l_on = mean_luma(lab.Img(p, 300))
check("passthrough toggle: shell hidden (room as bright as dim 0)", "passthrough=true" in (line or "") and abs(l_on - lumas[0]) < 0.15 * lumas[0] + 3,
      f"luma {l_on:.1f} vs dim0 {lumas[0]:.1f} | {line} | {p}")
line = env("passthrough")
p = lab.shot("passthrough_off")
check("passthrough toggled back: black again", "passthrough=false" in (line or "") and mean_luma(lab.Img(p, 300)) < 3, p)

# Cutout: measure the laptop window looking down, widen by 2 presses (+5° each side total 10°), raise top 2 presses.
PITCH = -40.0


def hole(path):
    img = lab.Img(path, 600)
    y = img.h // 2
    xs = [x for x in range(img.w) if img.lit(x, y)]
    if not xs:
        return None
    f = lab.F600
    az = [math.degrees(math.atan2((x - img.w / 2) / f, math.cos(math.radians(PITCH)))) for x in (min(xs), max(xs))]
    col = img.w // 2 + 40  # top edge: walk up a column right of center (the center has the v7 cursor ray)
    yy = y
    while yy > 0 and img.lit(col, yy - 1):
        yy -= 1
    top = PITCH + math.degrees(math.atan((img.h / 2 - yy) / f)) if img.lit(col, y) else None
    return az[0], az[1], top


lab.head(0, PITCH)
h0 = hole(lab.shot("cutout_default"))
env("cutout", dw=1)
env("cutout", dw=1)
h1 = hole(lab.shot("cutout_wider"))
check("cutout dw +2: laptop window 10° wider (5° each side)", h0 and h1 and abs((h1[1] - h1[0]) - (h0[1] - h0[0]) - 10) < 2,
      f"{h0} -> {h1}")
env("cutout", dh=1)
env("cutout", dh=1)
h2 = hole(lab.shot("cutout_taller"))
check("cutout dh +2: top edge 10° higher", h1 and h2 and h1[2] is not None and h2[2] is not None and abs(h2[2] - h1[2] - 10) < 2.5,
      f"top {h1[2] if h1 else None} -> {h2[2] if h2 else None}")
env("cutout", dw=-2)
env("cutout", dh=-2)
lab.head(0, 0)
finish()
