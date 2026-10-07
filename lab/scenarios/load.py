#!/usr/bin/env python3
"""Sustained load: 3 windows streaming at full rate for 60 s while the cursor sweeps across them.
Asserts the app keeps up: PING rtt p95 < 100 ms (the app's `clock offset=… rtt=` lines) and POINTER-to-app
latency stays bounded. Latency probe: a button press/release in empty space (no click target) makes the app log
a `pointer:` line immediately; latency = host time the line arrives over logcat - host time the press was sent
(includes ~adb logcat delay, so it's an upper bound). Meant for the real backend (LAB_BACKEND=real).
Usage: load.py [seconds]"""
import math
import re
import sys
import time

from _common import check, finish, lab, setup, window_ids

DUR = float(sys.argv[1]) if len(sys.argv) > 1 else 60.0
RTT_P95_MS = 100.0
PTR_P95_MS = 250.0
PTR_MAX_MS = 1000.0


def p95(xs):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(math.ceil(0.95 * len(xs))) - 1)] if xs else None


st = setup(windows=3)
ids = window_ids(st)
check("3 windows streaming", len(ids) == 3, str(ids))
lo = min(p["theta"] - math.atan(p["w"] / 2 / p["r"]) for p in st["panels"].values())
hi = max(p["theta"] + math.atan(p["w"] / 2 / p["r"]) for p in st["panels"].values())
EMPTY_T = -0.85  # below every panel and bar: a press there hits nothing

t_start = time.time()
probes = []
row = 0
while time.time() - t_start < DUR:
    # One sweep across all windows at content height (MOUSE moves flow to the Mac), alternating direction.
    st = lab.cursor_to(lo if row % 2 else hi, 0.0, lab.state())
    row += 1
    # Latency probe in empty space.
    st = lab.cursor_to((lo + hi) / 2, EMPTY_T, st)
    if st["hover"] is not None:
        continue
    t0 = time.time()
    lab.pointer(0, 0, 2)  # right button: no drag/jump semantics in empty space
    line = lab.wait_log(r"pointer: .*buttons=2", t0, 5.0)
    if line:
        probes.append((time.time() - t0) * 1000)
    lab.pointer(0, 0, 0)
    time.sleep(0.2)

rtts = []
for l in lab.logs(t_start, r"clock offset="):
    m = re.search(r"rtt=(\d+) us", l)
    if m:
        rtts.append(int(m[1]) / 1000)
fps = [l for l in lab.streamer_log().splitlines()[-60:] if " fps " in l]
decode = lab.logs(t_start, r"decode avg")
print(f"      {len(probes)} pointer probes, {len(rtts)} rtt samples over {time.time() - t_start:.0f} s")
print("      streamer:", " | ".join(fps[-3:]))
print("      headset:", " | ".join(d.split('Castle  : ')[-1] for d in decode[-3:]))
check(f"PING rtt p95 < {RTT_P95_MS:.0f} ms under load", rtts and p95(rtts) < RTT_P95_MS,
      f"rtt ms: n={len(rtts)} p95={p95(rtts)} max={max(rtts) if rtts else None} all={[round(r) for r in rtts]}")
check(f"POINTER→app latency bounded (p95 < {PTR_P95_MS:.0f} ms, max < {PTR_MAX_MS:.0f} ms, logcat delay included)",
      probes and p95(probes) < PTR_P95_MS and max(probes) < PTR_MAX_MS,
      f"ms: n={len(probes)} p95={p95(probes) and round(p95(probes))} max={probes and round(max(probes))} "
      f"first={[round(x) for x in probes[:3]]} last={[round(x) for x in probes[-3:]]}")
check("latency doesn't grow over the run (last third p95 ≤ 2× first third p95 + 50 ms)",
      len(probes) >= 6 and p95(probes[-len(probes) // 3:]) <= 2 * p95(probes[:len(probes) // 3]) + 50,
      f"first third p95={p95(probes[:len(probes) // 3])} last third p95={p95(probes[-len(probes) // 3:])}")
finish()
