"""Shared scenario plumbing: PASS/FAIL checks with evidence, and setup to a known layout."""
import json
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCENARIO = os.path.splitext(os.path.basename(sys.argv[0]))[0]
os.environ.setdefault("LAB_RUN", f"{SCENARIO}-{time.strftime('%Y%m%d-%H%M%S')}")
import lab  # noqa: E402

results = []


def check(name, ok, evidence=""):
    results.append((name, bool(ok), evidence))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"\n      {evidence}" if evidence else ""), flush=True)
    return ok


def finish():
    rtt = lab.link_rtt_ms()
    if rtt is not None and rtt > 1000:
        check("app link keeps up (PING rtt < 1 s)", False, f"rtt={rtt:.0f} ms: the app is behind on its socket; interaction results are unreliable")
    if lab.RECONNECTS:
        check("headset stayed connected to the lab streamer during the run", False,
              f"{len(lab.RECONNECTS)} drop(s): " + "; ".join(time.strftime('%H:%M:%S', time.localtime(t)) + f" {e}" for t, e in lab.RECONNECTS))
    failed = [n for n, ok, _ in results if not ok]
    print(f"\n{SCENARIO}: {len(results) - len(failed)}/{len(results)} passed; screenshots in {lab.OUT}")
    for n in failed:
        print("  FAILED:", n)
    with open(os.path.join(lab.OUT, "results.json"), "w") as f:
        json.dump([{"name": n, "ok": ok, "evidence": e} for n, ok, e in results], f, indent=1)
    sys.exit(1 if failed else 0)


def fmt(p):
    return "θ %.3f (%.1f°) y %.0f r %.0f %.0fx%.0f" % (p["theta"], math.degrees(p["theta"]), p["y"], p["r"], p["w"], p["h"])


def picker_rows(st):
    return round((st["panels"][0]["h"] - 2 * 16 - 36) / 44)


def click_picker_row(st, row):
    """Cursor onto picker row [row] (left part of the row text) and click. Returns the state after."""
    p = st["panels"][0]
    v = p["h"] / 2 - (16 + 36 + 44 * (row + 0.5))
    st = lab.cursor_to(*lab.panel_point(st, 0, -p["w"] / 4, v), st)
    lab.click()
    time.sleep(0.3)
    return lab.state()


def setup(windows=3, deploy=False, restart=True, fresh=True):
    """Lab streamer up, app (re)started (with its persisted layout cleared unless fresh=False), control on,
    [windows] lab windows shown via the picker. Returns state."""
    lab.start_lab()
    n = lab.embedded_channels()
    print(f"[lab] system_server embedded input channels: {n} (leak; reboot the emulator if this gets near ~1000)")
    # Recenter first: the app anchors its layout to the head pose at launch, so this gives layout frame == head frame.
    lab.recenter()
    if fresh and (deploy or restart):
        lab.clear_layout()  # v4 restores the last layout; scenarios want the default one
    if deploy:
        lab.deploy()
    elif restart:
        lab.restart_app()
    st = lab.wait_connected()
    # Known app race (see README): the cylinder center is sometimes sampled before the origin entity is laid
    # out, giving center≈(0, 1280, 0) and a layout floating ~1.6 m up. Record it, then relaunch to get a usable run.
    for _ in range(3):
        c = st.get("center") or [0, 0, 0]
        if abs(c[1]) < 500:
            break
        check("app startup: cylinder center at the head (not the floor-origin race)", False,
              f"center={c}; relaunching")
        lab.recenter()
        lab.restart_app()
        st = lab.wait_connected()
    # WINDOW_LIST arrives right after connect; let the picker settle to its row count.
    time.sleep(2.5)
    st = lab.state()
    check_frame(st)
    rows = picker_rows(st)
    for row in range(rows):
        if len(st["panels"]) - 1 >= windows:
            break
        before = set(st["panels"])
        st = click_picker_row(st, row)
        if set(st["panels"]) < before:  # it was shown: clicking hid it; show it again
            st = click_picker_row(st, row)
    learn_window_points(st)
    return st


def check_frame(st):
    """Sanity: looking at the picker's center must put it at screen center, upright. Catches a head/layout
    frame mismatch (e.g. someone rotated the emulator head outside the harness)."""
    p = st["panels"][0]
    lab.head(math.degrees(p["theta"]), math.degrees(math.atan2(p["y"], p["r"])))
    path = lab.shot("frame_check")
    # Picker-coloured pixels only, so this works over the black shell and over passthrough alike.
    box = (80, 80, 220, 220)  # central 140 px of a 300 px image; the picker is ~78 px wide there
    m = lab.measure_dark_panel(lab.Img(path, 300), box=box)
    ok = m is not None and m["x1"] - m["x0"] > 50 and abs(m["center_offset"]) < 0.04
    if ok:
        # Turn 40° away: the picker must leave the screen center (catches head-locked content).
        lab.head(math.degrees(p["theta"]) + 40, 0)
        m2 = lab.measure_dark_panel(lab.Img(lab.shot("frame_check_turned"), 300), box=box)
        ok = m2 is None or m2["x1"] - m2["x0"] < 50
        if not ok:
            print("      head turn did not move the picker: content is head-locked (emulator degraded? see README)")
    check("harness frame: picker appears centered and square-on when looked at", ok, f"{m} | {path}")
    lab.head(0, 0)
    return ok


def learn_window_points(st):
    """Window width in Mac points from a fresh panel: panel w = pixels * 0.4 (PANEL_SCALE), points = pixels / 2."""
    for pid in window_ids(st):
        lab.WINDOW_PTS.setdefault(pid, st["panels"][pid]["w"] / 0.8)


def window_ids(st):
    return [i for i in st["panels"] if i != 0]
