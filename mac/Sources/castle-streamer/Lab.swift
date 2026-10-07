import AppKit

/// `--lab`: for testing the headset app (e.g. the XR emulator) next to the live streamer. No event taps, gamma,
/// cursor detach or virtual display, and input from the client is only logged. Control-mode traffic (MODE,
/// POINTER, CURSOR) comes from the inject socket instead of the Mac's mouse.
enum Lab {
    static let args = CommandLine.arguments
    static let enabled = args.contains("--lab")
    /// `--only-app NAME` (repeatable): list/stream only these apps (case-insensitive application name).
    static let onlyApps: Set<String> = Set(args.indices.dropLast().filter { args[$0] == "--only-app" }.map { args[$0 + 1].lowercased() })

    /// Set by Castle: fails the window's stream as an SCK error would (exercises the retry path). Returns an error.
    static var failStream: ((UInt32) -> String?)?

    /// Lab apps may sit below normal windows (castle-testwin uses a desktop-level window): include desktop-level
    /// windows and don't require layer 0 (--only-app still filters out the wallpaper and icons).
    static var anyLayer: Bool { enabled && !onlyApps.isEmpty }

    static func allows(appName: String?) -> Bool {
        onlyApps.isEmpty || onlyApps.contains((appName ?? "").lowercased())
    }

    /// Logs a client input message instead of performing it.
    static func wouldDo(_ type: UInt8, _ wid: UInt32, _ obj: [String: Any]?) {
        let name = [Msg.focus: "FOCUS", Msg.click: "CLICK", Msg.scroll: "SCROLL", Msg.mouse: "MOUSE"][type] ?? "\(type)"
        let num = { (k: String) in (obj?[k] as? NSNumber).map { String(format: "%.3g", $0.doubleValue) } ?? "-" }
        var s = "lab: would \(name)"
        if type == Msg.mouse { s += " \(obj?["kind"] as? String ?? "?")" + ((obj?["button"] as? NSNumber)?.intValue == 1 ? " right" : "") }
        s += " window \(wid)"
        if type != Msg.focus { s += " at (\(num("x")),\(num("y")))" }
        if type == Msg.scroll { s += " d=(\(num("dx")),\(num("dy")))" }
        log(s)
    }

    /// Inject socket: newline-delimited JSON commands, one `{"ok":true}` / `{"ok":false,"error":...}` reply per line.
    ///   {"type":"mode","control":true}
    ///   {"type":"pointer","dx":3,"dy":-1,"buttons":0,"sx":0,"sy":0,"mods":[]}
    ///   {"type":"cursor","name":"arrow|ibeam|hand"}
    ///   {"type":"fail-stream","window":1026}      simulate an SCK stream error (retry path)
    ///   {"type":"voice","text":"show slack"}      v5: run the voice planner on a transcript; reply carries "result"
    ///   {"type":"voice-pcm","path":"/x.pcm"}      v5: stream 24 kHz mono PCM16 through realtime transcription, then plan
    static func startInjectServer(port: UInt16) throws {
        let lfd = socket(AF_INET, SOCK_STREAM, 0)
        guard lfd >= 0 else { throw POSIXError(.init(rawValue: errno)!) }
        var one: Int32 = 1
        setsockopt(lfd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(lfd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard rc == 0, listen(lfd, 4) == 0 else { throw POSIXError(.init(rawValue: errno)!) }
        log("lab: inject socket on 127.0.0.1:\(port)")
        Thread.detachNewThread {
            while true {
                let cfd = accept(lfd, nil, nil)
                if cfd < 0 { continue }
                setsockopt(cfd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
                Thread.detachNewThread { serve(cfd) }
            }
        }
    }

    private static func serve(_ fd: Int32) {
        defer { close(fd) }
        var buf = Data()
        var chunk = [UInt8](repeating: 0, count: 4096)
        while true {
            let n = recv(fd, &chunk, chunk.count, 0)
            if n <= 0 { return }
            buf.append(contentsOf: chunk[0..<n])
            while let nl = buf.firstIndex(of: 0x0A) {
                let line = buf[buf.startIndex..<nl]
                buf.removeSubrange(buf.startIndex...nl)
                guard !line.allSatisfy({ $0 == 0x20 || $0 == 0x0D }) else { continue }
                let err: String?
                var result: [String: Any]?
                if let obj = (try? JSONSerialization.jsonObject(with: line)) as? [String: Any] {
                    if obj["type"] as? String == "voice" || obj["type"] as? String == "voice-pcm" {
                        let r = obj["path"] is String ? voice.labPCM(obj["path"] as! String) : voice.labRun(obj["text"] as? String ?? "")
                        result = r
                        err = r["ok"] as? Bool == true ? nil : r["summary"] as? String ?? "voice failed"
                    } else if obj["type"] as? String == "fail-stream" {
                        let id = (obj["window"] as? NSNumber)?.uint32Value ?? 0
                        err = failStream?(id) ?? nil
                    } else {
                        err = control.labInject(obj)
                    }
                } else {
                    err = "invalid JSON"
                }
                var reply: [String: Any] = err.map { ["ok": false, "error": $0] } ?? ["ok": true]
                reply["result"] = result
                var out = (try? JSONSerialization.data(withJSONObject: reply, options: [.sortedKeys])) ?? Data()
                out.append(0x0A)
                _ = out.withUnsafeBytes { send(fd, $0.baseAddress, $0.count, 0) }
            }
        }
    }
}
