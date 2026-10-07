#!/usr/bin/env python3
"""Stand-in for `castle-streamer --lab` when the Mac side can't capture (no Screen Recording grant, rebuilds).
Same ports and inject socket; speaks PROTOCOL.md to the headset: WINDOW_LIST of 3 lab windows, looping
ffmpeg testsrc2 H.264 per subscribed window, MODE/POINTER/CURSOR from the inject socket, PONG.
Headset input (MOUSE/CLICK/SCROLL/FOCUS) is logged in the same `lab: would ...` format as the real streamer.

Usage: fake_streamer.py [--port 7421] [--inject-port 7422]"""
import json
import os
import re
import select
import socket
import struct
import subprocess
import sys
import threading
import time
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "out", "fake-video")

# (id, title, pixel w, h, border colour). Ids are fixed so scenarios can name windows.
# FAKE_ID_BASE shifts the ids, to simulate the Mac app restarting (new CGWindowIDs, same titles).
_B = int(os.environ.get("FAKE_ID_BASE", "9000"))
WINDOWS = [(_B + 1, "Lab Editor", 1800, 1464, "red"), (_B + 2, "Lab Browser", 1600, 1344, "lime"),
           (_B + 3, "Lab Terminal", 1400, 1000, "dodgerblue")]
FPS = 15  # encoded frame rate
# Sent frame rate (clip plays in slow motion). The emulator's software decoder runs on the app's network thread,
# so more video delays POINTER behind it (head-of-line); 2 fps keeps input snappy. Override with FAKE_FPS.
SEND_FPS = float(os.environ.get("FAKE_FPS", "2"))


def log(s):
    print(time.strftime("[%H:%M:%S] ") + s, flush=True)


def mac_us():
    return time.monotonic_ns() // 1000


# ---------------------------------------------------------------- video


def clip(wid, w, h, color):
    """Annex-B H.264 for one window: (sps+pps, [(is_key, access_unit)]). 2 s loop, IDR at the start."""
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, f"{wid}.h264")
    if not os.path.exists(path):
        vw, vh = w // 2 // 2 * 2, h // 2 // 2 * 2
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
            "-i", f"testsrc2=s={vw}x{vh}:r={FPS}:d=2",
            "-vf", f"drawgrid=w={vw // 10}:h={vh // 10}:t=2:c=white@0.4,drawbox=x=0:y=0:w=iw:h=ih:t=16:c={color}",
            "-c:v", "libx264", "-threads", "1", "-x264-params", "sliced-threads=0:slices=1", "-profile:v", "baseline",
            "-preset", "veryfast", "-tune", "zerolatency", "-b:v", "2M", "-g", str(2 * FPS), "-pix_fmt", "yuv420p",
            "-bsf:v", "h264_mp4toannexb", "-f", "h264", path], check=True, timeout=60)
    data = open(path, "rb").read()
    starts = [m.start() for m in re.finditer(b"\x00\x00\x00\x01|(?<!\x00)\x00\x00\x01", data)]
    nals = [data[a:b] for a, b in zip(starts, starts[1:] + [len(data)])]
    config, aus, pending = b"", [], b""
    for n in nals:
        t = n[n.index(b"\x01") + 1] & 31
        if t in (7, 8):
            if not aus:
                config += n
            continue
        pending += n
        if t in (1, 5):
            aus.append((t == 5, pending))
            pending = b""
    return config, aus


# ---------------------------------------------------------------- cursor PNGs


def png(w, h, pixels):
    """RGBA rows -> PNG bytes."""
    raw = b"".join(b"\x00" + bytes(row) for row in pixels)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def cursor_payload(name):
    """PROTOCOL v3 CURSOR: hotspot (px), logical size (pt), PNG. 32x32 px images at 16x16 pt."""
    S = 32
    rows = []
    for y in range(S):
        row = []
        for x in range(S):
            if name == "ibeam":
                on = 14 <= x <= 17 or ((y < 3 or y > 28) and 8 <= x <= 23)
                edge = False
            elif name == "hand":
                on = (x - 16) ** 2 + (y - 16) ** 2 < 120
                edge = 100 < (x - 16) ** 2 + (y - 16) ** 2 < 120
            else:  # arrow: right triangle from the top-left hotspot
                on = x <= y * 0.7 and y < 26
                edge = on and (x == 0 or x >= int(y * 0.7) - 1 or y == 25)
            row += ([0, 0, 0, 255] if edge else [255, 255, 255, 255]) if on else [0, 0, 0, 0]
        rows.append(row)
    hot = {"ibeam": (16, 16), "hand": (16, 16)}.get(name, (1, 1))
    return struct.pack(">HHHH", hot[0], hot[1], 16, 16) + png(S, S, rows)


# ---------------------------------------------------------------- server


class Fake:
    def __init__(self):
        self.lock = threading.Lock()
        self.sock = None
        self.control = False
        self.cursor = "arrow"
        self.subs = {}  # id -> frame index
        self.clips = {wid: clip(wid, w, h, c) for wid, _, w, h, c in WINDOWS}

    def send(self, t, wid, payload):
        with self.lock:
            s = self.sock
            if s is None:
                return
            try:
                s.sendall(struct.pack(">BII", t, wid, len(payload)) + payload)
            except OSError:
                pass

    def window_list(self):
        return json.dumps([{"id": i, "app": "castle-testwin", "title": t, "w": w, "h": h} for i, t, w, h, _ in WINDOWS]).encode()

    def serve(self, port):
        ls = socket.socket()
        ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        ls.bind(("127.0.0.1", port))
        ls.listen(4)
        log(f"listening on 127.0.0.1:{port}")
        log("lab: window list " + ", ".join(f"{i}:{t}" for i, t, *_ in WINDOWS))
        threading.Thread(target=self.ticker, daemon=True).start()
        threading.Thread(target=self.video, daemon=True).start()
        while True:
            c, _ = ls.accept()
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            # Small send buffer + skip video ticks while it's full, so POINTER never queues behind seconds of video.
            c.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 64 * 1024)
            with self.lock:
                if self.sock:
                    self.sock.close()
                self.sock = c
                self.subs = {}
            log("client connected")
            self.send(1, 0, self.window_list())
            self.send(7, 0, json.dumps({"control": self.control}).encode())
            threading.Thread(target=self.read, args=(c,), daemon=True).start()

    def read(self, c):
        f = c.makefile("rb")
        try:
            while True:
                hdr = f.read(9)
                if len(hdr) < 9:
                    break
                t, wid, n = struct.unpack(">BII", hdr)
                p = f.read(n)
                self.handle(t, wid, p)
        except OSError:
            pass
        with self.lock:
            if self.sock is c:
                self.sock = None
        log("client disconnected")

    def handle(self, t, wid, p):
        if t == 10:
            ids = json.loads(p)["ids"]
            log(f"subscribe {ids}")
            with self.lock:
                self.subs = {i: self.subs.get(i, -1) for i in ids}
            for i in ids:
                if i in self.clips:
                    self.restart(i)
        elif t == 14:
            # Like the real streamer: force an IDR, no new CODEC_CONFIG (that would tear the decoder down again).
            with self.lock:
                if wid in self.subs:
                    self.subs[wid] = 0
        elif t == 15:
            self.send(8, 0, p[:8] + struct.pack(">Q", mac_us()))
        elif t in (11, 12, 13, 17):
            o = json.loads(p) if p else {}
            name = {11: "FOCUS", 12: "CLICK", 13: "SCROLL", 17: "MOUSE"}[t]
            s = f"lab: would {name}"
            if t == 17:
                s += f" {o.get('kind', '?')}" + (" right" if o.get("button") == 1 else "")
            s += f" window {wid}"
            if t != 11:
                s += " at (%.3g,%.3g)" % (o.get("x", -1), o.get("y", -1))
            if t == 13:
                s += " d=(%s,%s)" % (o.get("dx"), o.get("dy"))
            log(s)

    def restart(self, wid):
        if wid not in self.clips:
            return
        self.send(2, wid, self.clips[wid][0])
        with self.lock:
            if wid in self.subs:
                self.subs[wid] = 0

    def video(self):
        nxt = time.monotonic()
        while True:
            nxt += 1 / SEND_FPS
            time.sleep(max(0, nxt - time.monotonic()))
            with self.lock:
                items = [(i, k) for i, k in self.subs.items() if k >= 0]
                s = self.sock
            if s is None or not select.select([], [s], [], 0)[1]:
                continue
            for wid, k in items:
                aus = self.clips[wid][1]
                key, au = aus[k % len(aus)]
                self.send(4 if key else 3, wid, struct.pack(">Q", mac_us()) + au)
                with self.lock:
                    if wid in self.subs:
                        self.subs[wid] = (k + 1) % len(aus)

    def ticker(self):
        while True:
            time.sleep(2)
            self.send(1, 0, self.window_list())

    def inject(self, obj):
        t = obj.get("type")
        if t == "mode":
            self.control = bool(obj.get("control"))
            log(f"control mode {'ON' if self.control else 'OFF'} (fake)")
            self.send(7, 0, json.dumps({"control": self.control}).encode())
            if self.control:
                self.send(9, 0, cursor_payload(self.cursor))
        elif t == "pointer":
            if not self.control:
                return "control mode is off"
            p = {k: obj.get(k, 0) for k in ("dx", "dy", "buttons", "sx", "sy")}
            p["mods"] = obj.get("mods", [])
            self.send(6, 0, json.dumps(p).encode())
        elif t == "fake_resize":  # fake only: change a window's reported size (next WINDOW_LIST); video keeps its size
            for i, w in enumerate(WINDOWS):
                if w[0] == obj.get("id"):
                    WINDOWS[i] = (w[0], w[1], int(obj["w"]), int(obj["h"]), w[4])
                    self.send(1, 0, self.window_list())
                    return None
            return "no such window"
        elif t == "command":
            cmd = {k: v for k, v in obj.items() if k != "type"}
            if not isinstance(cmd.get("cmd"), str):
                return 'command needs "cmd": string'
            log("lab: COMMAND " + json.dumps(cmd, sort_keys=True))
            self.send(20, 0, json.dumps(cmd).encode())
        elif t == "cursor":
            self.cursor = obj.get("name", "arrow")
            if self.control:
                self.send(9, 0, cursor_payload(self.cursor))
        else:
            return f"unknown type {t}"
        return None

    def serve_inject(self, port):
        ls = socket.socket()
        ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        ls.bind(("127.0.0.1", port))
        ls.listen(4)
        log(f"lab: inject socket on 127.0.0.1:{port}")

        def one(c):
            f = c.makefile("rb")
            for line in f:
                if not line.strip():
                    continue
                try:
                    err = self.inject(json.loads(line))
                except ValueError:
                    err = "invalid JSON"
                c.sendall((json.dumps({"ok": True} if err is None else {"ok": False, "error": err}) + "\n").encode())
        while True:
            c, _ = ls.accept()
            threading.Thread(target=one, args=(c,), daemon=True).start()


def main():
    a = sys.argv
    port = int(a[a.index("--port") + 1]) if "--port" in a else 7421
    iport = int(a[a.index("--inject-port") + 1]) if "--inject-port" in a else port + 1
    f = Fake()
    threading.Thread(target=f.serve_inject, args=(iport,), daemon=True).start()
    f.serve(port)


if __name__ == "__main__":
    main()
