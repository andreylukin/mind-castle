import AppKit
import CoreMedia
import Foundation

private let timebase: mach_timebase_info_data_t = {
    var t = mach_timebase_info_data_t()
    mach_timebase_info(&t)
    return t
}()

/// Mac host clock in microseconds: `mach_absolute_time`, the clock ScreenCaptureKit pts and `CMClockGetHostTimeClock` use.
func hostMicros() -> UInt64 {
    mach_absolute_time() * UInt64(timebase.numer) / UInt64(timebase.denom) / 1000
}

/// Host-clock CMTime -> µs, exactly as sent in FRAME/KEYFRAME `ptsMicros`.
func micros(_ t: CMTime) -> UInt64 {
    UInt64(max(0, CMTimeGetSeconds(t) * 1_000_000))
}

/// Per-frame latency trace, one JSON line per frame. Mac-side stamps are held per (window id, pts) until the
/// headset's FRAME_REPORT for that frame arrives; frames never reported are written without recv/out after
/// `reportTimeout`. Keypresses (timestamps only, never key codes) are logged as `{"type":"key"}` lines.
final class Trace {
    static let shared = Trace()
    private let q = DispatchQueue(label: "castle.trace")
    private var file: FileHandle?
    private var timer: DispatchSourceTimer?

    private struct Key: Hashable { let id: UInt32; let pts: UInt64 }
    private var pending: [Key: [String: Any]] = [:]
    private var keys: [UInt64] = [] // recent keyDown host µs, ascending
    private let reportTimeout: UInt64 = 3_000_000
    private let keyWindow: UInt64 = 500_000

    /// Truncates the trace file. The default port writes trace.jsonl; other ports (test instances) write
    /// trace-<port>.jsonl so they never clobber the live streamer's trace.
    func start(port: UInt16) {
        let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/mind-castle")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(port == 7420 ? "trace.jsonl" : "trace-\(port).jsonl")
        FileManager.default.createFile(atPath: url.path, contents: nil)
        q.sync { file = try? FileHandle(forWritingTo: url) }
        log("trace -> \(url.path)")
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [self] in flushStale() }
        t.resume()
        timer = t
    }

    /// `--trace-input`: raw physical moves and injected warps, to fit how warps leak into physical deltas.
    var traceInput = false

    /// One raw physical mouse/trackpad move as seen by the control tap (before filtering).
    func rawMove(dx: Double, dy: Double, dropped: Bool) {
        guard traceInput else { return }
        let t = hostMicros()
        q.async { [self] in write(["type": "mv", "t": t, "dx": dx, "dy": dy, "dropped": dropped]) }
    }

    /// One injected cursor move (MOUSE/CLICK warp of the hidden cursor), in global points.
    func warp(from: CGPoint, to: CGPoint) {
        guard traceInput else { return }
        let t = hostMicros()
        q.async { [self] in write(["type": "warp", "t": t, "x0": from.x, "y0": from.y, "x1": to.x, "y1": to.y]) }
    }

    /// One POINTER as sent in control mode (trackpad deltas only, no screen content), for gesture replay tests.
    func pointer(dx: Double, dy: Double, buttons: Int, sx: Double, sy: Double) {
        let t = hostMicros()
        q.async { [self] in write(["type": "ptr", "t": t, "dx": dx, "dy": dy, "buttons": buttons, "sx": sx, "sy": sy]) }
    }

    func key(at t: UInt64, tap: UInt64, isRepeat: Bool) {
        q.async { [self] in
            keys.append(t)
            if keys.count > 64 { keys.removeFirst(keys.count - 64) }
            write(["type": "key", "t": t, "tap": tap, "repeat": isRepeat])
        }
    }

    /// Mac-side stamps for an encoded frame, recorded just before it is handed to the socket writer.
    func frame(id: UInt32, pts: UInt64, sck: UInt64, submit: UInt64, done: UInt64, bytes: Int, keyframe: Bool) {
        q.async { [self] in
            pending[Key(id: id, pts: pts)] = ["type": "frame", "id": id, "pts": pts, "sckCallback": sck, "encodeSubmit": submit,
                                              "encodeDone": done, "bytes": bytes, "kf": keyframe]
        }
    }

    func sent(id: UInt32, pts: UInt64, at t: UInt64) {
        q.async { [self] in pending[Key(id: id, pts: pts)]?["sent"] = t }
    }

    /// FRAME_REPORT payload: `[{"id":630,"pts":123,"recv":456,"out":789}]`, times already in Mac µs.
    func report(_ payload: Data) {
        guard let arr = (try? JSONSerialization.jsonObject(with: payload)) as? [[String: Any]] else {
            log("bad FRAME_REPORT payload (\(payload.count) bytes)")
            return
        }
        q.async { [self] in
            for r in arr {
                guard let id = (r["id"] as? NSNumber)?.uint32Value, let pts = (r["pts"] as? NSNumber)?.uint64Value,
                      var rec = pending.removeValue(forKey: Key(id: id, pts: pts)) else { continue }
                rec["recv"] = r["recv"]
                rec["out"] = r["out"]
                finish(rec, pts: pts)
            }
        }
    }

    private func flushStale() {
        let now = hostMicros()
        for (k, rec) in pending where k.pts + reportTimeout < now {
            pending[k] = nil
            finish(rec, pts: k.pts)
        }
    }

    /// Adds `key`/`afterKeyMs` from the latest keyDown at or before pts (within 500 ms), then writes the line.
    private func finish(_ rec: [String: Any], pts: UInt64) {
        var rec = rec
        if let k = keys.last(where: { $0 <= pts }), pts - k <= keyWindow {
            rec["key"] = k
            rec["afterKeyMs"] = Double((pts - k) / 100) / 10
        }
        write(rec)
    }

    private func write(_ obj: [String: Any]) {
        guard let file, var line = try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys]) else { return }
        line.append(0x0A)
        file.write(line)
    }
}

private var keyTap: CFMachPort?

/// Listen-only keyDown tap (needs Accessibility) that marks keypresses in the trace.
func installKeyTap() {
    let mask = CGEventMask(1 << CGEventType.keyDown.rawValue)
    keyTap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .listenOnly,
                               eventsOfInterest: mask, callback: { _, type, ev, _ in
        if type == .keyDown {
            let tap = hostMicros()
            // NSEvent.timestamp is seconds on the same mach uptime clock: the HID time, earlier than this callback.
            let evt = NSEvent(cgEvent: ev).map { UInt64($0.timestamp * 1_000_000) } ?? 0
            let t = evt > 0 && evt <= tap && tap - evt < 1_000_000 ? evt : tap
            Trace.shared.key(at: t, tap: tap, isRepeat: ev.getIntegerValueField(.keyboardEventAutorepeat) != 0)
        } else if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput, let keyTap {
            CGEvent.tapEnable(tap: keyTap, enable: true)
        }
        return Unmanaged.passUnretained(ev)
    }, userInfo: nil)
    guard let keyTap else {
        log("key tap: CGEvent.tapCreate failed (Accessibility missing?) - no keypress marks in trace")
        return
    }
    CFRunLoopAddSource(CFRunLoopGetMain(), CFMachPortCreateRunLoopSource(nil, keyTap, 0), .commonModes)
    CGEvent.tapEnable(tap: keyTap, enable: true)
    log("key tap: marking keyDown times in trace")
}
