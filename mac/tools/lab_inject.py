#!/usr/bin/env python3
"""Lab inject client for `castle-streamer --lab` (inject socket, newline-delimited JSON).

usage:
  lab_inject.py '{"type":"mode","control":true}' '{"type":"pointer","dx":5,"dy":0,"buttons":0}'   # send lines, print replies
  lab_inject.py '{"type":"command","cmd":"nudge","dtheta":1}'   # forward a v4 COMMAND as-is (minus "type")
  lab_inject.py --check            # act as the headset too: verify MODE/POINTER/CURSOR arrive and input is only logged
  [--port 7421] [--inject-port 7422]
"""
import argparse, json, socket, struct, sys, time

MODE, POINTER, CURSOR, WINDOW_LIST = 7, 6, 9, 1
SUBSCRIBE, FOCUS, CLICK, SCROLL, MOUSE = 10, 11, 12, 13, 17


def inject(port, lines):
    s = socket.create_connection(("127.0.0.1", port))
    f = s.makefile("rwb")
    replies = []
    for line in lines:
        f.write((line if isinstance(line, str) else json.dumps(line)).encode() + b"\n")
        f.flush()
        replies.append(json.loads(f.readline()))
    s.close()
    return replies


def recv_exact(s, n):
    b = bytearray()
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            raise ConnectionError("closed")
        b += c
    return bytes(b)


def recv_until(s, want, timeout=3.0):
    end = time.time() + timeout
    s.settimeout(0.2)
    while time.time() < end:
        try:
            t, wid, n = struct.unpack(">BII", recv_exact(s, 9))
            p = recv_exact(s, n)
        except socket.timeout:
            continue
        if t == want:
            return wid, p
    return None


def send_msg(s, t, wid=0, payload=b""):
    s.sendall(struct.pack(">BII", t, wid, len(payload)) + payload)


def check(a):
    ok = True

    def expect(cond, what):
        nonlocal ok
        print(("PASS" if cond else "FAIL") + ": " + what)
        ok = ok and cond

    r = inject(a.inject_port, ['{"type":"mode","control":true}'])[0]
    expect(r.get("ok") is False and "no headset" in r.get("error", ""), f"inject without client is refused: {r}")

    h = socket.create_connection(("127.0.0.1", a.port))
    wl = recv_until(h, WINDOW_LIST)
    windows = json.loads(wl[1]) if wl else []
    print("windows:", [(w["id"], w["app"], w["title"]) for w in windows])
    m = recv_until(h, MODE)
    expect(m is not None and json.loads(m[1]) == {"control": False}, f"MODE on connect: {m and m[1]}")

    r = inject(a.inject_port, ['{"type":"pointer","dx":1}'])[0]
    expect(r.get("ok") is False and "off" in r.get("error", ""), "pointer refused while control is off")

    r = inject(a.inject_port, ['{"type":"mode","control":true}'])[0]
    m = recv_until(h, MODE)
    expect(r == {"ok": True} and m and json.loads(m[1]) == {"control": True}, f"mode on -> MODE {m and m[1]}")

    inject(a.inject_port, ['{"type":"pointer","dx":3.5,"dy":-1,"buttons":2,"sy":-2,"mods":["cmd"]}'])
    p = recv_until(h, POINTER)
    expect(p and json.loads(p[1]) == {"dx": 3.5, "dy": -1, "buttons": 2, "sx": 0, "sy": -2, "mods": ["cmd"]}, f"POINTER {p and p[1]}")

    for name in ["arrow", "ibeam", "hand"]:
        r = inject(a.inject_port, [{"type": "cursor", "name": name}])[0]
        c = recv_until(h, CURSOR)
        if c:
            hx, hy, pw, ph = struct.unpack(">HHHH", c[1][:8])
            png = c[1][8:]
            expect(r == {"ok": True} and png[:8] == b"\x89PNG\r\n\x1a\n", f"CURSOR {name}: hotspot ({hx},{hy}) {pw}x{ph} pt, {len(png)} B PNG")
        else:
            expect(False, f"CURSOR {name} received")

    r = inject(a.inject_port, ['{"type":"cursor","name":"nope"}', "not json", '{"type":"bogus"}'])
    expect(all(x.get("ok") is False for x in r), f"bad commands -> errors: {[x.get('error') for x in r]}")

    if windows:
        wid = windows[0]["id"]
        send_msg(h, MOUSE, wid, b'{"kind":"down","x":0.42,"y":0.13,"button":0}')
        send_msg(h, CLICK, wid, b'{"x":0.5,"y":0.5}')
        send_msg(h, SCROLL, wid, b'{"x":0.5,"y":0.5,"dx":0,"dy":-3}')
        send_msg(h, FOCUS, wid)
        print(f"sent MOUSE/CLICK/SCROLL/FOCUS for window {wid}: check the streamer log for 'lab: would ...'")

    inject(a.inject_port, ['{"type":"mode","control":false}'])
    m = recv_until(h, MODE)
    expect(m and json.loads(m[1]) == {"control": False}, "mode off -> MODE false")
    h.close()
    print("lab check: " + ("ALL PASS" if ok else "FAILURES"))
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=7421)
    ap.add_argument("--inject-port", type=int, default=7422)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("lines", nargs="*")
    a = ap.parse_args()
    if a.check:
        sys.exit(0 if check(a) else 1)
    for r in inject(a.inject_port, a.lines):
        print(json.dumps(r))


if __name__ == "__main__":
    main()
