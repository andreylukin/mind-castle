#!/usr/bin/env python3
"""COMMAND hotkeys (PROTOCOL v4): each spatial command changes the active panel as specified; undo/redo
round-trip; the active panel follows capture > last click > head.
Usage: commands.py"""
import math
import time

from _common import check, finish, fmt, lab, setup, window_ids

DPM = 800.0  # emulator dp per meter
STEP = 1.1   # > Comfort.COALESCE_MS, so each command is its own undo step


def deg(x):
    return math.degrees(x)


def run(cmd, **kw):
    line = lab.command(cmd, **kw)
    time.sleep(0.3)
    return line, lab.state()


st = setup(windows=3)
a, b, c = window_ids(st)
lab.head(deg(st["panels"][a]["theta"]), 0)
st = lab.cursor_to(*lab.panel_point(st, a, 0, 0), st)
lab.click()  # captured in a -> a is the active panel
st = lab.state()
check("captured in the first window (it becomes the active panel)", st["captured"] == a, st["capture"])

history = [dict(st["panels"][a])]
others0 = {o: dict(st["panels"][o]) for o in (b, c)}
specs = [
    ("nudge", dict(dtheta=1), lambda p, q: abs(deg(q["theta"] - p["theta"]) - 5) < 0.2 and abs(q["y"] - p["y"]) < 1, "θ +5°"),
    ("nudge", dict(dtheta=-1), lambda p, q: abs(deg(q["theta"] - p["theta"]) + 5) < 0.2, "θ -5°"),
    ("nudge", dict(dy=1), lambda p, q: abs(q["y"] - p["y"] - 0.05 * DPM) < 1, "y +5 cm"),
    ("nudge", dict(dy=-1), lambda p, q: abs(q["y"] - p["y"] + 0.05 * DPM) < 1, "y -5 cm"),
    ("depth", dict(d=-1), lambda p, q: abs(q["r"] - p["r"] + 0.10 * DPM) < 1, "r -10 cm (closer)"),
    ("depth", dict(d=1), lambda p, q: abs(q["r"] - p["r"] - 0.10 * DPM) < 1, "r +10 cm"),
    ("size", dict(d=1), lambda p, q: abs(q["w"] / p["w"] - 1.1) < 0.01 and abs(q["h"] / q["w"] - p["h"] / p["w"]) < 0.005, "w ×1.1, aspect kept"),
    ("size", dict(d=-1), lambda p, q: abs(q["w"] / p["w"] - 1 / 1.1) < 0.01, "w ÷1.1"),
    ("center", {}, lambda p, q: abs(q["theta"]) < 0.01 and abs(q["y"]) < 1 and abs(q["r"] - 0.9 * DPM) < 1
     and abs(q["w"] - 2 * 0.9 * DPM * math.tan(math.radians(25))) < 2, "straight ahead, r 0.9 m, 50° wide"),
    ("preset", dict(name="side"), lambda p, q: abs(q["r"] - 1.1 * DPM) < 1 and abs(q["w"] - 2 * 1.1 * DPM * math.tan(math.radians(17.5))) < 2
     and abs(q["theta"] - p["theta"]) < 0.01, "r 1.1 m, 35° wide, stays at its θ"),
    ("preset", dict(name="glance"), lambda p, q: abs(q["r"] - 1.4 * DPM) < 1 and abs(q["w"] - 2 * 1.4 * DPM * math.tan(math.radians(12.5))) < 2, "r 1.4 m, 25° wide"),
    ("preset", dict(name="editor"), lambda p, q: abs(q["theta"]) < 0.01 and abs(q["r"] - 0.9 * DPM) < 1, "editor = straight ahead r 0.9 m"),
]
for cmd, kw, ok, what in specs:
    p = st["panels"][a]
    line, st = run(cmd, **kw)
    q = st["panels"][a]
    check(f"{cmd} {kw or ''}: {what}", line and ok(p, q),
          f"{fmt(p)} -> {fmt(q)} | app: {line}")
    history.append(dict(q))
    time.sleep(STEP)
check("other windows untouched by commands on the active panel",
      all(st["panels"][o] == others0[o] for o in others0), "; ".join(f"{o}: {fmt(st['panels'][o])}" for o in others0))
lab.head(0, 0)
lab.shot("after_commands")

# Undo walks back through every step; redo replays them.
n = len(specs)
for i in range(n):
    line, st = run("undo")
    time.sleep(0.2)
want = history[0]
check(f"{n} × undo returns the panel to where it started", all(abs(st["panels"][a][k] - want[k]) < (0.002 if k == "theta" else 1)
      for k in want), f"want {fmt(want)} got {fmt(st['panels'][a])}")
line, st = run("undo")
check("undo past the bottom of the stack is a no-op (or undoes earlier layout steps only)", line is not None, line)
line, st = run("redo")  # if the extra undo undid something earlier, this replays it
for i in range(n):
    line, st = run("redo")
want = history[-1]
check(f"{n} × redo returns to the final state", all(abs(st["panels"][a][k] - want[k]) < (0.002 if k == "theta" else 1)
      for k in want), f"want {fmt(want)} got {fmt(st['panels'][a])} | last: {line}")

# Coalescing: two quick nudges are one undo step.
p0 = dict(st["panels"][a])
lab.command("nudge", dtheta=1)
lab.command("nudge", dtheta=1)
time.sleep(0.3)
st = lab.state()
check("two quick nudges move 10°", abs(deg(st["panels"][a]["theta"] - p0["theta"]) - 10) < 0.3, fmt(st["panels"][a]))
time.sleep(STEP)
line, st = run("undo")
check("...and one undo reverts both (coalesced within 1 s)", abs(st["panels"][a]["theta"] - p0["theta"]) < 0.002,
      f"{fmt(st['panels'][a])} | {line}")

# Bar drag is an undo step too.
time.sleep(STEP)
p0 = dict(st["panels"][a])
k = st["panels"][a]["w"] / lab.WINDOW_PTS[a] if a in lab.WINDOW_PTS else 0.8
cv = st["cursor"]["t"] * st["panels"][a]["r"] - st["panels"][a]["y"]
lab.move_smooth(0, (cv + st["panels"][a]["h"] / 2 + 68) / k)  # leave capture downward onto the bar
st = lab.state()
if not (st["hover"] and st["hover"]["zone"] == "BAR"):
    st = lab.cursor_to(*lab.panel_point(st, a, 0, lab.bar_v(st["panels"][a])), st)
check("cursor on the active panel's bar before the drag", st["hover"] and st["hover"]["zone"] == "BAR", f"hover={st['hover']}")
lab.drag(-150, 0)
time.sleep(STEP)
st = lab.state()
moved = abs(st["panels"][a]["theta"] - p0["theta"]) > 0.05
line, st = run("undo")
check("a bar drag is undoable", moved and abs(st["panels"][a]["theta"] - p0["theta"]) < 0.002,
      f"moved={moved}; after undo {fmt(st['panels'][a])} want {fmt(p0)} | {line}")

# Active panel = last clicked when not captured; = head when nothing clicked is covered by a fresh app (not here).
lab.head(deg(st["panels"][c]["theta"]), 0)
st = lab.state()
pa, pc = dict(st["panels"][a]), dict(st["panels"][c])
line, st = run("nudge", dy=1)
check("not captured: active = last clicked window, not the looked-at one",
      abs(st["panels"][a]["y"] - pa["y"] - 40) < 1 and abs(st["panels"][c]["y"] - pc["y"]) < 1, f"app: {line}")
time.sleep(STEP)
run("undo")

# Recenter command.
t0 = time.time()
line = lab.command("recenter")
cyl = lab.wait_log(r"head: cylinder center", t0, 5)
check("recenter command re-centers the arc on the head", line == "recenter" and cyl is not None, f"{line} | {cyl}")
lab.head(0, 0)
finish()
