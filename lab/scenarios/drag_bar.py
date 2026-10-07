#!/usr/bin/env python3
"""Moving panels with the trackpad: grab-bar drag (yaw + height), two-finger depth scroll on the bar, corner
resize, and the paths a real user takes to get there (from a captured window, after a pause, while looking
elsewhere). Also checks that each visible grab bar is drawn where its hit zone is.
Usage: drag_bar.py [--no-restart]"""
import math
import os
import sys
import time

from _common import check, finish, fmt, lab, setup, window_ids

R_PER_LINE = 0.03 * 800  # Desk.R_PER_LINE_M * dpPerMeter (800 on the emulator)


def bar_drawn_at_hit_zone(st, pid, tag):
    """Look at panel [pid]'s bar and check a lit pixel sits where Desk hit-tests the bar."""
    p = st["panels"][pid]
    bar_y = p["y"] + lab.bar_v(p)
    pitch = math.degrees(math.atan2(bar_y, p["r"]))
    over_passthrough = abs(math.degrees(p["theta"])) < 25 and pitch < -15
    if over_passthrough:
        return None  # passthrough behind the bar: a lit pixel proves nothing
    lab.head(math.degrees(p["theta"]), pitch)
    path = lab.shot(f"bar_{tag}_{pid}")
    img = lab.Img(path)
    at = lab.project_panel(p, 0, lab.bar_v(p))
    # Also probe the gap between the panel's bottom edge and the bar: should be dark.
    gap = lab.project_panel(p, 0, -p["h"] / 2 - 7)
    ok = lab.lit_near(img, at, 2) and not lab.lit_near(img, gap, 0)
    return check(f"[{tag}] panel {pid}: grab bar drawn where its hit zone is", ok,
                 f"{fmt(p)}; Desk bar center projects to px {tuple(round(c) for c in at)} "
                 f"lit={lab.lit_near(img, at, 2)}, gap px {tuple(round(c) for c in gap)} lit={lab.lit_near(img, gap, 0)} | {path}")


def to_bar(st, pid):
    p = st["panels"][pid]
    return lab.cursor_to(*lab.panel_point(st, pid, 0, lab.bar_v(p)), st)


def to_content(st, pid, fu=0.0, fv=0.0):
    p = st["panels"][pid]
    return lab.cursor_to(*lab.panel_point(st, pid, fu * p["w"] / 2, fv * p["h"] / 2), st)


st = setup(windows=3, restart="--no-restart" not in sys.argv)
ids = window_ids(st)
a, b = ids[0], ids[1]
lab.head(0, 0)

# --- 1. Bars drawn where they hit-test (fresh layout, nothing hovered yet).
for pid in [0] + ids:
    bar_drawn_at_hit_zone(st, pid, "fresh")
lab.head(0, 0)

# --- 2. Baseline: bar drag with keepalive (no idle), mid-drag the panel follows.
st = to_bar(st, a)
check("cursor on panel's grab bar hit-tests as BAR", st["hover"] and st["hover"]["id"] == a and st["hover"]["zone"] == "BAR",
      f"hover={st['hover']} cursor={st['cursor']}")
p0 = st["panels"][a]
lab.press()
lab.move_smooth(-150, -100, buttons=1)
mid = lab.state()
lab.head(math.degrees(mid["panels"][a]["theta"]), math.degrees(math.atan2(mid["panels"][a]["y"], mid["panels"][a]["r"])))
path = lab.shot("drag_mid")
img = lab.Img(path)
pm = mid["panels"][a]
pts = [lab.project(pm["theta"], pm["y"], pm["r"], u) for u in (-pm["w"] * 0.4, 0, pm["w"] * 0.4)]
check("mid-drag (button held): the panel is drawn at its new Desk position", all(lab.lit_near(img, q, 1) for q in pts),
      f"{fmt(p0)} -> {fmt(pm)}; probes {[tuple(round(c) for c in q) for q in pts]} | {path}")
lab.release()
st = lab.state()
p1 = st["panels"][a]
check("bar drag moves the panel along the cylinder (Δθ = dx/r, Δy = -dy)",
      abs((p1["theta"] - p0["theta"]) - (-150 / p0["r"])) < 0.01 and abs((p1["y"] - p0["y"]) - 100) < 5,
      f"{fmt(p0)} -> {fmt(p1)} (expected Δθ {-150 / p0['r']:+.3f}, Δy +100)")
lab.head(0, 0)
path = lab.shot("after_drag")

# --- 3. Outline colour after release (visual: should return to the idle colour).
lab.head(math.degrees(p1["theta"]), 0)
path = lab.shot("after_release_outline")
print(f"      (look: hover outline should be white/grey, not drag-blue, after release) {path}")
lab.head(0, 0)

# --- 4. Real-user path A: pause on the bar (>0.5 s idle), look at the same panel, then press-drag.
st = lab.state()
st = to_bar(st, a)
p0 = st["panels"][a]
lab.head(math.degrees(p0["theta"]), 0)
lab.keepalive(False)
time.sleep(1.0)
lab.drag(120, 0)
lab.keepalive(True)
st = lab.state()
p1 = st["panels"][a]
check("pause on bar, look at the same panel, press-drag: panel moves",
      abs((p1["theta"] - p0["theta"]) - 120 / p0["r"]) < 0.01, f"{fmt(p0)} -> {fmt(p1)} hover={st['hover']}")

# --- 5. Real-user path B: pause on the bar, look at the neighbour (where you want it to go), then press-drag.
st = to_bar(st, a)
p0 = st["panels"][a]
pb0 = st["panels"][b]
lab.head(math.degrees(pb0["theta"]), 0)
lab.keepalive(False)
time.sleep(1.0)
t0 = time.time()
log0 = lab.streamer_log_size()
lab.drag(150, 0)
lab.keepalive(True)
st = lab.state()
p1 = st["panels"][a]
caps = lab.logs(t0, r"capture: ")
macs = [l for l in lab.streamer_log(log0).splitlines() if "would MOUSE down" in l]
check("pause on bar, glance at another window, press-drag: the panel under the cursor moves (not the looked-at one)",
      abs((p1["theta"] - p0["theta"]) - 150 / p0["r"]) < 0.01,
      f"{a}: {fmt(p0)} -> {fmt(p1)}; hover after={st['hover']}; capture log={caps[-1:] if caps else None}; "
      f"Mac MOUSE down={macs[-1:] if macs else None}")
lab.head(0, 0)

# --- 6. Real-user path C: working in a window (captured), then move down onto its bar and drag.
st = lab.state()
st = to_content(st, a, 0, 0)
t0 = time.time()
lab.click()
st = lab.state()
check("click in content captures the cursor", st["captured"] == a, f"capture={st['capture']}")
p0 = st["panels"][a]
k = p0["w"] / lab.WINDOW_PTS[a] if a in lab.WINDOW_PTS else 0.8  # dp per window point (native gain)
# Straight down: to the bottom edge, then OVERSHOOT (60 dp) + 8 dp more of push.
cv = st["cursor"]["t"] * p0["r"] - p0["y"]
lab.move_smooth(0, (cv + p0["h"] / 2 + 60 + 8) / k)
st = lab.state()
check("pushing down out of a captured window lands on its grab bar", st["hover"] and st["hover"]["zone"] == "BAR"
      and st["hover"]["id"] == a and st["captured"] is None, f"hover={st['hover']} capture={st['capture']} cursor={st['cursor']}")
lab.drag(-100, 0)
st = lab.state()
p1 = st["panels"][a]
check("...and dragging from there moves the panel", abs((p1["theta"] - p0["theta"]) - (-100 / p0["r"])) < 0.01,
      f"{fmt(p0)} -> {fmt(p1)}")
# A real swipe doesn't stop exactly at the threshold: 40 more points of the same downward swipe.
st = to_content(st, a, 0, 0)
lab.click()
lab.move_smooth(0, (p0["h"] / 2 + 60 + 8) / k + 40)
st = lab.state()
check("UX: a swipe that overshoots the exit by 40 pt still ends on the grab bar", st["hover"] and st["hover"]["zone"] == "BAR",
      f"hover={st['hover']} cursor={st['cursor']} capture={st['capture']}")

# --- 7. Depth: two-finger scroll on the bar.
st = to_bar(st, a)
p0 = st["panels"][a]
lab.scroll(3)
st = lab.state()
p1 = st["panels"][a]
check("scroll up 3 lines on the bar pushes the panel away by 3 × 3 cm", abs((p1["r"] - p0["r"]) - 3 * R_PER_LINE) < 2,
      f"{fmt(p0)} -> {fmt(p1)} (expected Δr +{3 * R_PER_LINE:.0f} dp)")
check("cursor stays on the bar while the panel recedes", st["hover"] and st["hover"]["zone"] == "BAR", f"hover={st['hover']}")
lab.scroll(-3)
st = lab.state()
check("scroll down 3 lines brings it back", abs(st["panels"][a]["r"] - p0["r"]) < 2, fmt(st["panels"][a]))

# --- 8. Corner resize (top-right), aspect kept, opposite corner fixed.
st = lab.state()
p0 = st["panels"][a]
st = lab.cursor_to(*lab.panel_point(st, a, p0["w"] / 2 + 6, p0["h"] / 2 + 6), st)
check("cursor at the top-right corner hit-tests as CORNER(+1,+1)",
      st["hover"] == {"id": a, "zone": "CORNER", "cx": 1, "cy": 1}, f"hover={st['hover']}")
lab.drag(100, -60)
st = lab.state()
p1 = st["panels"][a]
left0 = p0["theta"] - math.atan(p0["w"] / 2 / p0["r"])
left1 = p1["theta"] - math.atan(p1["w"] / 2 / p1["r"])
bot0, bot1 = p0["y"] - p0["h"] / 2, p1["y"] - p1["h"] / 2
check("corner drag grows the panel with aspect kept",
      p1["w"] > p0["w"] + 50 and abs(p1["h"] / p1["w"] - p0["h"] / p0["w"]) < 0.01, f"{fmt(p0)} -> {fmt(p1)}")
check("opposite (bottom-left) corner stays put", abs(left1 - left0) < 0.01 and abs(bot1 - bot0) < 5,
      f"left edge θ {left0:.3f} -> {left1:.3f}, bottom y {bot0:.0f} -> {bot1:.0f}")
lab.head(math.degrees(p1["theta"]), 0)
lab.shot("after_resize")
lab.head(0, 0)

# --- 9. Bars stay attached when a panel's size changes from the Mac side (fake backend: resize a window).
st = lab.state()
for pid in [0] + window_ids(st):
    bar_drawn_at_hit_zone(st, pid, "after-interaction")
if lab.BACKEND == "fake" or os.environ.get("LAB_BACKEND") == "fake":
    lab.head(0, 0)
    lab.inject({"type": "fake_resize", "id": b, "w": 1600, "h": 900})
    time.sleep(2.5)
    st = lab.state()
    bar_drawn_at_hit_zone(st, b, "after-mac-resize")
    lab.inject({"type": "fake_resize", "id": b, "w": 1600, "h": 1344})  # put it back for later scenarios
lab.head(0, 0)
finish()
