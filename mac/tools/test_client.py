#!/usr/bin/env python3
"""Mind Castle test client: list windows, subscribe to one, dump Annex-B H.264 for a few seconds.

usage: test_client.py [--seconds 5] [--match Chrome] [--out out.h264] [--host 127.0.0.1] [--port 7420] [--trace]

--trace acts like the headset's tracing side: PING every 1 s (offset from the lowest-RTT PONG of the last 10) and
synthetic FRAME_REPORTs every 0.5 s (recv = frame fully read, out = recv + --fake-decode-ms, both in Mac µs).
"""
import argparse, json, socket, struct, time

WINDOW_LIST, CODEC_CONFIG, FRAME, KEYFRAME, WINDOW_GONE = 1, 2, 3, 4, 5
PONG = 8
SUBSCRIBE, REQUEST_KEYFRAME, PING, FRAME_REPORT = 10, 14, 15, 16


def now_us():
    return time.monotonic_ns() // 1000


def recv_exact(s, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("server closed")
        buf += chunk
    return bytes(buf)


def recv_msg(s):
    t, wid, n = struct.unpack(">BII", recv_exact(s, 9))
    return t, wid, recv_exact(s, n)


def send_msg(s, t, wid=0, payload=b""):
    s.sendall(struct.pack(">BII", t, wid, len(payload)) + payload)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=7420)
    ap.add_argument("--seconds", type=float, default=5)
    ap.add_argument("--match", default="", help="substring of app/title to pick (default: Chrome, then Terminal, then first)")
    ap.add_argument("--out", default="out.h264")
    ap.add_argument("--trace", action="store_true", help="send PINGs and synthetic FRAME_REPORTs")
    ap.add_argument("--fake-decode-ms", type=float, default=5.0)
    a = ap.parse_args()

    s = socket.create_connection((a.host, a.port))
    t, _, p = recv_msg(s)
    assert t == WINDOW_LIST, t
    windows = json.loads(p)
    for w in windows:
        print(f"{w['id']:>7}  {w['w']}x{w['h']}  {w['app']} | {w['title']}")
    if not windows:
        print("no windows")
        return

    def pick():
        for m in ([a.match] if a.match else ["Chrome", "Terminal"]):
            for w in windows:
                if m.lower() in (w["app"] + " " + w["title"]).lower():
                    return w
        return windows[0]

    target = pick()
    print(f"subscribing to {target['id']} ({target['app']} | {target['title']})")
    send_msg(s, SUBSCRIBE, 0, json.dumps({"ids": [target["id"]]}).encode())

    counts = {CODEC_CONFIG: 0, FRAME: 0, KEYFRAME: 0}
    nbytes = 0
    first = last = None
    asked_key = False
    pongs = []  # (rtt, offset) samples
    reports = []
    next_ping = next_report = 0.0
    nreported = 0
    with open(a.out, "wb") as f:
        end = time.time() + a.seconds
        s.settimeout(0.05 if a.trace else 0.5)
        while time.time() < end:
            if a.trace and time.time() >= next_ping:
                send_msg(s, PING, 0, struct.pack(">Q", now_us()))
                next_ping = time.time() + 1
            if a.trace and reports and time.time() >= next_report:
                send_msg(s, FRAME_REPORT, 0, json.dumps(reports).encode())
                nreported += len(reports)
                reports = []
                next_report = time.time() + 0.5
            try:
                t, wid, p = recv_msg(s)
            except socket.timeout:
                continue
            if t == WINDOW_LIST:
                continue
            if t == PONG:
                t1 = now_us()
                t0, mac = struct.unpack(">QQ", p)
                pongs = (pongs + [(t1 - t0, mac - (t0 + t1) // 2)])[-10:]
                continue
            if wid != target["id"]:
                continue
            if t == WINDOW_GONE:
                print("WINDOW_GONE")
                break
            counts[t] = counts.get(t, 0) + 1
            if t == CODEC_CONFIG:
                f.write(p)
                print(f"CODEC_CONFIG {len(p)} bytes")
            elif t in (FRAME, KEYFRAME):
                pts = struct.unpack(">Q", p[:8])[0]
                if a.trace and pongs:
                    off = min(pongs)[1]
                    recv = now_us() + off
                    reports.append({"id": wid, "pts": pts, "recv": recv, "out": recv + int(a.fake_decode_ms * 1000)})
                first = first or time.time()
                last = time.time()
                nbytes += len(p)
                f.write(p[8:])
                if not asked_key and time.time() > end - a.seconds / 2:
                    send_msg(s, REQUEST_KEYFRAME, target["id"])  # exercise forced IDR mid-stream
                    asked_key = True
    if a.trace and reports:
        send_msg(s, FRAME_REPORT, 0, json.dumps(reports).encode())
        nreported += len(reports)
    s.close()

    frames = counts[FRAME] + counts[KEYFRAME]
    dur = a.seconds
    print(f"frames={frames} keyframes={counts[KEYFRAME]} configs={counts[CODEC_CONFIG]} "
          f"fps={frames / dur:.1f} Mbps={nbytes * 8 / dur / 1e6:.2f} -> {a.out}")
    if a.trace:
        if pongs:
            rtt, off = min(pongs)
            print(f"pongs={len(pongs)} min_rtt={rtt} us offset={off} us reported={nreported}")
        else:
            print("no PONGs received")


if __name__ == "__main__":
    main()
