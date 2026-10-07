import AppKit
import ScreenCaptureKit

/// Owns the window catalog, the current client and per-window streams. All state on `stateQ`.
final class Castle {
    private let stateQ = DispatchQueue(label: "castle.state")
    private let inputQ = DispatchQueue(label: "castle.input")
    private let server = Server()
    private var client: Client?
    private var windows: [UInt32: SCWindow] = [:]
    private var listJSON = Data()
    private var streams: [UInt32: WindowStream] = [:]
    private var subscribed = Set<UInt32>()
    /// Restart attempts after a stream died on its own; reset once a failure is >30 s after the previous one.
    private var retries: [UInt32: (count: Int, last: Date)] = [:]
    private static let retryDelays: [Double] = [1, 2, 4]

    func start() throws {
        control.onModeChange = { on in
            self.stateQ.async { WindowStream.setShowsCursor(!on, streams: Array(self.streams.values)) }
        }
        server.onConnect = { c in self.stateQ.async { self.connected(c) } }
        server.onDisconnect = { c in self.stateQ.async { self.disconnected(c) } }
        server.onMessage = { c, t, w, p in self.stateQ.async { self.handle(c, t, w, p) } }
        if Lab.enabled {
            Lab.failStream = { id in
                self.stateQ.sync {
                    guard let s = self.streams[id] else { return "no active stream for window \(id)" }
                    s.simulateFailure()
                    return nil
                }
            }
        }
        try server.start(port: port)
        log("listening on 127.0.0.1:\(port)")
        refreshWindows()
        Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { _ in self.refreshWindows() }
    }

    // v5 voice (Voice.swift): the window catalog for the planner, and a way to reach the headset.
    func voiceWindows() -> [VoiceWindow] {
        stateQ.sync {
            windows.values.sorted { $0.windowID < $1.windowID }.map {
                VoiceWindow(id: $0.windowID, app: $0.owningApplication?.applicationName ?? "", title: $0.title ?? "",
                            shown: subscribed.contains($0.windowID))
            }
        }
    }

    /// Sends a window-less message to the current headset; false if none is connected. Not from stateQ.
    func sendToHeadset(_ type: UInt8, _ payload: Data) -> Bool {
        stateQ.sync {
            guard let client else { return false }
            client.send(type, 0, payload)
            return true
        }
    }

    private func refreshWindows() {
        SCShareableContent.getExcludingDesktopWindows(!Lab.anyLayer, onScreenWindowsOnly: true) { content, err in
            guard let content else {
                log("SCShareableContent failed: \(err?.localizedDescription ?? "?")")
                return
            }
            let me = getpid()
            let list = content.windows.filter {
                ($0.windowLayer == 0 || Lab.anyLayer) && $0.isOnScreen && $0.frame.width >= 120 && $0.frame.height >= 80
                    && !($0.title ?? "").isEmpty && $0.owningApplication != nil && $0.owningApplication!.processID != me
                    && Lab.allows(appName: $0.owningApplication?.applicationName)
            }
            self.stateQ.async { self.updateWindows(list) }
        }
    }

    private func updateWindows(_ list: [SCWindow]) {
        windows = Dictionary(list.map { ($0.windowID, $0) }, uniquingKeysWith: { a, _ in a })
        let json = list.sorted { $0.windowID < $1.windowID }.map { w -> [String: Any] in
            let (pw, ph) = pixelSize(w.frame)
            return ["id": w.windowID, "app": w.owningApplication?.applicationName ?? "", "title": w.title ?? "", "w": pw, "h": ph]
        }
        let data = (try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])) ?? Data("[]".utf8)
        // Sent every poll even if unchanged: doubles as the heartbeat the headset uses to detect a dead tunnel.
        if Lab.enabled, data != listJSON {
            log("lab: window list " + list.sorted { $0.windowID < $1.windowID }.map { "\($0.windowID):\($0.title ?? "")" }.joined(separator: ", "))
        }
        listJSON = data
        client?.send(Msg.windowList, 0, data)
        for (id, s) in streams {
            if let w = windows[id] { s.resize(w) } else { stopStream(id, gone: true) }
        }
    }

    private func connected(_ c: Client) {
        if let old = client {
            log("new client replaces previous connection")
            old.close()
        }
        stopAll()
        client = c
        c.onNeedKeyframe = { [weak self] id in self?.stateQ.async { self?.streams[id]?.requestKeyframe() } }
        log("client connected")
        c.send(Msg.hello, 0, HostInfo.hello) // first message: who this Mac is (v6)
        c.send(Msg.windowList, 0, listJSON.isEmpty ? Data("[]".utf8) : listJSON)
        control.clientConnected { [weak c] t, p in c?.send(t, 0, p) }
    }

    private func disconnected(_ c: Client) {
        guard c === client else { return }
        log("client disconnected")
        client = nil
        stopAll()
        control.clientDisconnected()
        inputQ.async { releaseInjectedButtons() }
        virtualDisplay?.arrange([])
    }

    private func stopAll() {
        for id in streams.keys { stopStream(id, gone: false) }
        subscribed = []
        retries = [:]
    }

    private func startStream(_ w: SCWindow, _ c: Client) {
        let id = w.windowID
        streams[id] = WindowStream(
            window: w,
            send: { [weak c] t, p in c?.send(t, id, p) },
            onStop: { [weak self, weak c] in
                // Stream died on its own (window closed, capture error): retry, or drop it and tell the headset.
                self?.stateQ.async {
                    guard let self, let c, let s = self.streams[id], s.isStopped else { return }
                    self.streams[id] = nil
                    self.retryOrGone(id, c)
                }
            })
        streams[id]?.takeDrops = { [weak c] in c?.takeDrops(id) ?? 0 }
    }

    /// SCK errors can be transient ("application connection being interrupted"): restart after 1, 2, 4 s while the
    /// window is still listed and subscribed. The new stream starts a fresh encoder, so CODEC_CONFIG + IDR come first.
    private func retryOrGone(_ id: UInt32, _ c: Client) {
        var r = retries[id] ?? (0, .distantPast)
        if Date().timeIntervalSince(r.last) > 30 { r.count = 0 }
        guard windows[id] != nil, r.count < Self.retryDelays.count else {
            retries[id] = nil
            subscribed.remove(id)
            c.send(Msg.windowGone, id)
            log("window \(id): \(windows[id] == nil ? "gone" : "giving up after \(r.count) retries"), sent WINDOW_GONE")
            return
        }
        let delay = Self.retryDelays[r.count]
        r.count += 1
        r.last = Date()
        retries[id] = r
        log("window \(id): stream failed, retry \(r.count)/\(Self.retryDelays.count) in \(Int(delay)) s")
        stateQ.asyncAfter(deadline: .now() + delay) { [weak self, weak c] in
            guard let self, let c, c === client, subscribed.contains(id), streams[id] == nil else { return }
            guard let w = windows[id] else { return retryOrGone(id, c) }
            log("window \(id): restarting capture (retry \(r.count))")
            startStream(w, c)
        }
    }

    private func stopStream(_ id: UInt32, gone: Bool) {
        guard let s = streams.removeValue(forKey: id) else { return }
        s.stop()
        if gone { client?.send(Msg.windowGone, id) }
        log("window \(id): stream stopped\(gone ? " (window gone)" : "")")
    }

    private func handle(_ c: Client, _ type: UInt8, _ wid: UInt32, _ payload: Data) {
        guard c === client else { return }
        let obj = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any]
        if Lab.enabled, [Msg.focus, Msg.click, Msg.scroll, Msg.mouse].contains(type) { return Lab.wouldDo(type, wid, obj) }
        switch type {
        case Msg.subscribe:
            let ids = Set(((obj?["ids"] as? [Any]) ?? []).compactMap { ($0 as? NSNumber)?.uint32Value })
            log("subscribe \(ids.sorted())")
            for id in streams.keys where !ids.contains(id) { stopStream(id, gone: false) }
            subscribed = ids
            retries = retries.filter { ids.contains($0.key) }
            for id in ids where streams[id] == nil {
                guard let w = windows[id] else {
                    c.send(Msg.windowGone, id)
                    continue
                }
                startStream(w, c)
            }
            virtualDisplay?.arrange(ids.sorted().compactMap { id in
                windows[id].flatMap { w in w.owningApplication.map { .init(id: id, pid: $0.processID, title: w.title ?? "") } }
            })
        case Msg.requestKeyframe:
            streams[wid]?.requestKeyframe()
        case Msg.focus, Msg.click, Msg.scroll:
            guard let w = windows[wid], let pid = w.owningApplication?.processID else { return }
            let title = w.title ?? ""
            let x = (obj?["x"] as? NSNumber)?.doubleValue ?? 0.5, y = (obj?["y"] as? NSNumber)?.doubleValue ?? 0.5
            let dx = (obj?["dx"] as? NSNumber)?.int32Value ?? 0, dy = (obj?["dy"] as? NSNumber)?.int32Value ?? 0
            inputQ.async {
                focusWindow(id: wid, pid: pid, title: title)
                guard type != Msg.focus else { return }
                usleep(50_000) // let the raise land before injecting
                if type == Msg.click { click(id: wid, x: x, y: y) } else { scroll(id: wid, x: x, y: y, dx: dx, dy: dy) }
            }
        case Msg.mouse:
            guard let w = windows[wid], let pid = w.owningApplication?.processID else { return }
            let title = w.title ?? "", t = ProcessInfo.processInfo.systemUptime
            let kind = obj?["kind"] as? String ?? "", button = (obj?["button"] as? NSNumber)?.intValue ?? 0
            let x = (obj?["x"] as? NSNumber)?.doubleValue ?? 0.5, y = (obj?["y"] as? NSNumber)?.doubleValue ?? 0.5
            inputQ.async { mouse(id: wid, pid: pid, title: title, kind: kind, x: x, y: y, button: button, t: t) }
        default:
            log("unknown message type \(type)")
        }
    }
}

// MARK: startup

_ = NSApplication.shared // initializes the window-server connection ScreenCaptureKit expects
// setup.sh: report (and optionally request) permissions, then exit before any prompt, tap or server below.
if CommandLine.arguments.contains("--check-permissions") || CommandLine.arguments.contains("--request-permissions") {
    exit(HostInfo.permissionsReport(request: CommandLine.arguments.contains("--request-permissions")))
}

let screenOK = CGPreflightScreenCaptureAccess()
let axOK = AXIsProcessTrusted()
log("Screen Recording permission: \(screenOK ? "granted" : "MISSING")")
log("Accessibility permission: \(axOK ? "granted" : "MISSING")")
if !screenOK {
    CGRequestScreenCaptureAccess()
    log("-> Grant Screen Recording in System Settings > Privacy & Security > Screen & System Audio Recording")
    log("   for the app that launched this binary (your terminal app when run from a shell), then restart it.")
}
if !axOK && !Lab.enabled {
    AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue(): true] as CFDictionary)
    log("-> Grant Accessibility in System Settings > Privacy & Security > Accessibility")
    log("   for the app that launched this binary (needed for FOCUS/CLICK/SCROLL), then restart it.")
}

let args = CommandLine.arguments
let port = args.firstIndex(of: "--port").flatMap { $0 + 1 < args.count ? UInt16(args[$0 + 1]) : nil } ?? 7420
if args.contains("--dry-run-control") { exit(controlSelfTest() ? 0 : 1) }
if args.contains("--dry-run-voice") { exit(voiceSelfTest() ? 0 : 1) }
Trace.shared.start(port: port)
if Lab.enabled {
    let injectPort = args.firstIndex(of: "--inject-port").flatMap { $0 + 1 < args.count ? UInt16(args[$0 + 1]) : nil } ?? port + 1
    do { try Lab.startInjectServer(port: injectPort) } catch {
        log("lab: failed to listen on 127.0.0.1:\(injectPort): \(error)")
        exit(1)
    }
    if !Lab.onlyApps.isEmpty { log("lab: only apps \(Lab.onlyApps.sorted())") }
} else {
    installKeyTap()
}

control.start(lab: Lab.enabled)
let virtualDisplay = args.contains("--virtual-display") && !Lab.enabled ? VirtualDisplay() : nil

let castle = Castle()
do {
    try castle.start()
    voice.start(windows: castle.voiceWindows, send: castle.sendToHeadset) // v5 push-to-talk
} catch {
    log("failed to listen on 127.0.0.1:\(port): \(error)")
    exit(1)
}
RunLoop.main.run()
