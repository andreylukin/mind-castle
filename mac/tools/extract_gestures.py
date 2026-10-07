#!/usr/bin/env python3
"""Split recorded trackpad input into gestures for test replay.

usage: extract_gestures.py trace.jsonl [--gap-ms 150] > gestures.json

Reads the streamer's trace (~/Library/Logs/mind-castle/trace.jsonl), keeps the {"type":"ptr"} lines that control
mode records for every POINTER sent (deltas only), and splits them wherever input pauses for more than --gap-ms.
Each gesture gets summary stats plus its events, with times in ms relative to the gesture start, for replay.
"""
import argparse, json, math, sys


def kind(g):
    if g["buttons"]:
        return "drag"
    if abs(g["sx"]) + abs(g["sy"]) > 0 and g["path"] == 0:
        return "scroll"
    if abs(g["sx"]) + abs(g["sy"]) > 0:
        return "mixed"
    return "move"


def summarize(evs):
    t0 = evs[0]["t"]
    g = {"start_us": t0, "duration_ms": round((evs[-1]["t"] - t0) / 1000, 1), "n": len(evs),
         "dx": 0.0, "dy": 0.0, "sx": 0.0, "sy": 0.0, "path": 0.0, "peak_speed": 0.0, "buttons": 0, "events": []}
    prev = None
    for e in evs:
        dx, dy = e.get("dx", 0), e.get("dy", 0)
        g["dx"] += dx; g["dy"] += dy
        g["sx"] += e.get("sx", 0); g["sy"] += e.get("sy", 0)
        g["path"] += math.hypot(dx, dy)
        g["buttons"] |= int(e.get("buttons", 0))
        if prev is not None:
            dt = max(e["t"] - prev, 1000) / 1e6  # >= 1 ms, avoids spikes from coalesced timestamps
            g["peak_speed"] = max(g["peak_speed"], math.hypot(dx, dy) / dt)
        prev = e["t"]
        g["events"].append([round((e["t"] - t0) / 1000, 2), dx, dy, int(e.get("buttons", 0)), e.get("sx", 0), e.get("sy", 0)])
    for k in ("dx", "dy", "sx", "sy", "path"):
        g[k] = round(g[k], 2)
    g["peak_speed"] = round(g["peak_speed"])  # points per second
    g["kind"] = kind(g)
    return g


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("trace")
    ap.add_argument("--gap-ms", type=float, default=150)
    a = ap.parse_args()
    evs = []
    with open(a.trace) as f:
        for line in f:
            try:
                o = json.loads(line)
            except ValueError:
                continue
            if o.get("type") == "ptr" and "t" in o:
                evs.append(o)
    evs.sort(key=lambda e: e["t"])
    gestures, cur = [], []
    for e in evs:
        if cur and e["t"] - cur[-1]["t"] > a.gap_ms * 1000:
            gestures.append(summarize(cur)); cur = []
        cur.append(e)
    if cur:
        gestures.append(summarize(cur))
    json.dump({"gap_ms": a.gap_ms, "event_fields": ["t_ms", "dx", "dy", "buttons", "sx", "sy"],
               "count": len(gestures), "gestures": gestures}, sys.stdout, indent=1)
    sys.stdout.write("\n")
    print(f"{len(evs)} pointer events -> {len(gestures)} gestures", file=sys.stderr)


if __name__ == "__main__":
    main()
