#!/usr/bin/env python3
"""How do injected cursor warps leak into physical trackpad deltas?

usage: fit_warp.py trace.jsonl      (trace recorded with `castle-streamer --trace-input`)

For each raw physical move ("mv"), sums the injected warps ("warp": to - from) since the previous physical move (W)
and reports: how often a move follows a warp, the projection k of the delta on W (delta ~ real + k*W), and
path/net per gesture (gaps > 150 ms) for the raw deltas vs. compensated deltas (delta - k*W, for k = 1 and fitted k).
A smooth gesture has path/net near 1; warp contamination inflates it.
"""
import json, math, sys


def main():
    evs = []
    for line in open(sys.argv[1]):
        try:
            o = json.loads(line)
        except ValueError:
            continue
        if o.get("type") in ("mv", "warp"):
            evs.append(o)
    evs.sort(key=lambda e: e["t"])
    moves, wx, wy, last_warp_t = [], 0.0, 0.0, None
    for e in evs:
        if e["type"] == "warp":
            wx += e["x1"] - e["x0"]; wy += e["y1"] - e["y0"]; last_warp_t = e["t"]
        else:
            age = (e["t"] - last_warp_t) / 1000 if last_warp_t is not None and (wx or wy) else None
            moves.append((e["t"], e["dx"], e["dy"], wx, wy, age))
            wx = wy = 0.0
    if not moves:
        sys.exit("no mv events (record with --trace-input)")
    with_w = [m for m in moves if m[3] or m[4]]
    num = sum(m[1] * m[3] + m[2] * m[4] for m in with_w)
    den = sum(m[3] ** 2 + m[4] ** 2 for m in with_w) or 1
    k = num / den
    print(f"{len(moves)} physical moves, {len(with_w)} preceded by a warp "
          f"(median warp age {sorted(m[5] for m in with_w)[len(with_w)//2] if with_w else 0:.1f} ms)")
    print(f"projection k (delta ~ real + k*W): {k:.3f}")

    def gestures(kk):
        out, cur = [], []
        for t, dx, dy, w_x, w_y, _ in moves:
            if cur and t - cur[-1][0] > 150_000:
                out.append(cur); cur = []
            cur.append((t, dx - kk * w_x, dy - kk * w_y))
        if cur:
            out.append(cur)
        ratios = []
        for g in out:
            path = sum(math.hypot(dx, dy) for _, dx, dy in g)
            net = math.hypot(sum(d[1] for d in g), sum(d[2] for d in g))
            if net > 20:
                ratios.append(path / net)
        ratios.sort()
        return len(out), ratios[len(ratios) // 2] if ratios else float("nan")

    for name, kk in (("raw", 0.0), ("minus W (k=1)", 1.0), (f"minus k*W (k={k:.2f})", k)):
        n, med = gestures(kk)
        print(f"  {name:22s} median path/net over {n} gestures: {med:.2f}")


if __name__ == "__main__":
    main()
