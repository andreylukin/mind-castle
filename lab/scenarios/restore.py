#!/usr/bin/env python3
"""Layout restore (PROTOCOL v4 feature 2): arrange 3 panels, then (a) force-stop + relaunch the app and
(b) restart the Mac side so window IDs change; each time the same windows come back where they were.
Usage: restore.py"""
import math
import os
import subprocess
import time

from _common import check, finish, fmt, lab, setup, window_ids

TOL_TH, TOL_DP = 0.01, 3.0


def by_title(st):
    titles = lab.window_titles()
    return {titles.get(i, f"#{i}"): st["panels"][i] for i in window_ids(st)}


def same(a, b):
    return (abs(a["theta"] - b["theta"]) < TOL_TH and all(abs(a[k] - b[k]) < TOL_DP for k in ("y", "r", "w", "h")))


def compare(tag, want, got):
    check(f"[{tag}] same set of windows shown", sorted(want) == sorted(got), f"want {sorted(want)} got {sorted(got)}")
    for t, p in want.items():
        q = got.get(t)
        check(f"[{tag}] '{t}' restored to the same place", q is not None and same(p, q),
              f"want {fmt(p)} | got {fmt(q) if q else None}")


def wait_shown(n, timeout=30):
    end = time.time() + timeout
    st = lab.state()
    while len(window_ids(st)) < n and time.time() < end:
        time.sleep(1)
        st = lab.state()
    return st


st = setup(windows=3)
ids = window_ids(st)
# Arrange: bar-drag the first, nudge/depth the second via COMMANDs (captured = active), resize the third.
a, b, c = ids
st = lab.cursor_to(*lab.panel_point(st, a, 0, lab.bar_v(st["panels"][a])), st)
lab.drag(-200, -120)
st = lab.state()
st = lab.cursor_to(*lab.panel_point(st, b, 0, 0), st)
lab.click()
lab.command("nudge", dtheta=-2)
lab.command("depth", d=1)
st = lab.state()
pc = st["panels"][c]
st = lab.cursor_to(*lab.panel_point(st, c, pc["w"] / 2 + 6, pc["h"] / 2 + 6), st)
lab.drag(60, -40)
time.sleep(2.5)  # saves are debounced
st = lab.state()
want = by_title(st)
saved = lab.logs(0, r"layout: saved")
check("layout saved after the changes", bool(saved), saved[-1] if saved else "no `layout: saved` line")
print("      arranged:", "; ".join(f"{t}: {fmt(p)}" for t, p in want.items()))
lab.head(0, 0)
lab.shot("arranged")

# (a) App relaunch.
t0 = time.time()
lab.recenter()
lab.restart_app()
lab.wait_connected()
st = wait_shown(3)
rl = lab.logs(t0, r"layout: restored")
check("[relaunch] app logs a restore", bool(rl), rl[-1] if rl else "none")
compare("relaunch", want, by_title(st))
lab.head(0, 0)
lab.shot("after_relaunch")

# (b) Mac side restarts: window IDs change, titles stay.
old_ids = set(window_ids(st))
if lab.BACKEND == "fake":
    lab.stop_lab()
    os.environ["FAKE_ID_BASE"] = "9100"
    lab.start_lab()
else:
    subprocess.run(["pkill", "-x", "castle-testwin"])
    time.sleep(1)
    subprocess.Popen([lab.TESTWIN], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(3)
st = wait_shown(3, timeout=45)
new_ids = set(window_ids(st))
check("window IDs changed", new_ids and not (new_ids & old_ids), f"{sorted(old_ids)} -> {sorted(new_ids)}")
compare("new window IDs", want, by_title(st))
subs = lab.wait_streamer(r"subscribe \[", 0, 1)
check("re-subscribed to the new IDs", subs and all(str(i) in subs[-1] for i in new_ids), subs[-1] if subs else "none")
lab.head(0, 0)
lab.shot("after_new_ids")
finish()
