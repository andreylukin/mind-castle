#!/usr/bin/env python3
"""Summarize castle-streamer's per-frame latency trace (p50/p95/max per stage, in ms).

usage: latency_report.py [TRACE] [--since SECONDS]

TRACE defaults to ~/Library/Logs/mind-castle/trace.jsonl (test instances on other ports write trace-<port>.jsonl).
--since keeps only the last SECONDS of the trace, measured back from its newest timestamp.
Keypress stages use, per window, the first frame captured after each keyDown (within 500 ms).
"""
import argparse, json, os

STAGES = [
    ("SCK delivery (pts->callback)", "pts", "sckCallback"),
    ("encode wait (callback->submit)", "sckCallback", "encodeSubmit"),
    ("encode (submit->done)", "encodeSubmit", "encodeDone"),
    ("send (done->written)", "encodeDone", "sent"),
    ("USB+recv (written->recv)", "sent", "recv"),
    ("decode (recv->out)", "recv", "out"),
    ("Mac total (pts->written)", "pts", "sent"),
    ("total (pts->out)", "pts", "out"),
]
KEY_WINDOW = 500_000


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p / 100 * len(xs)))]


def row(name, xs):
    if not xs:
        return f"{name:<34} {'-':>7} {'-':>7} {'-':>7} {0:>6}"
    return f"{name:<34} {pct(xs, 50) / 1000:>7.1f} {pct(xs, 95) / 1000:>7.1f} {max(xs) / 1000:>7.1f} {len(xs):>6}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trace", nargs="?", default=os.path.expanduser("~/Library/Logs/mind-castle/trace.jsonl"))
    ap.add_argument("--since", type=float, help="only the last SECONDS of the trace")
    a = ap.parse_args()

    frames, keys = [], []
    with open(a.trace) as f:
        for line in f:
            try:
                r = json.loads(line)
            except ValueError:
                continue  # partial last line while the streamer is writing
            (keys if r.get("type") == "key" else frames).append(r)
    if a.since is not None:
        newest = max([r["pts"] for r in frames] + [r["t"] for r in keys] + [0])
        cut = newest - a.since * 1_000_000
        frames = [r for r in frames if r["pts"] >= cut]
        keys = [r for r in keys if r["t"] >= cut]

    print(f"{len(frames)} frames, {len(keys)} keyDowns, "
          f"{sum('out' in r for r in frames)} frames with headset reports\n")
    print(f"{'stage (ms)':<34} {'p50':>7} {'p95':>7} {'max':>7} {'n':>6}")
    for name, a0, a1 in STAGES:
        print(row(name, [r[a1] - r[a0] for r in frames if a0 in r and a1 in r]))

    # Keypress -> first frame captured after it, per window.
    by_win = {}
    for r in frames:
        by_win.setdefault(r["id"], []).append(r)
    for wid, fs in sorted(by_win.items()):
        fs.sort(key=lambda r: r["pts"])
        cap, out = [], []
        i = 0
        for k in sorted(r["t"] for r in keys):
            while i < len(fs) and fs[i]["pts"] < k:
                i += 1
            if i < len(fs) and fs[i]["pts"] - k <= KEY_WINDOW:
                cap.append(fs[i]["pts"] - k)
                if "out" in fs[i]:
                    out.append(fs[i]["out"] - k)
        if cap:
            print(f"\nwindow {wid} ({len(fs)} frames)")
            print(row("keypress->capture", cap))
            print(row("keypress->out", out))


if __name__ == "__main__":
    main()
