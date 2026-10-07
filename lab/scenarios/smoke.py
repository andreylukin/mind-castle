#!/usr/bin/env python3
"""Smoke: deploy, connect to the lab streamer, open the 3 lab windows from the picker, look left/ahead/right.
Usage: smoke.py [--no-deploy]"""
import sys
import time

from _common import check, finish, fmt, lab, picker_rows, setup, window_ids

deploy = "--no-deploy" not in sys.argv
t0 = time.time()
lab.start_lab()
log0 = lab.streamer_log_size()
st = setup(windows=3, deploy=deploy)
check("app connected and in control mode", st.get("control") and st.get("cursor") is not None,
      f"control={st.get('control')} cursor={st.get('cursor')}")
rows = picker_rows(st)
check("picker lists exactly 3 lab windows", rows == 3, f"picker h={st['panels'][0]['h']:.0f} -> {rows} rows")
ids = window_ids(st)
check("3 window panels shown after picker clicks", len(ids) == 3,
      "; ".join(f"{i}: {fmt(st['panels'][i])}" for i in ids))
subs = [l for l in lab.streamer_log(log0).splitlines() if "subscribe" in l][-3:]
check("Mac saw SUBSCRIBE for the shown windows", bool(subs) and all(str(i) in subs[-1] for i in ids), " | ".join(subs))
errs = [l for l in lab.streamer_log(log0).splitlines() if "stream stopped:" in l or "error" in l.lower()]
check("Mac streams without errors", not errs, " | ".join(errs[-3:]))
dec = lab.logs(t0, r"decode avg|decoder \d+:")
check("headset decoded frames", bool(dec), dec[-1] if dec else "no decoder log lines since start")
for yaw in (-40, 0, 40):
    lab.head(yaw, 0)
    lab.shot(f"yaw{yaw:+d}")
lab.head(0, 0)
finish()
