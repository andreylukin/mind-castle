#!/usr/bin/env python3
"""Leak regression: system_server `Embedded{}` input channels (one per SpatialPanel window). Expected after the
fix: a launch adds ~5 + 2N (N shown windows) and nothing else grows: hover churn, control toggles and cursor
sweeps are flat. The system itself drifts upward slowly (~+10 / 30 s seen with our app stopped), so each
phase is compared against an idle baseline drift measured first.
Usage: leak.py [hover_cycles]"""
import sys
import time

from _common import check, finish, lab, setup, window_ids

N = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 50


def count():
    time.sleep(1.5)
    return lab.embedded_channels()


st = setup(windows=3)
shown = len(window_ids(st))
time.sleep(3)

# Idle drift: app running, nothing happening, 30 s.
c0 = count()
time.sleep(30)
c1 = count()
drift = c1 - c0
print(f"      idle drift over 30 s: {c0} -> {c1} ({drift:+d})")
slack = max(3, drift) + 2


def phase(name, fn, budget_s):
    before = count()
    t0 = time.time()
    fn()
    after = count()
    dt = time.time() - t0
    allowed = slack * max(1.0, dt / 30) + 2
    check(f"{name}: no input-channel growth", after - before <= allowed,
          f"Embedded{{}} {before} -> {after} (Δ{after - before:+d} in {dt:.0f} s; allowed ≤{allowed:.0f} incl. idle drift)")


a = window_ids(st)[0]
p = st["panels"][a]
st = lab.cursor_to(*lab.panel_point(st, a, 0, lab.bar_v(p)), st)
check("cursor parked on a grab bar", st["hover"] and st["hover"]["zone"] == "BAR", f"hover={st['hover']}")


def hover_cycles():
    for _ in range(N):  # bar -> empty space below -> bar: hover (outline, cursor image) on/off each cycle
        lab.move_smooth(0, 80)
        lab.move_smooth(0, -80)


def control_toggles():
    for _ in range(10):  # raw inject: lab.mode() would restart the keepalive while control is off
        lab.inject({"type": "mode", "control": False})
        time.sleep(0.4)
        lab.inject({"type": "mode", "control": True})
        time.sleep(0.4)


def sweeps():
    s = lab.state()
    lo = min(q["theta"] - 0.3 for q in s["panels"].values())
    hi = max(q["theta"] + 0.3 for q in s["panels"].values())
    s = lab.cursor_to(lo, 0.0, s)
    for t in (0.0, -0.3, 0.2):
        s = lab.cursor_to(hi, t, s)
        s = lab.cursor_to(lo, t, s)


phase(f"{N} hover on/off cycles", hover_cycles, 60)
lab.keepalive(False)
phase("10 control-mode toggles", control_toggles, 10)
lab.mode(True)
phase("cursor sweeps across all panels (3 rows, both ways)", sweeps, 60)

# One relaunch. system_server frees a stopped instance's channels lazily (seen: nothing at +5 s, -13 at +60 s),
# so let the count settle for up to 90 s before comparing.
def settled(limit=90):
    last, t0 = count(), time.time()
    while time.time() - t0 < limit:
        time.sleep(15)
        now = count()
        if now == last:
            return now
        last = now
    return last


before = settled()
lab.restart_app()
lab.wait_connected()
st = lab.state()
n2 = len(window_ids(st))
after = settled()
check(f"relaunch frees the old instance (net ≈ 0 after settling; one launch costs ~{n2} + 2)",
      abs(after - before) <= slack + 4, f"{before} -> {after} (Δ{after - before:+d}); {n2} windows shown after restore")
lab.adb("shell", "am", "force-stop", lab.PKG)
stopped = settled()
check(f"force-stop frees the app's channels (≈ {n2 + 2} fewer after settling)",
      after - stopped >= n2 + 2 - 2, f"{after} -> {stopped} (Δ{stopped - after:+d})")
lab.restart_app()
lab.wait_connected()
finish()
