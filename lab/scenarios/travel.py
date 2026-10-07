#!/usr/bin/env python3
"""Cursor travel / boundary crossing: how many realistic trackpad swipes it takes to get around.

Swipes are POINTER bursts at ~120 Hz with a 150 ms finger-lift gap after each:
  slow   300 pt/s for 0.5 s  (150 pt)
  normal 1200 pt/s for 0.4 s (480 pt)
  flick  3000 pt/s for 0.25 s (750 pt)
The simulated user aims at the target's center and lifts the finger as soon as the cursor is on the target
(predicted per message with the app's gain + acceleration model, TRAVEL_ACCEL=0 for older builds). After each swipe the
app's state is read; the path is done when the cursor is on the target. Pass: ≤ 2 normal swipes, ≤ 1 flick.

Layout D mirrors the user's device layout (12:43): picker θ -1.301; A θ -0.671 1289 wide at r≈1360;
B θ +0.245 same; C θ -0.104 r≈2080 28° below eye level.
Usage: travel.py [--quick]"""
import math
import os
import sys
import time

from _common import arrange, check, finish, lab, release_capture, setup, window_ids, capture_window, commands

QUICK = "--quick" in sys.argv
PROFILES = {"slow": (300.0, 0.5), "normal": (1200.0, 0.4), "flick": (3000.0, 0.25)}
LIMIT = {"slow": 4, "normal": 2, "flick": 1}
MAX_SWIPES = 10
HZ = 120.0
ROWS = []  # (section, path, kind, swipes, seconds, ok, note)


ACCEL = os.environ.get("TRAVEL_ACCEL", "1") != "0"  # model the app's pointer acceleration (build ≥ 2026-10-07)


def accel_factor(speed):
    """Desk Accel: 1× up to 400 pt/s, linear to 3.5× at 2500 pt/s, flat beyond."""
    if not ACCEL or speed <= 400:
        return 1.0
    return 1.0 + 2.5 * min(1.0, (speed - 400) / 2100)


def zone_at(panels, theta, t):
    """(id, 'CONTENT'|'OTHER') of the nearest panel under (theta, t) using Desk's plane model, or (None, None)."""
    best = (None, None, 1e9)
    for pid, p in panels.items():
        d = wrap(theta - p["theta"])
        if math.cos(d) < 0.3:
            continue
        u, v = p["r"] * math.tan(d), p["r"] * t - p["y"]
        if abs(u) <= p["w"] / 2 and abs(v) <= p["h"] / 2 and p["r"] < best[2]:
            best = (pid, "CONTENT" if pid else "OTHER", p["r"])
    return best[0], best[1]


def predicted_gain(panels, theta, t, speed):
    """dp per Mac point the app applies here at this speed (the user 'sees' this; we predict it to stop on time)."""
    pid, zone = zone_at(panels, theta, t)
    f = accel_factor(speed)
    if pid is None:
        return max(1.5, f) if ACCEL else 1.0
    if zone == "CONTENT":
        native = panels[pid]["w"] / lab.WINDOW_PTS[pid] if pid in lab.WINDOW_PTS else 0.8
        return native if f == 1.0 else f
    return f


def swipe(ux, uy, kind, length=None, buttons=None, stop=None, st=None):
    """One swipe along unit vector (ux, uy) in Mac points (y down), like a finger: constant speed at ~120 Hz.
    [stop](theta, t) -> bool lets the simulated user lift the finger once the (predicted) cursor is on target;
    without it the swipe runs its full length (or [length] points). Returns seconds spent."""
    speed, dur = PROFILES[kind]
    total = speed * dur if length is None else min(length, speed * dur)
    n = max(1, int(total / speed * HZ))
    step = total / n
    th, t = (st["cursor"]["theta"], st["cursor"]["t"]) if st else (0.0, 0.0)
    t0 = time.time()
    for i in range(n):
        lab.pointer(ux * step, uy * step, buttons)
        if st is not None:
            r = lab._cursor_r(st)
            g = predicted_gain(st["panels"], th, t, speed)
            th, t = wrap(th + ux * step * g / r), t - uy * step * g / r
            if stop and stop(th, t):
                break
        nxt = t0 + (i + 1) / HZ
        time.sleep(max(0.0, nxt - time.time()))
    time.sleep(0.15)  # finger lift
    return time.time() - t0


def wrap(a):
    return (a + math.pi) % (2 * math.pi) - math.pi


def on(st, pid, zones=("CONTENT", "BAR", "CORNER")):
    h = st.get("hover")
    return h is not None and h["id"] == pid and h["zone"] in zones


def aim(st, theta, t, allow_wrap=False):
    """Mac-point vector from the cursor to (theta, t), at the cursor's current radius (1 dp/pt)."""
    c = st["cursor"]
    r = lab._cursor_r(st)
    dth = wrap(theta - c["theta"]) if allow_wrap else theta - c["theta"]
    return dth * r, -(t - c["t"]) * r


def travel(st, target, kind, section, label, allow_wrap=False, start=None):
    """Swipe from the current cursor position to [target]'s center. Returns (state, swipes)."""
    p = st["panels"][target]
    goal = (p["theta"], p["y"] / p["r"])
    t_spent, swipes, stalls = 0.0, 0, 0
    while swipes < MAX_SWIPES:
        if on(st, target):
            break
        dx, dy = aim(st, *goal, allow_wrap=allow_wrap)
        dist = math.hypot(dx, dy)
        if dist < 1:
            break
        before = dict(st["cursor"])
        tgt = st["panels"][target]

        def reached(th, t, tgt=tgt):
            u = tgt["r"] * math.tan(wrap(th - tgt["theta"]))
            v = tgt["r"] * t - tgt["y"]
            return abs(u) <= 0.4 * tgt["w"] and abs(v) <= 0.4 * tgt["h"]
        # The user looks at the cursor and lifts when it's on the target (predicted with the app's gain model).
        t_spent += swipe(dx / dist, dy / dist, kind, stop=reached, st=st)
        swipes += 1
        st = lab.state()
        moved = math.hypot(wrap(st["cursor"]["theta"] - before["theta"]), st["cursor"]["t"] - before["t"])
        if moved < 1e-3:
            stalls += 1
    ok = on(st, target) and swipes <= LIMIT[kind] and stalls == 0
    note = "" if on(st, target) else f"not reached (cursor {st['cursor']}, hover {st['hover']})"
    if stalls:
        note += f" {stalls} stalled swipe(s)"
    ROWS.append((section, label, kind, swipes, round(t_spent, 2), ok, note.strip()))
    print(f"      {section:10s} {label:28s} {kind:6s} swipes={swipes:2d} {t_spent:5.1f}s {'ok' if ok else 'FAIL'} {note}")
    return st, swipes


def park(st, pid):
    """Put the cursor on [pid]'s center (closed loop; setup only, not measured)."""
    return lab.cursor_to(*lab.panel_point(st, pid, 0, 0), st, tries=5)


# ---------------------------------------------------------------- layout D (the user's device layout)
st = setup(windows=3)
A, B, C = window_ids(st)
names = {0: "picker", A: "A(-38°)", B: "B(+14°)", C: "C(low,far)"}
st = arrange(st, A, theta_deg=-40, r_steps=8, size_steps=7, preset="editor")
st = arrange(st, B, theta_deg=15, r_steps=8, size_steps=7, preset="editor")
yc = 2080 * math.tan(math.radians(-28))
st = arrange(st, C, theta_deg=-5, y=yc, r_steps=17, size_steps=7, preset="editor")
# Picker to θ -74.5° with its bar.
pk = st["panels"][0]
st = lab.cursor_to(*lab.panel_point(st, 0, 0, lab.bar_v(pk)), st)
lab.drag((math.radians(-74.5) - pk["theta"]) * pk["r"], 0)
st = lab.state()
print("      layout D:", "; ".join(f"{names.get(i, i)} θ {math.degrees(p['theta']):+.0f}° y {p['y']:.0f} r {p['r']:.0f} {p['w']:.0f}x{p['h']:.0f}"
                              for i, p in st["panels"].items()))
lab.head(-20, -5)
lab.shot("layout_D")

kinds = ["normal", "flick"] if QUICK else ["slow", "normal", "flick"]
ids = [0, A, B, C]
for kind in kinds:
    for src in ids:
        for dst in ids:
            if src == dst:
                continue
            st = park(st, src)
            st, _ = travel(st, dst, kind, "D", f"{names[src]} -> {names[dst]}")

# ---------------------------------------------------------------- gaps: crossing A -> B at eye level in small steps
st = park(st, A)
pa, pb = st["panels"][A], st["panels"][B]
a_right = pa["theta"] + math.atan(pa["w"] / 2 / pa["r"])
b_left = pb["theta"] - math.atan(pb["w"] / 2 / pb["r"])
st = lab.cursor_to(a_right - 0.05, 0.0, st)
seq, bad, stall = [], [], 0
step_pts = 15
for i in range(int(((b_left + 0.05) - (a_right - 0.05)) * lab._cursor_r(st) / step_pts) + 3):
    th0 = st["cursor"]["theta"]
    swipe(1, 0, "slow", length=step_pts)
    st = lab.state()
    th = st["cursor"]["theta"]
    if th - th0 < 1e-4:
        stall += 1
    exp = A if th <= a_right else (B if th >= b_left else None)
    got = st["hover"]["id"] if st["hover"] else None
    seq.append((round(math.degrees(th), 2), got))
    if got != exp and not (st["hover"] and st["hover"]["zone"] == "CORNER"):
        bad.append((round(math.degrees(th), 2), exp, got))
    if th > b_left + 0.05:
        break
check("gap A→B: cursor keeps moving through the gap (no stalls)", stall == 0, f"{stall} stalled steps; trail {seq}")
check("gap A→B: hover changes exactly at the panel edges (A, then nothing, then B)", not bad,
      f"A right edge {math.degrees(a_right):.2f}°, B left edge {math.degrees(b_left):.2f}°; mismatches {bad}")
lab.head(math.degrees((a_right + b_left) / 2), 0)
lab.shot("gap_crossing")

# ---------------------------------------------------------------- crossing a window's content
st = park(st, A)
st = lab.cursor_to(pa["theta"] - math.atan(pa["w"] / 2 / pa["r"]) + 0.02, 0.0, st)  # inside A's left edge
st, n = travel(st, B, "normal", "content", "across A, no click")
check("crossing a window's content without clicking never captures", st["captured"] is None, f"capture={st['capture']}")
st = park(st, A)
lab.click()
st = lab.state()
check("click in A captures", st["captured"] == A, st["capture"])
to_edge = (pa["theta"] + math.atan(pa["w"] / 2 / pa["r"]) - st["cursor"]["theta"]) * pa["r"] / (pa["w"] / lab.WINDOW_PTS.get(A, pa["w"] / 0.8))
need = math.ceil(to_edge / (PROFILES["normal"][0] * PROFILES["normal"][1]))
swipes = 0
while st["captured"] == A and swipes < MAX_SWIPES:
    swipe(1, 0, "normal")
    swipes += 1
    st = lab.state()
ok = st["captured"] is None and swipes <= need + 1
ROWS.append(("content", "captured in A -> exit right", "normal", swipes, None, ok, f"edge needs {need}; {st['capture']}"))
check("captured: exit through the edge within 1 extra swipe", ok, f"{swipes} swipes, {need} to reach the edge | {st['capture']}")

# ---------------------------------------------------------------- edge cases
# Elevation limit: flick up 3 times, then come back to A with normal swipes.
st = park(st, A)
for _ in range(3):
    swipe(0, -1, "flick")
st = lab.state()
check("elevation is limited (t ≤ tan 60° + ε)", st["cursor"]["t"] <= 1.75, f"cursor {st['cursor']}")
st, n = travel(st, A, "normal", "edge", "from elevation limit -> A")

# Wrap-around: from the picker keep going left (away from everything) until something is reached.
st = park(st, 0)
far = max((i for i in ids if i), key=lambda i: st["panels"][i]["theta"])
st, n = travel(st, far, "flick", "edge", f"picker -> {names[far]} the long way (wrap)", allow_wrap=True)
c = st["cursor"]
check("wrap-around past ±180° works (cursor θ stays in [-π, π))", -math.pi - 1e-3 <= c["theta"] < math.pi + 1e-3, f"{c}")

# Overlap at different depths: push B behind A's θ, farther away.
st = arrange(st, B, theta_deg=math.degrees(st["panels"][A]["theta"]) + 10, r_steps=5)
pa, pb = st["panels"][A], st["panels"][B]
st = lab.cursor_to(pa["theta"] + 0.05, 0.0, st)
check("overlap: the nearer panel wins the hover", st["hover"] and st["hover"]["id"] == (A if pa["r"] < pb["r"] else B),
      f"A r {pa['r']:.0f} B r {pb['r']:.0f}; hover {st['hover']}")
st = park(st, 0)
st, n = travel(st, B, "normal", "edge", "picker -> B behind A")

# Very small (glance) and very large (editor) panels.
st = arrange(st, C, preset="glance")
st = park(st, 0)
st, n = travel(st, C, "normal", "edge", "picker -> C (glance, small)")
st = arrange(st, A, preset="editor")
st = park(st, 0)
st, n = travel(st, A, "normal", "edge", "picker -> A (editor, large)")

# After tidy.
lab.command("tidy")
time.sleep(1)
st = lab.state()
for kind in ("normal", "flick"):
    for src, dst in ((0, B), (A, B), (B, 0)):
        st = park(st, src)
        st, n = travel(st, dst, kind, "tidy", f"{names[src]} -> {names[dst]}")
lab.shot("after_tidy")

# After recenter with the head turned.
lab.head(30, 0)
lab.command("recenter")
time.sleep(1.5)
st = lab.state()
st = park(st, 0)
st, n = travel(st, B, "normal", "recenter", f"picker -> {names[B]}")

# Lying down: pitch up 70°, recenter, travel; then back.
lab.head(0, 70)
lab.command("recenter")
time.sleep(1.5)
st = lab.state()
st = park(st, 0)
st, n = travel(st, A, "normal", "lying", "picker -> A")
st, n = travel(st, B, "normal", "lying", "A -> B")
lab.head(0, 0)
lab.command("recenter")
time.sleep(1)

# ---------------------------------------------------------------- table
lines = ["| section | path | swipe | swipes | time s | result | note |", "|---|---|---|---|---|---|---|"]
for sec, path, kind, n, secs, ok, note in ROWS:
    lines.append(f"| {sec} | {path} | {kind} | {n} | {secs if secs is not None else ''} | {'PASS' if ok else 'FAIL'} | {note} |")
table = "\n".join(lines)
os.makedirs(lab.OUT, exist_ok=True)
with open(os.path.join(lab.OUT, "travel_table.md"), "w") as f:
    f.write(table + "\n")
print(table)
npass = sum(1 for r in ROWS if r[5])
check(f"travel paths within the swipe budget ({npass}/{len(ROWS)})", npass == len(ROWS), f"table: {os.path.join(lab.OUT, 'travel_table.md')}")
finish()
