import AppKit

extension Msg {
    static let pointer: UInt8 = 6
    static let mode: UInt8 = 7
    static let mouse: UInt8 = 17
    static let command: UInt8 = 20
}

/// kCGEventSourceUserData value on events the streamer posts itself (see Input.swift); the control tap passes them.
let castleEventTag: Int64 = 0x4D43_4153 // "MCAS"

/// Accumulated pointer activity between POINTER sends.
struct PointerAccum {
    var dx = 0.0, dy = 0.0, sx = 0.0, sy = 0.0
    var buttons = 0
    var mods: [String] = []
    var dirty = false

    /// Serializes the current state and zeroes the deltas (buttons and mods are state, not deltas).
    mutating func take() -> Data {
        let obj: [String: Any] = ["dx": dx, "dy": dy, "sx": sx, "sy": sy, "buttons": buttons, "mods": mods]
        dx = 0; dy = 0; sx = 0; sy = 0; dirty = false
        return (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data()
    }
}

/// Control mode: ⌃⌥⌘M toggles; while on, physical mouse/trackpad events are swallowed and forwarded as POINTER,
/// and the built-in display is blacked out via gamma. All state lives on the tap thread (`perform` hops there).
final class ControlMode {
    static let sendInterval = 1.0 / 120
    static let hotkeyCode: Int64 = 46 // kVK_ANSI_M

    private(set) var isOn = false
    private var send: ((UInt8, Data) -> Void)?
    private var acc = PointerAccum()
    private var lastSent = -1.0
    private var flushPending = false
    private var swallowedDown = Set<Int64>() // keycodes whose keyDown we swallowed; their keyUp is swallowed too

    // Side effects; the self-test swaps these for recorders.
    var now: () -> Double = { ProcessInfo.processInfo.systemUptime }
    var after: (Double, @escaping () -> Void) -> Void = { _, _ in }
    var apply: (Bool) -> Void = { _ in }

    private var runLoop: CFRunLoop?
    private var keyTap: CFMachPort?
    private var mouseTap: CFMachPort?
    private var mouseSource: CFRunLoopSource?
    private var gammaTimer: Timer?
    private var cursor: CursorWatcher?
    private var lab = false

    // Spurious physical deltas (PROTOCOL v1 POINTER): when an injected MOUSE move/click warps the hidden cursor far,
    // macOS can add the warp to the next physical event's delta (seen: single events of 250-1745 pt vs p99 ~91).
    static let spikeFloor = 200.0, spikeFactor = 8.0, postWarpLimit = 100.0, bigJump = 150.0
    private var recentMags: [Double] = [] // magnitudes of the last 20 accepted physical moves
    private var warpPending = false
    private let warpLock = NSLock()
    private(set) var droppedSpikes = 0

    /// From Input.swift (any thread): an injected event moved the cursor by `distance` points.
    func noteInjectedJump(_ distance: Double) {
        guard distance > Self.bigJump else { return }
        warpLock.lock(); warpPending = true; warpLock.unlock()
    }

    /// True if this physical delta is a warp artifact or an outlier: drop it.
    private func isSpurious(_ dx: Double, _ dy: Double) -> Bool {
        let m = hypot(dx, dy)
        warpLock.lock()
        let afterWarp = warpPending
        warpPending = false
        warpLock.unlock()
        let median = recentMags.isEmpty ? 0 : recentMags.sorted()[recentMags.count / 2]
        let limit = afterWarp ? Self.postWarpLimit : max(Self.spikeFloor, Self.spikeFactor * median)
        if m > limit {
            droppedSpikes += 1
            log("control: dropped spurious delta (\(Int(dx)),\(Int(dy)))\(afterWarp ? " after an injected jump" : "")")
            return true
        }
        if m > 0 {
            recentMags.append(m)
            if recentMags.count > 20 { recentMags.removeFirst() }
        }
        return false
    }
    /// Key hook for other features (push-to-talk, v5): sees every key event the ⌃⌥⌘ hotkeys didn't swallow, except
    /// events tagged `castleEventTag` (our own injected keys pass straight through). Args: event type (.keyDown/.keyUp;
    /// autorepeat via `.keyboardEventAutorepeat`), the event, control mode on. Return true to swallow.
    /// Runs on the tap thread and must not block (macOS disables a tap that stalls ~1 s). Set it before `start()`,
    /// or via `perform`.
    var keyHook: ((CGEventType, CGEvent, Bool) -> Bool)?
    /// Called (on the tap thread) whenever control mode turns on or off.
    var onModeChange: (Bool) -> Void = { _ in }

    // MARK: live wiring

    /// Starts the tap thread and installs the always-on hotkey tap (swallows only ⌃⌥⌘M).
    /// `lab`: the thread only (state for `labInject`); no taps, no gamma/cursor side effects, no cursor polling.
    func start(lab: Bool = false) {
        if lab {
            self.lab = true
            let ready = DispatchSemaphore(value: 0)
            Thread { [self] in
                runLoop = CFRunLoopGetCurrent()
                // A run loop with no sources returns at once; keep it alive.
                CFRunLoopAddTimer(runLoop, CFRunLoopTimerCreateWithHandler(nil, .greatestFiniteMagnitude, 0, 0, 0) { _ in }, .commonModes)
                ready.signal()
                CFRunLoopRun()
            }.start()
            ready.wait()
            log("control: lab mode, no event taps; MODE/POINTER/CURSOR come from the inject socket")
            return
        }
        after = { [weak self] delay, f in
            guard let rl = self?.runLoop else { return }
            let t = CFRunLoopTimerCreateWithHandler(nil, CFAbsoluteTimeGetCurrent() + delay, 0, 0, 0) { _ in f() }
            CFRunLoopAddTimer(rl, t, .commonModes)
        }
        apply = { [weak self] on in self?.applyLive(on) }
        cursor = CursorWatcher { [weak self] png in self?.perform { self?.send?(Msg.cursor, png) } }
        onTermination { ControlMode.restoreDisplayAndCursor() }

        let ready = DispatchSemaphore(value: 0)
        let t = Thread { [self] in
            runLoop = CFRunLoopGetCurrent()
            let mask = CGEventMask(1 << CGEventType.keyDown.rawValue) | CGEventMask(1 << CGEventType.keyUp.rawValue)
            keyTap = makeTap(mask)
            if let keyTap {
                CFRunLoopAddSource(runLoop, CFMachPortCreateRunLoopSource(nil, keyTap, 0), .commonModes)
                log("control: hotkey ⌃⌥⌘M ready")
            } else {
                log("control: could not create hotkey event tap (Accessibility permission?) — control mode unavailable")
            }
            ready.signal()
            CFRunLoopRun()
        }
        t.name = "castle.control"
        t.qualityOfService = .userInteractive
        t.start()
        ready.wait()
    }

    /// Runs `f` on the tap thread (inline before `start`, i.e. in the self-test).
    func perform(_ f: @escaping () -> Void) {
        guard let rl = runLoop else { return f() }
        CFRunLoopPerformBlock(rl, CFRunLoopMode.commonModes.rawValue, f)
        CFRunLoopWakeUp(rl)
    }

    /// Enter control mode automatically when a headset connects (it connects only while the app is in use).
    /// `--no-auto-control` turns this off; a manual ⌃⌥⌘M OFF sticks until the next connect.
    var autoControl = true

    func clientConnected(_ send: @escaping (UInt8, Data) -> Void) {
        perform { [self] in
            self.send = send
            if autoControl && !isOn {
                log("control: auto ON (headset active)")
                setOn(true) // sends MODE {control:true}
            } else {
                sendMode()
            }
            if isOn, let cursor { DispatchQueue.main.async { cursor.start() } } // resend the current cursor
        }
    }

    func clientDisconnected() {
        perform { [self] in
            send = nil
            if isOn {
                log("control: client disconnected, leaving control mode")
                setOn(false)
            }
        }
    }

    /// Lab inject command (see Lab.swift). Returns an error message, or nil on success.
    func labInject(_ obj: [String: Any]) -> String? {
        var err: String?
        let done = DispatchSemaphore(value: 0)
        perform { [self] in
            defer { done.signal() }
            guard send != nil else { err = "no headset client connected"; return }
            let num = { (k: String) in (obj[k] as? NSNumber)?.doubleValue ?? 0 }
            switch obj["type"] as? String {
            case "mode":
                guard let on = obj["control"] as? Bool else { err = "mode needs \"control\": bool"; return }
                if on == isOn { sendMode() } else { setOn(on) } // apply is a no-op in lab
            case "pointer":
                guard isOn else { err = "control mode is off (send {\"type\":\"mode\",\"control\":true} first)"; return }
                var a = PointerAccum()
                (a.dx, a.dy, a.sx, a.sy) = (num("dx"), num("dy"), num("sx"), num("sy"))
                a.buttons = Int(num("buttons"))
                a.mods = obj["mods"] as? [String] ?? []
                send?(Msg.pointer, a.take())
            case "command":
                var cmd = obj
                cmd["type"] = nil
                guard cmd["cmd"] is String else { err = "command needs \"cmd\": string"; return }
                let data = (try? JSONSerialization.data(withJSONObject: cmd, options: [.sortedKeys])) ?? Data()
                log("lab: COMMAND \(String(decoding: data, as: UTF8.self))")
                send?(Msg.command, data)
            case "cursor":
                let cursors: [String: NSCursor] = ["arrow": .arrow, "ibeam": .iBeam, "hand": .pointingHand]
                guard let c = cursors[obj["name"] as? String ?? ""], let img = CursorImage(c) else {
                    err = "cursor name must be arrow|ibeam|hand"
                    return
                }
                log("lab: cursor \(obj["name"]!) \(img.summary)")
                send?(Msg.cursor, img.payload)
            default:
                err = "unknown type (mode|pointer|cursor|command|fail-stream)"
            }
        }
        done.wait()
        return err
    }

    private func makeTap(_ mask: CGEventMask) -> CFMachPort? {
        let cb: CGEventTapCallBack = { _, type, event, refcon in
            let me = Unmanaged<ControlMode>.fromOpaque(refcon!).takeUnretainedValue()
            return me.handle(type, event) ? nil : Unmanaged.passUnretained(event)
        }
        return CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
                                 eventsOfInterest: mask, callback: cb, userInfo: Unmanaged.passUnretained(self).toOpaque())
    }

    private static var mouseMask: CGEventMask {
        let types: [CGEventType] = [.leftMouseDown, .leftMouseUp, .rightMouseDown, .rightMouseUp, .mouseMoved,
                                    .leftMouseDragged, .rightMouseDragged, .scrollWheel, .tabletPointer, .tabletProximity,
                                    .otherMouseDown, .otherMouseUp, .otherMouseDragged]
        var m = types.reduce(CGEventMask(0)) { $0 | CGEventMask(1 << $1.rawValue) }
        // Trackpad gesture types (NSEventType rotate/beginGesture/endGesture/gesture/magnify/swipe/smartMagnify/pressure)
        // so pinches and force clicks don't reach Mac apps either.
        for raw: UInt64 in [18, 19, 20, 29, 30, 31, 32, 34] { m |= 1 << raw }
        return m
    }

    private func applyLive(_ on: Bool) {
        if on {
            if let tap = makeTap(Self.mouseMask) {
                mouseTap = tap
                mouseSource = CFMachPortCreateRunLoopSource(nil, tap, 0)
                CFRunLoopAddSource(runLoop, mouseSource, .commonModes)
            } else {
                log("control: could not create mouse event tap")
            }
            CGAssociateMouseAndMouseCursorPosition(0) // cursor stays put: no hot corners / Dock reveal
            Self.blackOutBuiltin()
            // macOS can reset gamma (Night Shift, True Tone, display reconfig); keep it black.
            let t = Timer(timeInterval: 1, repeats: true) { _ in Self.blackOutBuiltin() }
            RunLoop.current.add(t, forMode: .common)
            gammaTimer = t
            if let cursor { DispatchQueue.main.async { cursor.start() } }
        } else {
            if let cursor { DispatchQueue.main.async { cursor.stop() } }
            gammaTimer?.invalidate()
            gammaTimer = nil
            if let tap = mouseTap {
                CGEvent.tapEnable(tap: tap, enable: false)
                CFRunLoopRemoveSource(runLoop, mouseSource, .commonModes)
                CFMachPortInvalidate(tap)
            }
            mouseTap = nil
            mouseSource = nil
            Self.restoreDisplayAndCursor()
        }
    }

    private static func blackOutBuiltin() {
        var ids = [CGDirectDisplayID](repeating: 0, count: 16)
        var n: UInt32 = 0
        CGGetOnlineDisplayList(16, &ids, &n)
        let zeros = [CGGammaValue](repeating: 0, count: 256)
        for d in ids.prefix(Int(n)) where CGDisplayIsBuiltin(d) != 0 {
            CGSetDisplayTransferByTable(d, 256, zeros, zeros, zeros)
        }
    }

    static func restoreDisplayAndCursor() {
        CGDisplayRestoreColorSyncSettings()
        CGAssociateMouseAndMouseCursorPosition(1)
    }

    // MARK: event handling (pure apart from the side-effect closures; exercised by the self-test)

    /// Returns true to swallow the event.
    func handle(_ type: CGEventType, _ ev: CGEvent) -> Bool {
        switch type {
        case .tapDisabledByTimeout, .tapDisabledByUserInput:
            log("control: event tap disabled by macOS (\(type == .tapDisabledByTimeout ? "timeout" : "user input")), re-enabling")
            if let keyTap { CGEvent.tapEnable(tap: keyTap, enable: true) }
            if isOn, let mouseTap { CGEvent.tapEnable(tap: mouseTap, enable: true) }
            return false
        case .keyDown, .keyUp:
            return handleKey(type, ev)
        default:
            break
        }
        guard isOn, ev.getIntegerValueField(.eventSourceUserData) != castleEventTag else { return false }

        acc.mods = Self.mods(ev.flags)
        switch type {
        case .mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged:
            let dx = ev.getDoubleValueField(.mouseEventDeltaX), dy = ev.getDoubleValueField(.mouseEventDeltaY)
            if isSpurious(dx, dy) { return true } // still swallowed, just not forwarded
            acc.dx += dx
            acc.dy += dy
            acc.dirty = true
        case .scrollWheel:
            acc.sy += ev.getDoubleValueField(.scrollWheelEventFixedPtDeltaAxis1)
            acc.sx += ev.getDoubleValueField(.scrollWheelEventFixedPtDeltaAxis2)
            acc.dirty = true
        case .leftMouseDown, .leftMouseUp, .rightMouseDown, .rightMouseUp, .otherMouseDown, .otherMouseUp:
            // Button transitions bypass the rate limit so a fast click is never coalesced away.
            if acc.dirty { flush() }
            let bit = type == .leftMouseDown || type == .leftMouseUp ? 1 : type == .rightMouseDown || type == .rightMouseUp ? 2 : 4
            let down = type == .leftMouseDown || type == .rightMouseDown || type == .otherMouseDown
            acc.buttons = down ? acc.buttons | bit : acc.buttons & ~bit
            acc.dirty = true
            flush()
            return true
        default:
            return true // gestures, tablet: swallow, nothing to forward
        }
        scheduleFlush()
        return true
    }

    private func handleKey(_ type: CGEventType, _ ev: CGEvent) -> Bool {
        if ev.getIntegerValueField(.eventSourceUserData) == castleEventTag { return false }
        if handleHotkey(type, ev) { return true }
        return keyHook?(type, ev, isOn) ?? false
    }

    /// ⌃⌥⌘M and the v4 COMMAND hotkeys. Returns true if the event was swallowed.
    private func handleHotkey(_ type: CGEventType, _ ev: CGEvent) -> Bool {
        let code = ev.getIntegerValueField(.keyboardEventKeycode)
        if type == .keyUp {
            // Swallow the release of a swallowed press even if the modifiers were let go first.
            return swallowedDown.remove(code) != nil
        }
        let f = ev.flags
        guard f.contains(.maskControl), f.contains(.maskAlternate), f.contains(.maskCommand) else { return false }
        let isRepeat = ev.getIntegerValueField(.keyboardEventAutorepeat) != 0
        if code == Self.hotkeyCode {
            swallowedDown.insert(code)
            if !isRepeat {
                if !isOn && send == nil {
                    log("control: no headset connected, not entering control mode")
                } else {
                    setOn(!isOn)
                }
            }
            return true
        }
        // ⌃⌥⌘ spatial commands (PROTOCOL v4): only with a headset to receive them, else the keys pass through.
        guard let send, let (cmd, repeatable) = Self.command(keycode: code, shift: f.contains(.maskShift)) else { return false }
        swallowedDown.insert(code)
        if !isRepeat || repeatable {
            let data = (try? JSONSerialization.data(withJSONObject: cmd, options: [.sortedKeys])) ?? Data()
            if !isRepeat { log("control: COMMAND \(String(decoding: data, as: UTF8.self))") }
            send(Msg.command, data)
        }
        return true
    }

    /// ⌃⌥⌘ (+⇧) keycode -> COMMAND payload, and whether key repeat re-sends it. Shift only matters for Z and arrows.
    static func command(keycode: Int64, shift: Bool) -> ([String: Any], Bool)? {
        switch (keycode, shift) {
        case (123, false): return (["cmd": "nudge", "dtheta": -1], true) // ←
        case (124, false): return (["cmd": "nudge", "dtheta": 1], true) // →
        case (126, false): return (["cmd": "nudge", "dy": 1], true) // ↑
        case (125, false): return (["cmd": "nudge", "dy": -1], true) // ↓
        case (123, true): return (["cmd": "cutout", "dw": -1, "dh": 0], true) // ⇧← narrow
        case (124, true): return (["cmd": "cutout", "dw": 1, "dh": 0], true) // ⇧→ widen
        case (126, true): return (["cmd": "cutout", "dw": 0, "dh": 1], true) // ⇧↑ raise top edge
        case (125, true): return (["cmd": "cutout", "dw": 0, "dh": -1], true) // ⇧↓ lower top edge
        case (24, false): return (["cmd": "depth", "d": -1], true) // = closer
        case (27, false): return (["cmd": "depth", "d": 1], true) // - farther
        case (30, false): return (["cmd": "size", "d": 1], true) // ]
        case (33, false): return (["cmd": "size", "d": -1], true) // [
        case (8, false): return (["cmd": "center"], false) // C
        case (18, false): return (["cmd": "preset", "name": "editor"], false) // 1
        case (19, false): return (["cmd": "preset", "name": "side"], false) // 2
        case (20, false): return (["cmd": "preset", "name": "glance"], false) // 3
        case (6, false): return (["cmd": "undo"], false) // Z
        case (6, true): return (["cmd": "redo"], false) // ⇧Z
        case (15, false): return (["cmd": "recenter"], false) // R
        case (35, false): return (["cmd": "passthrough"], false) // P
        case (17, false): return (["cmd": "tidy"], false) // T
        case (47, false): return (["cmd": "dim", "d": 1], false) // .
        case (43, false): return (["cmd": "dim", "d": -1], false) // ,
        default: return nil
        }
    }

    private func setOn(_ on: Bool) {
        guard on != isOn else { return }
        isOn = on
        acc = PointerAccum()
        apply(on)
        onModeChange(on)
        log("control mode \(on ? lab ? "ON (lab: injected pointer only)" : "ON (mouse -> headset, built-in display black)" : "OFF")")
        sendMode()
    }

    private func sendMode() {
        send?(Msg.mode, Data("{\"control\":\(isOn)}".utf8))
    }

    private func scheduleFlush() {
        let t = now()
        if t - lastSent >= Self.sendInterval {
            flush()
        } else if !flushPending {
            flushPending = true
            after(lastSent + Self.sendInterval - t) { [weak self] in
                guard let self else { return }
                flushPending = false
                if isOn, acc.dirty { flush() }
            }
        }
    }

    private var statPointers = 0, statSince = 0.0

    private func flush() {
        lastSent = now()
        // Real trackpad input, recorded for test replay (deltas only).
        Trace.shared.pointer(dx: acc.dx, dy: acc.dy, buttons: acc.buttons, sx: acc.sx, sy: acc.sy)
        send?(Msg.pointer, acc.take())
        statPointers += 1
        if lastSent - statSince >= 2 {
            log("control: sent \(statPointers) POINTER msgs in last \(String(format: "%.1f", lastSent - statSince))s")
            statPointers = 0; statSince = lastSent
        }
    }

    private static func mods(_ f: CGEventFlags) -> [String] {
        var m: [String] = []
        if f.contains(.maskCommand) { m.append("cmd") }
        if f.contains(.maskAlternate) { m.append("opt") }
        if f.contains(.maskControl) { m.append("ctrl") }
        if f.contains(.maskShift) { m.append("shift") }
        return m
    }
}

let control = ControlMode()

// MARK: termination hooks (gamma, cursor, virtual-display window frames)

private var terminationHooks: [() -> Void] = []
private var terminationSources: [DispatchSourceSignal] = []
private let terminationLock = NSLock()

/// Runs `f` once at exit: SIGINT/SIGTERM/SIGHUP or a normal `exit()`. (A crash skips it; macOS still restores gamma.)
func onTermination(_ f: @escaping () -> Void) {
    terminationLock.lock()
    defer { terminationLock.unlock() }
    if terminationSources.isEmpty {
        for sig in [SIGINT, SIGTERM, SIGHUP] {
            signal(sig, SIG_IGN)
            let s = DispatchSource.makeSignalSource(signal: sig, queue: .global())
            s.setEventHandler {
                log("signal \(sig), cleaning up")
                exit(0) // atexit runs the hooks
            }
            s.resume()
            terminationSources.append(s)
        }
        atexit { runTerminationHooks() }
    }
    terminationHooks.append(f)
}

private func runTerminationHooks() {
    terminationLock.lock()
    let hooks = terminationHooks
    terminationHooks = []
    terminationLock.unlock()
    for h in hooks { h() }
}

// MARK: self-test (--dry-run-control): no taps installed, gamma and cursor untouched

func controlSelfTest() -> Bool {
    var ok = true
    func check(_ cond: Bool, _ what: String) {
        print("\(cond ? "PASS" : "FAIL"): \(what)")
        ok = ok && cond
    }

    let c = ControlMode()
    c.autoControl = false // the manual-toggle checks below start from OFF
    var clock = 0.0
    var timers: [(Double, () -> Void)] = []
    var applied: [Bool] = []
    var sent: [(UInt8, [String: Any])] = []
    c.now = { clock }
    c.after = { d, f in timers.append((clock + d, f)) }
    c.apply = { applied.append($0) }
    func advance(to t: Double) {
        clock = t
        let due = timers.filter { $0.0 <= t }
        timers.removeAll { $0.0 <= t }
        due.forEach { $0.1() }
    }
    let pointers = { sent.filter { $0.0 == Msg.pointer }.map { $0.1 } }
    func key(_ down: Bool, _ code: CGKeyCode = 46, _ flags: CGEventFlags = [.maskControl, .maskAlternate, .maskCommand], repeat_: Bool = false) -> Bool {
        let e = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down)!
        e.flags = flags
        if repeat_ { e.setIntegerValueField(.keyboardEventAutorepeat, value: 1) }
        return c.handle(down ? .keyDown : .keyUp, e)
    }
    func mouse(_ type: CGEventType, dx: Double = 0, dy: Double = 0, flags: CGEventFlags = [], tagged: Bool = false) -> Bool {
        let e = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: .zero, mouseButton: .left)!
        e.setDoubleValueField(.mouseEventDeltaX, value: dx)
        e.setDoubleValueField(.mouseEventDeltaY, value: dy)
        e.flags = flags
        if tagged { tagInjected(e) }
        return c.handle(type, e)
    }
    func num(_ v: Any?) -> Double { (v as? NSNumber)?.doubleValue ?? .nan }

    check(key(true) && key(false) && !c.isOn && applied.isEmpty, "hotkey without a client is swallowed but does not turn control on")

    c.clientConnected { t, p in sent.append((t, (try? JSONSerialization.jsonObject(with: p)) as? [String: Any] ?? [:])) }
    check(sent.count == 1 && sent[0].0 == Msg.mode && sent[0].1["control"] as? Bool == false, "MODE {control:false} on connect")

    check(!mouse(.mouseMoved, dx: 5), "mouse passes through while off")
    check(!key(true, 0, []) && !key(true, 46, [.maskCommand]), "other keys (incl. ⌘M) pass through")
    check(key(true) && c.isOn && applied == [true], "⌃⌥⌘M turns control on and applies side effects")
    check(sent.last?.0 == Msg.mode && sent.last?.1["control"] as? Bool == true, "MODE {control:true} sent on toggle")
    check(key(true, repeat_: true) && c.isOn, "autorepeat swallowed without toggling")
    check(key(false, 46, []) && !key(false, 46, []), "hotkey keyUp swallowed once, even after modifiers released")
    check(!key(true, 0, []), "typing passes through while on")

    // Coalescing: 50 moves within 5 ms -> one immediate send + one timer flush, deltas conserved.
    advance(to: 10)
    sent.removeAll()
    for i in 0..<50 {
        clock = 10 + Double(i) * 0.0001
        _ = mouse(.mouseMoved, dx: 1, dy: -2)
    }
    check(pointers().count == 1, "burst coalesced: \(pointers().count) send(s) before timer")
    advance(to: 10.02)
    let ps = pointers()
    check(ps.count == 2, "pending flush fired once")
    check(ps.reduce(0) { $0 + num($1["dx"]) } == 50 && ps.reduce(0) { $0 + num($1["dy"]) } == -100, "dx/dy conserved across sends")

    // Rate: 1 kHz events for 1 s -> at most ~120 sends.
    sent.removeAll()
    for i in 1...1000 {
        advance(to: 11 + Double(i) * 0.001)
        _ = mouse(.mouseMoved, dx: 1)
    }
    advance(to: 13)
    check(pointers().count <= 121 && pointers().count >= 100, "1 kHz input -> \(pointers().count) POINTER/s (≤120)")
    check(pointers().reduce(0) { $0 + num($1["dx"]) } == 1000, "no delta lost under rate limit")

    // Fast click inside one rate window still produces down and up.
    sent.removeAll()
    clock = 14
    check(mouse(.leftMouseDown) && mouse(.leftMouseUp), "clicks swallowed")
    check(pointers().map { num($0["buttons"]) } == [1, 0], "fast click -> buttons 1 then 0")
    sent.removeAll()
    clock = 15
    _ = mouse(.rightMouseDown)
    check(pointers().last.map { num($0["buttons"]) } == 2, "right button = 2")
    _ = mouse(.rightMouseUp)

    // Drag with ⌘ carries buttons + mods.
    sent.removeAll()
    clock = 16
    _ = mouse(.leftMouseDown, flags: .maskCommand)
    clock = 16.1
    _ = mouse(.leftMouseDragged, dx: 7, flags: .maskCommand)
    let drag = pointers().last
    check(num(drag?["buttons"]) == 1 && num(drag?["dx"]) == 7 && (drag?["mods"] as? [String]) == ["cmd"], "⌘-drag -> buttons 1, mods [cmd]")
    _ = mouse(.leftMouseUp)

    // Scroll in lines.
    sent.removeAll()
    clock = 17
    let s = CGEvent(scrollWheelEvent2Source: nil, units: .line, wheelCount: 2, wheel1: 3, wheel2: -1, wheel3: 0)!
    check(c.handle(.scrollWheel, s), "scroll swallowed")
    check(num(pointers().last?["sy"]) == 3 && num(pointers().last?["sx"]) == -1, "scroll -> sy 3, sx -1")

    // Injected (tagged) events pass through.
    check(!mouse(.leftMouseDown, tagged: true) && !mouse(.mouseMoved, dx: 3, tagged: true), "events tagged by Input.swift pass through")

    // MOUSE (17) injection: events built by Input.swift pass the swallowing tap, carry the right type and click count.
    let interval = NSEvent.doubleClickInterval
    let p0 = CGPoint(x: 100, y: 100)
    func inj(_ kind: String, _ pt: CGPoint, _ button: Int = 0, _ t: Double) -> CGEvent { mouseEvent(kind: kind, at: pt, button: button, t: t)! }
    let injected: [(String, Int, CGEventType)] = [("move", 0, .mouseMoved), ("down", 0, .leftMouseDown), ("drag", 0, .leftMouseDragged),
                                                   ("up", 0, .leftMouseUp), ("down", 1, .rightMouseDown), ("drag", 1, .rightMouseDragged),
                                                   ("up", 1, .rightMouseUp)]
    var allPass = true, typesOK = true
    for (i, (k, b, want)) in injected.enumerated() {
        let e = inj(k, p0, b, 100 + Double(i) * 10)
        typesOK = typesOK && e.type == want
        allPass = allPass && !c.handle(e.type, e)
    }
    check(typesOK, "MOUSE kinds map to mouseMoved/left|rightMouseDown/Dragged/Up")
    check(allPass, "injected MOUSE events pass the swallowing tap while control is on")
    let moved = inj("move", CGPoint(x: 130, y: 90), 0, 200)
    check(moved.location == CGPoint(x: 130, y: 90) && moved.getDoubleValueField(.mouseEventDeltaX) == 30, "move carries location + delta from last injected point")
    func state(_ e: CGEvent) -> Int64 { e.getIntegerValueField(.mouseEventClickState) }
    let d1 = inj("down", p0, 0, 300), u1 = inj("up", p0, 0, 300.05)
    let d2 = inj("down", CGPoint(x: 102, y: 101), 0, 300.05 + interval * 0.5), u2 = inj("up", p0, 0, 300.3)
    let d3 = inj("down", p0, 0, 300.3 + interval * 0.5)
    check([state(d1), state(u1), state(d2), state(u2), state(d3)] == [1, 1, 2, 2, 3], "single/double/triple click states within \(interval)s and 4pt")
    check(state(inj("down", p0, 0, 400)) == 1, "down after the interval restarts at 1")
    check(state(inj("down", CGPoint(x: 120, y: 100), 0, 400.1)) == 1, "down >4pt away restarts at 1")
    check(state(inj("down", CGPoint(x: 120, y: 100), 1, 400.2)) == 1, "other button restarts at 1")
    check(state(inj("drag", CGPoint(x: 150, y: 100), 1, 400.3)) == 1, "drag carries the press's click state")
    _ = inj("up", CGPoint(x: 150, y: 100), 1, 400.4)

    // Spike guard: replay the shape of the user's real trace (small moves with isolated warp spikes).
    let sc = ControlMode()
    sc.autoControl = false
    var spSent: [[String: Any]] = []
    sc.clientConnected { t, p in if t == Msg.pointer { spSent.append((try? JSONSerialization.jsonObject(with: p)) as? [String: Any] ?? [:]) } }
    for d in [true, false] { let e = CGEvent(keyboardEventSource: nil, virtualKey: 46, keyDown: d)!; e.flags = [.maskControl, .maskAlternate, .maskCommand]; _ = sc.handle(d ? .keyDown : .keyUp, e) }
    var sclock = 100.0
    sc.now = { sclock }
    func smove(_ dx: Double, _ dy: Double) -> Bool {
        sclock += 0.01 // 10 ms apart: every event flushes on its own
        let e = CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: .zero, mouseButton: .left)!
        e.setDoubleValueField(.mouseEventDeltaX, value: dx)
        e.setDoubleValueField(.mouseEventDeltaY, value: dy)
        return sc.handle(.mouseMoved, e)
    }
    let real: [(Double, Double)] = [(5, -2), (6, 1), (31, 12), (-8, 4), (91, -20), (3, 3), (-12, 6), (40, 25), (7, -1), (2, 2)]
    for (dx, dy) in real { _ = smove(dx, dy) }
    let spikes: [(Double, Double)] = [(-1015, 730), (932, -314), (-1063, -43), (751, 952), (254, 161)]
    var swallowedAll = true
    for (dx, dy) in spikes {
        swallowedAll = swallowedAll && smove(dx, dy)
        _ = smove(65, -43) // the real move right after a spike (from the user's trace) must survive
    }
    let fwd = spSent.reduce((0.0, 0.0)) { ($0.0 + ((($1["dx"] as? NSNumber)?.doubleValue) ?? 0), $0.1 + ((($1["dy"] as? NSNumber)?.doubleValue) ?? 0)) }
    let wantX = real.reduce(0) { $0 + $1.0 } + 5 * 65, wantY = real.reduce(0) { $0 + $1.1 } - 5 * 43
    check(swallowedAll && sc.droppedSpikes == 5, "spike guard drops the 5 warp spikes (\(sc.droppedSpikes)), still swallows them")
    check(fwd.0 == wantX && fwd.1 == wantY, "spike guard forwards all real motion incl. 91 pt and post-spike moves (sum \(fwd) vs \(wantX),\(wantY))")
    // After an injected jump (>150 pt), a mid-size spike (191,-125 in the user's trace) is also dropped; small moves pass.
    sc.noteInjectedJump(600)
    check(!smove(191, -125) || sc.droppedSpikes == 6, "post-jump limit drops a 228 pt delta")
    sc.noteInjectedJump(600)
    let before = sc.droppedSpikes
    _ = smove(20, 10)
    check(sc.droppedSpikes == before, "post-jump limit keeps a normal 22 pt move")
    sc.noteInjectedJump(40) // hover-sized injected move: no extra strictness
    _ = smove(150, 0)
    check(sc.droppedSpikes == before, "small injected moves (hover) don't arm the post-jump limit (150 pt flick kept)")

    // Auto control mode: ON when a headset connects, OFF on disconnect; manual OFF sticks until the next connect.
    var autoApplied: [Bool] = [], autoSent: [String] = []
    let ac = ControlMode()
    ac.apply = { autoApplied.append($0) }
    check(!ac.isOn, "auto: off with no headset")
    ac.clientConnected { t, p in if t == Msg.mode { autoSent.append(String(decoding: p, as: UTF8.self)) } }
    check(ac.isOn && autoApplied == [true] && autoSent == [#"{"control":true}"#], "auto: headset connect turns control ON (one MODE true)")
    func acKey() { for d in [true, false] { let e = CGEvent(keyboardEventSource: nil, virtualKey: 46, keyDown: d)!; e.flags = [.maskControl, .maskAlternate, .maskCommand]; _ = ac.handle(d ? .keyDown : .keyUp, e) } }
    acKey()
    check(!ac.isOn && autoSent.last == #"{"control":false}"#, "auto: ⌃⌥⌘M still turns it OFF manually")
    acKey()
    let manualOn = ac.isOn
    acKey()
    check(manualOn && !ac.isOn, "auto: manual toggles still work after auto")
    ac.clientDisconnected()
    ac.clientConnected { t, p in if t == Msg.mode { autoSent.append(String(decoding: p, as: UTF8.self)) } }
    check(ac.isOn, "auto: the next connect turns it ON again")
    ac.clientDisconnected()
    check(!ac.isOn && autoApplied.last == false, "auto: disconnect turns it OFF")
    let nc2 = ControlMode()
    nc2.autoControl = false
    var nApplied: [Bool] = []
    nc2.apply = { nApplied.append($0) }
    nc2.clientConnected { _, _ in }
    check(!nc2.isOn && nApplied.isEmpty, "--no-auto-control: connect leaves control OFF")

    // COMMAND hotkeys (v4): keycode table, swallowing, repeat rules, pass-through without a client.
    let all = [.maskControl, .maskAlternate, .maskCommand] as CGEventFlags
    let table: [(CGKeyCode, Bool, String, Bool)] = [
        (123, false, #"{"cmd":"nudge","dtheta":-1}"#, true), (124, false, #"{"cmd":"nudge","dtheta":1}"#, true),
        (126, false, #"{"cmd":"nudge","dy":1}"#, true), (125, false, #"{"cmd":"nudge","dy":-1}"#, true),
        (24, false, #"{"cmd":"depth","d":-1}"#, true), (27, false, #"{"cmd":"depth","d":1}"#, true),
        (30, false, #"{"cmd":"size","d":1}"#, true), (33, false, #"{"cmd":"size","d":-1}"#, true),
        (8, false, #"{"cmd":"center"}"#, false), (18, false, #"{"cmd":"preset","name":"editor"}"#, false),
        (19, false, #"{"cmd":"preset","name":"side"}"#, false), (20, false, #"{"cmd":"preset","name":"glance"}"#, false),
        (6, false, #"{"cmd":"undo"}"#, false), (6, true, #"{"cmd":"redo"}"#, false),
        (15, false, #"{"cmd":"recenter"}"#, false), (35, false, #"{"cmd":"passthrough"}"#, false),
        (17, false, #"{"cmd":"tidy"}"#, false),
        (47, false, #"{"cmd":"dim","d":1}"#, false), (43, false, #"{"cmd":"dim","d":-1}"#, false),
        (123, true, #"{"cmd":"cutout","dh":0,"dw":-1}"#, true), (124, true, #"{"cmd":"cutout","dh":0,"dw":1}"#, true),
        (126, true, #"{"cmd":"cutout","dh":1,"dw":0}"#, true), (125, true, #"{"cmd":"cutout","dh":-1,"dw":0}"#, true),
    ]
    var raw: [(UInt8, Data)] = []
    let rc = ControlMode()
    rc.autoControl = false
    rc.clientConnected { t, p in raw.append((t, p)) }
    func ckey(_ code: CGKeyCode, _ down: Bool, _ flags: CGEventFlags, repeat_: Bool = false, _ m: ControlMode) -> Bool {
        let e = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down)!
        e.flags = flags
        if repeat_ { e.setIntegerValueField(.keyboardEventAutorepeat, value: 1) }
        return m.handle(down ? .keyDown : .keyUp, e)
    }
    var tableOK = true, swallowOK = true, repeatOK = true
    for (code, shift, json, repeatable) in table {
        raw.removeAll()
        let f = shift ? all.union(.maskShift) : all
        swallowOK = swallowOK && ckey(code, true, f, rc) && ckey(code, true, f, repeat_: true, rc) && ckey(code, false, [], rc)
        let cmds = raw.filter { $0.0 == Msg.command }.map { String(decoding: $0.1, as: UTF8.self) }
        tableOK = tableOK && cmds.first == json
        repeatOK = repeatOK && cmds.count == (repeatable ? 2 : 1)
        if cmds.first != json { print("  mismatch keycode \(code) shift \(shift): \(cmds.first ?? "none") != \(json)") }
    }
    check(tableOK, "all \(table.count) ⌃⌥⌘ hotkeys map to the v4 COMMAND JSON")
    check(swallowOK, "COMMAND hotkeys swallow keyDown, repeats and the matching keyUp (even after modifiers released)")
    check(repeatOK, "key repeat re-sends nudge/depth/size/cutout only")
    check(!ckey(8, true, [.maskControl, .maskCommand], rc) && !ckey(0, true, all, rc), "missing ⌥, or an unmapped key, passes through")
    check(!ckey(8, false, [], rc), "keyUp of a key whose keyDown passed is not swallowed")
    let nc = ControlMode() // no client
    check(!ckey(123, true, all, nc) && !ckey(123, false, [], nc), "without a headset, COMMAND hotkeys pass through")
    check(!rc.isOn && raw.allSatisfy { $0.0 != Msg.mode || $0.1 == Data(#"{"control":false}"#.utf8) }, "COMMAND hotkeys don't toggle control mode")

    // keyHook (v5 push-to-talk): sees non-hotkey keys with the mode, its verdict is used; hotkeys and tagged keys skip it.
    var hooked: [(Int64, Bool, Bool)] = []
    rc.keyHook = { type, e, on in
        hooked.append((e.getIntegerValueField(.keyboardEventKeycode), type == .keyDown, on))
        return e.getIntegerValueField(.keyboardEventKeycode) == 49 // swallow space
    }
    check(ckey(49, true, [], rc) && ckey(49, false, [], rc) && !ckey(0, true, [], rc), "keyHook verdict swallows space, passes 'a'")
    check(hooked.map { $0.0 } == [49, 49, 0] && hooked[0].1 && !hooked[1].1 && hooked[0].2 == rc.isOn, "keyHook sees keyDown/keyUp with control mode")
    hooked.removeAll()
    _ = ckey(15, true, all, rc); _ = ckey(15, false, [], rc) // ⌃⌥⌘R COMMAND
    let bs = CGEvent(keyboardEventSource: nil, virtualKey: 51, keyDown: true)!
    tagInjected(bs)
    check(!rc.handle(.keyDown, bs) && hooked.isEmpty, "hotkeys and tagged (injected) keys never reach keyHook")
    // Contract with voice: a keyUp still reaches the hook after control mode turned off between down and up.
    hooked.removeAll()
    rc.clientConnected { t, p in raw.append((t, p)) }
    _ = ckey(46, true, all, rc); _ = ckey(46, false, [], rc) // ⌃⌥⌘M on
    let wasOn = rc.isOn
    _ = ckey(49, true, [], rc)
    _ = ckey(46, true, all, rc); _ = ckey(46, false, [], rc) // ⌃⌥⌘M off
    _ = ckey(49, false, [], rc)
    check(wasOn && !rc.isOn && hooked.map { "\($0.0)\($0.1 ? "d" : "u")\($0.2 ? "+" : "-")" } == ["49d+", "49u-"],
          "space keyUp reaches keyHook after control mode turned off (on=false)")
    rc.keyHook = nil

    // CURSOR encoding: payload header + PNG round trip for the I-beam.
    if let ci = CursorImage(.iBeam), let back = NSBitmapImageRep(data: ci.payload.dropFirst(8)) {
        let u16 = { (i: Int) in Int(ci.payload[i]) << 8 | Int(ci.payload[i + 1]) }
        print("  iBeam CURSOR: \(ci.summary), payload \(ci.payload.count) B")
        check(u16(4) == Int(NSCursor.iBeam.image.size.width.rounded()) && u16(6) == Int(NSCursor.iBeam.image.size.height.rounded()),
              "CURSOR header carries logical size")
        check(back.pixelsWide == Int(ci.pixels.width) && Double(u16(0)) == ci.hotspot.x && Double(u16(2)) == ci.hotspot.y,
              "CURSOR PNG decodes at the encoded size; hotspot in image px")
        check(ci.pixels.width == 2 * ci.points.width || NSCursor.iBeam.image.representations.allSatisfy { $0.pixelsWide != Int(2 * ci.points.width) },
              "2x rep chosen when available")
    } else {
        check(false, "CURSOR encoding of NSCursor.iBeam")
    }

    // Tap disabled notification passes through.
    check(!c.handle(.tapDisabledByTimeout, CGEvent(source: nil)!), "tap-disabled event handled (re-enable path)")

    // Serialization shape.
    var a = PointerAccum()
    a.dx = 3.5; a.dy = -1; a.buttons = 1; a.sy = -2; a.mods = ["cmd"]
    print("  sample POINTER: \(String(decoding: a.take(), as: UTF8.self))")

    // Client disconnect forces control off.
    sent.removeAll()
    c.clientDisconnected()
    check(!c.isOn && applied == [true, false], "client disconnect turns control off and restores")
    check(!mouse(.mouseMoved, dx: 1), "mouse passes through after disconnect")

    print(ok ? "control self-test: ALL PASS" : "control self-test: FAILURES")
    return ok
}
