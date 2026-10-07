import AppKit
import AVFoundation

// v5 voice commands (PROTOCOL.md): hold space in control mode -> mic -> OpenAI realtime transcription -> planner
// (Responses API, strict function tools) -> layout COMMANDs to the headset / Mac actions. Status goes out as VOICE (21).

extension Msg {
    static let voice: UInt8 = 21
}

struct VoiceError: Error {
    let message: String
    init(_ m: String) { message = m }
}

/// A window from the streamer's catalog; `shown` = the headset subscribes to it.
struct VoiceWindow {
    let id: UInt32
    let app: String
    let title: String
    let shown: Bool
}

// MARK: push-to-talk key state machine (tap thread; pure apart from the closures, exercised by the self-test)

/// Space held ≥350 ms in control mode = talk. The space keyDown always passes (normal typing); once the hold is
/// confirmed one tagged Backspace deletes that space. Autorepeats are swallowed while pending/recording, keyUps pass.
final class PushToTalk {
    static let holdDelay = 0.35
    static let maxUtterance = 30.0
    static let space: Int64 = 49 // kVK_Space
    static let modifiers: CGEventFlags = [.maskShift, .maskControl, .maskAlternate, .maskCommand]

    enum State: Equatable { case idle, pending, typing, recording, capped }
    private(set) var state = State.idle
    private var gen = 0 // invalidates timers of an earlier press
    private var lastOn = false

    var after: (Double, @escaping () -> Void) -> Void = { _, _ in }
    var postBackspace: () -> Void = {}
    var onPending: () -> Void = {} // space went down: warm the transcription socket (no mic)
    var onStart: () -> Void = {}
    var onEnd: () -> Void = {}

    /// ControlMode.keyHook signature. Returns true to swallow.
    func hook(_ type: CGEventType, _ ev: CGEvent, _ on: Bool) -> Bool {
        key(down: type == .keyDown, code: ev.getIntegerValueField(.keyboardEventKeycode), flags: ev.flags,
            isRepeat: ev.getIntegerValueField(.keyboardEventAutorepeat) != 0, on: on)
    }

    func key(down: Bool, code: Int64, flags: CGEventFlags, isRepeat: Bool, on: Bool) -> Bool {
        lastOn = on
        guard code == Self.space else {
            if down && state == .pending { gen += 1; state = .typing } // another key during the hold: it's typing
            return false
        }
        if !down {
            if state == .recording { onEnd() }
            gen += 1
            state = .idle
            return false
        }
        if isRepeat { return [.pending, .recording, .capped].contains(state) }
        if state == .recording { onEnd() } // missed keyUp
        gen += 1
        state = .idle
        guard on, flags.intersection(Self.modifiers).isEmpty else { return false }
        state = .pending
        let g = gen
        onPending()
        after(Self.holdDelay) { [self] in
            guard gen == g, state == .pending, lastOn else { return }
            postBackspace()
            state = .recording
            onStart()
            after(Self.maxUtterance) { [self] in
                guard gen == g, state == .recording else { return }
                log("voice: 30 s cap reached, ending the utterance")
                state = .capped
                onEnd()
            }
        }
        return false
    }
}

private func postTaggedBackspace() {
    for down in [true, false] {
        guard let e = CGEvent(keyboardEventSource: nil, virtualKey: 51, keyDown: down) else { continue }
        e.flags = []
        tagInjected(e)
        e.post(tap: .cghidEventTap)
    }
}

// MARK: OpenAI key

enum OpenAIKey {
    static let missing = "no OpenAI key — run: security add-generic-password -U -s mind-castle-openai -a openai -w"
    private static var cached: String?

    /// Keychain (generic password, service mind-castle-openai, account openai), else env OPENAI_API_KEY. Never logged.
    static func get() -> String? {
        if let cached { return cached }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/security")
        p.arguments = ["find-generic-password", "-s", "mind-castle-openai", "-a", "openai", "-w"]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        var key: String?
        if (try? p.run()) != nil {
            let data = out.fileHandleForReading.readDataToEndOfFile()
            p.waitUntilExit()
            if p.terminationStatus == 0 { key = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines) }
        }
        if key?.isEmpty ?? true { key = ProcessInfo.processInfo.environment["OPENAI_API_KEY"] }
        guard let key, !key.isEmpty else { return nil }
        cached = key
        return key
    }
}

// MARK: HTTP (blocking, for the plan queue)

private func httpJSON(_ req: URLRequest) throws -> [String: Any] {
    var result: Result<(Data, HTTPURLResponse), Error> = .failure(VoiceError("no response"))
    let done = DispatchSemaphore(value: 0)
    URLSession.shared.dataTask(with: req) { data, resp, err in
        if let data, let http = resp as? HTTPURLResponse { result = .success((data, http)) } else if let err { result = .failure(err) }
        done.signal()
    }.resume()
    done.wait()
    let (data, http) = try result.get()
    let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    guard http.statusCode == 200 else {
        let msg = (obj["error"] as? [String: Any])?["message"] as? String ?? String(decoding: data.prefix(200), as: UTF8.self)
        throw VoiceError("OpenAI HTTP \(http.statusCode): \(msg)")
    }
    return obj
}

private func openAIRequest(_ path: String, key: String, timeout: Double) -> URLRequest {
    var req = URLRequest(url: URL(string: "https://api.openai.com/v1/" + path)!)
    req.httpMethod = "POST"
    req.timeoutInterval = timeout
    req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
    return req
}

// MARK: microphone

/// AVAudioEngine input tap resampled to 24 kHz mono PCM16 (little-endian, what the realtime API takes).
final class Mic {
    static let rate = 24000.0
    private let engine = AVAudioEngine()
    private var running = false
    private let outFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Mic.rate, channels: 1, interleaved: true)!

    /// Mic permission belongs to the app that launched us (the terminal, e.g. Ghostty).
    static func permissionError() -> String? {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: return nil
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { ok in log("voice: microphone access \(ok ? "granted" : "denied")") }
            return "allow microphone access in the macOS prompt, then hold space again"
        default:
            return "microphone denied — System Settings > Privacy & Security > Microphone: enable your terminal app, then restart it"
        }
    }

    func start(_ onPCM: @escaping (Data) -> Void) throws {
        let input = engine.inputNode
        let inFormat = input.outputFormat(forBus: 0)
        guard inFormat.sampleRate > 0, inFormat.channelCount > 0,
              let conv = AVAudioConverter(from: inFormat, to: outFormat) else { throw VoiceError("no microphone input device") }
        let out = outFormat
        input.installTap(onBus: 0, bufferSize: 2048, format: inFormat) { buf, _ in
            let cap = AVAudioFrameCount(Double(buf.frameLength) * Mic.rate / inFormat.sampleRate) + 64
            guard let o = AVAudioPCMBuffer(pcmFormat: out, frameCapacity: cap) else { return }
            var fed = false
            var err: NSError?
            conv.convert(to: o, error: &err) { _, status in
                if fed { status.pointee = .noDataNow; return nil }
                fed = true
                status.pointee = .haveData
                return buf
            }
            if o.frameLength > 0, let p = o.int16ChannelData { onPCM(Data(bytes: p[0], count: Int(o.frameLength) * 2)) }
        }
        engine.prepare()
        do { try engine.start() } catch {
            input.removeTap(onBus: 0)
            throw VoiceError("microphone failed to start: \(error.localizedDescription)")
        }
        running = true
    }

    func stop() {
        guard running else { return }
        running = false
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
    }

    static func wav(_ pcm: Data) -> Data {
        var d = Data("RIFF".utf8)
        func le<T: FixedWidthInteger>(_ v: T) { var x = v.littleEndian; withUnsafeBytes(of: &x) { d.append(contentsOf: $0) } }
        le(UInt32(36 + pcm.count)); d.append(contentsOf: Data("WAVEfmt ".utf8))
        le(UInt32(16)); le(UInt16(1)); le(UInt16(1)); le(UInt32(rate)); le(UInt32(rate * 2)); le(UInt16(2)); le(UInt16(16))
        d.append(contentsOf: Data("data".utf8)); le(UInt32(pcm.count))
        d.append(pcm)
        return d
    }
}

// MARK: realtime transcription (WebSocket, kept warm, reconnected lazily)

final class Transcriber {
    static let model = "gpt-live-transcribe"
    private let q: DispatchQueue
    private var task: URLSessionWebSocketTask?
    private var text = ""
    private var onDelta: ((String) -> Void)?
    private var onDone: ((Result<String, VoiceError>) -> Void)?

    init(queue: DispatchQueue) { q = queue }

    /// Connects if not connected. On `q`.
    func warm(key: String) {
        guard task == nil else { return }
        var req = URLRequest(url: URL(string: "wss://api.openai.com/v1/realtime?intent=transcription")!)
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        req.timeoutInterval = 10
        let t = URLSession.shared.webSocketTask(with: req)
        task = t
        t.resume()
        send(["type": "session.update", "session": [
            "type": "transcription",
            "audio": ["input": ["format": ["type": "audio/pcm", "rate": 24000],
                                "transcription": ["model": Self.model],
                                "turn_detection": NSNull()]],
        ]])
        receive(t)
        log("voice: transcription socket connecting")
    }

    func begin(onDelta: @escaping (String) -> Void) {
        text = ""
        self.onDelta = onDelta
        onDone = nil
        send(["type": "input_audio_buffer.clear"])
    }

    func append(_ pcm: Data) {
        send(["type": "input_audio_buffer.append", "audio": pcm.base64EncodedString()])
    }

    /// Ends the turn; `done` gets the final transcript or an error (socket down, server error, 10 s timeout).
    func commit(_ done: @escaping (Result<String, VoiceError>) -> Void) {
        guard task != nil else { return done(.failure(VoiceError("transcription socket not connected"))) }
        onDone = done
        send(["type": "input_audio_buffer.commit"])
        q.asyncAfter(deadline: .now() + 10) { [weak self] in self?.finish(.failure(VoiceError("transcription timed out"))) }
    }

    private func finish(_ r: Result<String, VoiceError>) {
        guard let d = onDone else { return }
        onDone = nil
        onDelta = nil
        d(r)
    }

    private func send(_ obj: [String: Any]) {
        guard let t = task, let data = try? JSONSerialization.data(withJSONObject: obj) else { return }
        t.send(.string(String(decoding: data, as: UTF8.self))) { [weak self] err in
            guard let err else { return }
            self?.q.async { self?.drop(t, "send failed: \(err.localizedDescription)") }
        }
    }

    private func receive(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] r in
            self?.q.async {
                guard let self, t === self.task else { return }
                switch r {
                case .failure(let e): self.drop(t, e.localizedDescription)
                case .success(let m):
                    if case .string(let s) = m, let obj = (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any] { self.handle(obj) }
                    self.receive(t)
                }
            }
        }
    }

    private func handle(_ obj: [String: Any]) {
        switch obj["type"] as? String ?? "" {
        case "session.created", "session.updated":
            log("voice: transcription socket \(obj["type"]!)")
        case "conversation.item.input_audio_transcription.delta":
            text += obj["delta"] as? String ?? ""
            onDelta?(text)
        case "conversation.item.input_audio_transcription.completed":
            finish(.success(obj["transcript"] as? String ?? text))
        case "error":
            let msg = (obj["error"] as? [String: Any])?["message"] as? String ?? "unknown error"
            log("voice: realtime error: \(msg)")
            finish(.failure(VoiceError(msg)))
        default: break
        }
    }

    private func drop(_ t: URLSessionWebSocketTask, _ why: String) {
        guard t === task else { return }
        log("voice: transcription socket closed (\(why)); reconnecting on next hold")
        t.cancel(with: .goingAway, reason: nil)
        task = nil
        finish(.failure(VoiceError("transcription socket closed: \(why)")))
    }

    /// Fallback: the whole clip through POST /v1/audio/transcriptions. Blocking.
    static func transcribeClip(_ pcm: Data, key: String) throws -> String {
        var req = openAIRequest("audio/transcriptions", key: key, timeout: 20)
        let boundary = "castle-\(UUID().uuidString)"
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        var body = Data()
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\ngpt-4o-mini-transcribe\r\n".utf8))
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\nContent-Type: audio/wav\r\n\r\n".utf8))
        body.append(Mic.wav(pcm))
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        req.httpBody = body
        return try httpJSON(req)["text"] as? String ?? ""
    }
}

// MARK: tools (strict JSON schemas, validated before executing)

/// Minimal JSON-schema check for the subset the tool schemas use: type (incl. unions), enum, properties,
/// required, additionalProperties false. Returns an error message or nil.
func validateJSON(_ v: Any, _ s: [String: Any], _ path: String = "arguments") -> String? {
    let types = (s["type"] as? [String]) ?? [s["type"] as? String].compactMap { $0 }
    func isBool(_ x: Any) -> Bool { (x as? NSNumber).map { CFGetTypeID($0) == CFBooleanGetTypeID() } ?? false }
    func matches(_ t: String) -> Bool {
        switch t {
        case "string": return v is String
        case "integer": return (v as? NSNumber).map { !isBool($0) && $0.doubleValue == $0.doubleValue.rounded() } ?? false
        case "number": return v is NSNumber && !isBool(v)
        case "boolean": return isBool(v)
        case "null": return v is NSNull
        case "object": return v is [String: Any]
        case "array": return v is [Any]
        default: return false
        }
    }
    if !types.isEmpty, !types.contains(where: matches) { return "\(path): expected \(types.joined(separator: " or "))" }
    if let e = s["enum"] as? [Any], !e.contains(where: { ($0 as AnyObject).isEqual(v) }) {
        return "\(path): must be one of \(e.map { $0 is NSNull ? "null" : "\($0)" }.joined(separator: ", "))"
    }
    if let arr = v as? [Any], let items = s["items"] as? [String: Any] {
        for (i, x) in arr.enumerated() { if let err = validateJSON(x, items, "\(path)[\(i)]") { return err } }
    }
    if let o = v as? [String: Any], let props = s["properties"] as? [String: [String: Any]] {
        for r in s["required"] as? [String] ?? [] where o[r] == nil { return "\(path): missing \"\(r)\"" }
        if s["additionalProperties"] as? Bool == false, let extra = o.keys.sorted().first(where: { props[$0] == nil }) {
            return "\(path): unexpected \"\(extra)\""
        }
        for k in props.keys.sorted() { if let x = o[k], let err = validateJSON(x, props[k]!, k) { return err } }
    }
    return nil
}

struct ToolResult {
    let ok: Bool
    let text: String
}

/// nickname -> slack:// deep link or https permalink, in ~/.config/mind-castle/slack.json.
struct SlackBook {
    let path: URL
    static let `default` = SlackBook(path: URL(fileURLWithPath: ProcessInfo.processInfo.environment["MIND_CASTLE_SLACK_JSON"]
        ?? NSHomeDirectory() + "/.config/mind-castle/slack.json"))

    static func normalize(_ s: String) -> String {
        s.lowercased().trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "#@ "))
    }

    static func validLink(_ s: String) -> Bool {
        guard let u = URL(string: s), let scheme = u.scheme?.lowercased() else { return false }
        return scheme == "slack" || (scheme == "https" && (u.host ?? "").hasSuffix("slack.com"))
    }

    func load() -> [String: String] {
        guard let d = try? Data(contentsOf: path) else { return [:] }
        return (try? JSONSerialization.jsonObject(with: d)) as? [String: String] ?? [:]
    }

    /// Exact (normalized) match, else a unique substring match.
    func resolve(_ name: String) -> Result<(String, String), VoiceError> {
        let book = load(), n = Self.normalize(name)
        let norm = Dictionary(book.map { (Self.normalize($0.key), ($0.key, $0.value)) }, uniquingKeysWith: { a, _ in a })
        if let hit = norm[n] { return .success(hit) }
        let partial = norm.filter { $0.key.contains(n) || (!$0.key.isEmpty && n.contains($0.key)) }
        if partial.count == 1, let hit = partial.first { return .success(hit.value) }
        let known = book.keys.sorted().joined(separator: ", ")
        if partial.count > 1 { return .failure(VoiceError("\"\(name)\" is ambiguous: \(partial.values.map { $0.0 }.sorted().joined(separator: ", "))")) }
        return .failure(VoiceError("unknown Slack destination \"\(name)\" (known: \(known.isEmpty ? "none" : known)). Ask the user for its link, then use slack_remember"))
    }

    func remember(_ name: String, _ link: String) throws {
        var book = load()
        book[Self.normalize(name)] = link
        try FileManager.default.createDirectory(at: path.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONSerialization.data(withJSONObject: book, options: [.prettyPrinted, .sortedKeys]).write(to: path, options: .atomic)
    }
}

enum VoiceTools {
    static let layoutCmds = ["nudge_left", "nudge_right", "nudge_up", "nudge_down", "closer", "farther", "bigger", "smaller",
                             "center", "preset", "show", "hide", "focus", "undo", "redo", "recenter", "tidy", "passthrough",
                             "darker", "lighter"]
    static let globalCmds: Set = ["undo", "redo", "recenter", "tidy", "passthrough", "darker", "lighter"]

    static func obj(_ props: [String: [String: Any]]) -> [String: Any] {
        ["type": "object", "properties": props, "required": props.keys.sorted(), "additionalProperties": false]
    }

    static let str: [String: Any] = ["type": "string"]
    static let layoutAction = obj([
        "cmd": ["type": "string", "enum": layoutCmds],
        "window": ["type": ["string", "null"], "description": "App or title substring of the target window; null = the active panel"],
        "steps": ["type": ["integer", "null"], "description": "Repeat count 1-10 for nudge/closer/farther/bigger/smaller/darker/lighter; null = 1"],
        "preset": ["type": ["string", "null"], "enum": ["editor", "side", "glance", NSNull()], "description": "Only for cmd=preset"],
    ])
    static let optStr: [String: Any] = ["type": ["string", "null"]]

    static let schemas: [String: [String: Any]] = [
        // One call carries the whole ordered sequence: the planner tends to emit one call per response.
        "layout": obj(["actions": ["type": "array", "items": layoutAction, "description": "Run in order"]]),
        "open_url": obj(["url": ["type": "string", "description": "http, https or slack:// URL"]]),
        "slack_open": obj(["name": ["type": "string", "description": "Channel, DM or nickname, e.g. standup, #design, alex"]]),
        "slack_remember": obj(["name": str, "link": ["type": "string", "description": "slack:// deep link or https://….slack.com permalink"]]),
        "focus_app": obj(["name": ["type": "string", "description": "App name or bundle id"]]),
        "search_web": obj(["query": str, "site": ["type": ["string", "null"], "description": "Domain to restrict to, e.g. github.com; null = whole web"]]),
        "computer_use": obj(["task": ["type": "string", "description": "What to do on the Mac, in one sentence"]]),
    ]

    static func descriptions(_ windows: [VoiceWindow]) -> [String: String] {
        var list = windows.prefix(40).map { w in
            "- \(w.shown ? "[shown]" : "[available]") \(w.app) — \(w.title.prefix(70))"
        }.joined(separator: "\n")
        if list.isEmpty { list = "(none)" }
        return [
            "layout": """
                Arrange the headset's window panels: one call with every step in order (e.g. show X, then bigger). nudge_* moves a panel 5°/5 cm per step, closer/farther 10 cm, bigger/smaller 10%; \
                center puts it straight ahead; preset applies editor (big, ahead), side or glance (small, far); show adds a window \
                as a panel, hide removes it, focus makes it the active panel; undo/redo/recenter/tidy/passthrough/darker/lighter act \
                on the whole scene (window ignored). Windows on the Mac:\n\(list)
                """,
            "open_url": "Open a URL on the Mac in its default app.",
            "slack_open": "Open a Slack channel or DM saved in the user's Slack address book by nickname.",
            "slack_remember": "Save a Slack destination (nickname -> link) in the address book, when the user gives a link.",
            "focus_app": "Bring a Mac app to the front (launches it if not running) so typing goes to it.",
            "search_web": "Open a web search for the query in the browser.",
            "computer_use": "Operate the Mac's GUI for tasks no other tool covers. Requires the user's confirmation.",
        ]
    }

    static func definitions(_ windows: [VoiceWindow]) -> [[String: Any]] {
        let desc = descriptions(windows)
        return schemas.keys.sorted().map { name in
            ["type": "function", "name": name, "description": desc[name]!, "parameters": schemas[name]!, "strict": true]
        }
    }

    /// Picks the window a spoken name refers to: exact app/title beats substring; shown panels win ties.
    static func resolveWindow(_ name: String, in list: [VoiceWindow]) -> VoiceWindow? {
        let n = name.lowercased().trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return nil }
        func score(_ w: VoiceWindow) -> Int {
            let a = w.app.lowercased(), t = w.title.lowercased()
            if a == n || t == n { return 3 }
            if a.contains(n) || (a.count >= 3 && n.contains(a)) { return 2 } // "visual studio code" -> Code
            return t.contains(n) ? 1 : 0
        }
        let scored = list.map { ($0, score($0)) }.filter { $0.1 > 0 }
        guard let best = scored.map({ $0.1 }).max() else { return nil }
        let top = scored.filter { $0.1 == best }.map { $0.0 }
        return top.first(where: { $0.shown }) ?? top.first
    }

    /// layout args -> COMMAND (20) JSON, or an error for the model.
    static func command(_ a: [String: Any], windows: [VoiceWindow]) -> Result<([String: Any], String), VoiceError> {
        let cmd = a["cmd"] as! String
        let steps = (a["steps"] as? NSNumber)?.intValue ?? 1
        guard (1...10).contains(steps) else { return .failure(VoiceError("steps must be 1-10")) }
        var out: [String: Any]
        switch cmd {
        case "nudge_left": out = ["cmd": "nudge", "dtheta": -steps]
        case "nudge_right": out = ["cmd": "nudge", "dtheta": steps]
        case "nudge_up": out = ["cmd": "nudge", "dy": steps]
        case "nudge_down": out = ["cmd": "nudge", "dy": -steps]
        case "closer": out = ["cmd": "depth", "d": -steps]
        case "farther": out = ["cmd": "depth", "d": steps]
        case "bigger": out = ["cmd": "size", "d": steps]
        case "smaller": out = ["cmd": "size", "d": -steps]
        case "darker": out = ["cmd": "dim", "d": steps]
        case "lighter": out = ["cmd": "dim", "d": -steps]
        case "preset":
            guard let p = a["preset"] as? String else { return .failure(VoiceError("cmd=preset needs preset")) }
            out = ["cmd": "preset", "name": p]
        default: out = ["cmd": cmd]
        }
        var target = "active panel"
        if !globalCmds.contains(cmd), let name = a["window"] as? String {
            guard let w = resolveWindow(name, in: windows) else {
                let names = Set(windows.map { $0.app }).sorted().joined(separator: ", ")
                return .failure(VoiceError("no window matches \"\(name)\" (apps: \(names.isEmpty ? "none" : names))"))
            }
            // The headset matches "window" by substring (id preferred if it supports it): send the canonical name.
            out["window"] = windows.filter { $0.app == w.app }.count == 1 ? w.app : w.title
            out["id"] = w.id
            target = w.app
        } else if ["show", "hide", "focus"].contains(cmd) {
            return .failure(VoiceError("cmd=\(cmd) needs a window"))
        }
        let summary: String
        switch cmd {
        case "nudge_left", "nudge_right", "nudge_up", "nudge_down": summary = "Moved \(target) \(cmd.dropFirst(6))"
        case "closer": summary = "Brought \(target) closer"
        case "farther": summary = "Pushed \(target) back"
        case "bigger": summary = "Enlarged \(target)"
        case "smaller": summary = "Shrank \(target)"
        case "center": summary = "Centered \(target)"
        case "preset": summary = "\(target): \(out["name"]!) preset"
        case "show": summary = "Showing \(target)"
        case "hide": summary = "Hid \(target)"
        case "focus": summary = "Focused \(target)"
        case "darker": summary = "Dimmed the room"
        case "lighter": summary = "Brightened the room"
        default: summary = cmd.prefix(1).uppercased() + cmd.dropFirst()
        }
        return .success((out, summary))
    }

    static func searchURL(_ query: String, site: String?) -> URL? {
        let q = site.map { "\(query) site:\($0)" } ?? query
        var c = URLComponents(string: "https://www.google.com/search")!
        c.queryItems = [URLQueryItem(name: "q", value: q)]
        return c.url
    }

    static func allowedURL(_ s: String) -> URL? {
        guard let u = URL(string: s), let scheme = u.scheme?.lowercased(), ["http", "https", "slack"].contains(scheme) else { return nil }
        return u
    }
}

// MARK: the agent

final class VoiceAgent {
    static let plannerModel = "gpt-6-luna"
    static let maxRounds = 3
    static let effort = ProcessInfo.processInfo.environment["CASTLE_VOICE_EFFORT"] ?? "none"
    static let instructions = """
        You turn one spoken command from the user of Mind Castle (a VR workspace that shows their Mac's windows as \
        panels in a headset) into tool calls. Act immediately with the tools; never ask follow-up questions. The \
        transcript may contain speech-recognition errors: infer the intent. Target windows by the names in the layout \
        tool's window list (app names as listed, e.g. "Code" not "Visual Studio Code"). Emit EVERY tool call the command \
        needs in your first response, in order — they run in sequence and you won't get another turn unless one \
        fails, so never wait for a result before the next step. Keep the user's own words in search queries. \
        Destructive or irreversible \
        actions are not available. If a tool fails, either fix the call or reply with one short sentence the user can \
        act on. When done, reply with at most 8 words for a HUD.
        """

    private let q = DispatchQueue(label: "castle.voice")
    private let planQ = DispatchQueue(label: "castle.voice.plan")
    let ptt = PushToTalk()
    private let mic = Mic()
    private lazy var stt = Transcriber(queue: q)
    private var windows: () -> [VoiceWindow] = { [] }
    private var toHeadset: (UInt8, Data) -> Bool = { _, _ in false }
    private var recording = false
    private var clip = Data() // the utterance's audio, for the fallback; discarded after the turn
    private var tRelease = 0.0

    func start(windows: @escaping () -> [VoiceWindow], send: @escaping (UInt8, Data) -> Bool) {
        self.windows = windows
        toHeadset = send
        guard !Lab.enabled else { return log("voice: lab mode, inject {\"type\":\"voice\",\"text\":\"…\"} to run the planner") }
        ptt.after = control.after
        ptt.postBackspace = postTaggedBackspace
        ptt.onPending = { [self] in q.async { if let key = OpenAIKey.get() { self.stt.warm(key: key) } } }
        ptt.onStart = { [self] in q.async { self.holdStarted() } }
        ptt.onEnd = { [self] in q.async { self.holdEnded() } }
        control.perform { [self] in control.keyHook = ptt.hook }
        log("voice: hold space in control mode to talk (key: \(OpenAIKey.get() == nil ? "MISSING" : "found"))")
    }

    private func status(_ state: String, _ text: String) {
        let data = (try? JSONSerialization.data(withJSONObject: ["state": state, "text": text], options: [.sortedKeys])) ?? Data()
        _ = toHeadset(Msg.voice, data)
        if state != "listening" || text.isEmpty { log("voice: \(state) \(text)") }
    }

    // On q.
    private func holdStarted() {
        guard let key = OpenAIKey.get() else { return status("error", OpenAIKey.missing) }
        if let err = Mic.permissionError() { return status("error", err) }
        status("listening", "")
        clip = Data()
        stt.warm(key: key)
        stt.begin { [weak self] text in self?.status("listening", text) }
        do {
            try mic.start { [weak self] pcm in
                self?.q.async {
                    guard let self, self.recording else { return }
                    self.clip.append(pcm)
                    self.stt.append(pcm)
                }
            }
            recording = true
        } catch {
            status("error", (error as? VoiceError)?.message ?? "\(error)")
        }
    }

    // On q.
    private func holdEnded() {
        guard recording else { return }
        recording = false
        mic.stop()
        tRelease = ProcessInfo.processInfo.systemUptime
        let audio = clip
        clip = Data()
        guard Double(audio.count) / 2 / Mic.rate >= 0.15 else { return status("error", "too short — hold space while you speak") }
        status("thinking", "")
        stt.commit { [self] r in
            switch r {
            case .success(let text): transcribed(text)
            case .failure(let e):
                log("voice: streaming transcription failed (\(e.message)), falling back to /v1/audio/transcriptions")
                planQ.async { [self] in
                    guard let key = OpenAIKey.get() else { return status("error", OpenAIKey.missing) }
                    do { transcribed(try Transcriber.transcribeClip(audio, key: key)) } catch {
                        status("error", (error as? VoiceError)?.message ?? error.localizedDescription)
                    }
                }
            }
        }
    }

    private func transcribed(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let ms = Int((ProcessInfo.processInfo.systemUptime - tRelease) * 1000)
        guard !t.isEmpty else { return status("error", "didn't catch that") }
        log("voice: transcript (\(ms) ms after release): \(t)")
        planQ.async { [self] in
            let r = run(t, releaseMs: ms)
            labDone?(r)
        }
    }

    /// Plans and executes one utterance. Blocking (plan queue). Returns a summary for lab replies.
    func run(_ transcript: String, releaseMs: Int?) -> [String: Any] {
        status("thinking", transcript)
        let t0 = ProcessInfo.processInfo.systemUptime
        var tTool: Double?
        var calls: [[String: Any]] = []
        let finish = { (ok: Bool, text: String) -> [String: Any] in
            let t1 = ProcessInfo.processInfo.systemUptime
            self.status(ok ? "done" : "error", text)
            let toolMs = tTool.map { Int(($0 - t0) * 1000) }, doneMs = Int((t1 - (tTool ?? t1)) * 1000)
            log("voice: timings release→transcript \(releaseMs.map { "\($0) ms" } ?? "-"), transcript→tool \(toolMs.map { "\($0) ms" } ?? "-")"
                + ", tool→done \(tTool == nil ? "-" : "\(doneMs) ms"), total plan \(Int((t1 - t0) * 1000)) ms")
            return ["ok": ok, "summary": text, "calls": calls, "transcriptToToolMs": toolMs ?? NSNull(), "planMs": Int((t1 - t0) * 1000)]
        }
        guard let key = OpenAIKey.get() else { return finish(false, OpenAIKey.missing) }
        let wins = windows()
        var body: [String: Any] = [
            "model": Self.plannerModel, "instructions": Self.instructions,
            "input": [["role": "user", "content": transcript]],
            "tools": VoiceTools.definitions(wins), "reasoning": ["effort": Self.effort], "parallel_tool_calls": true,
        ]
        for round in 1...Self.maxRounds {
            var req = openAIRequest("responses", key: key, timeout: 15)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
            let resp: [String: Any]
            do { resp = try httpJSON(req) } catch {
                return finish(false, (error as? VoiceError)?.message ?? "planner: \(error.localizedDescription)")
            }
            let items = resp["output"] as? [[String: Any]] ?? []
            let fcalls = items.filter { $0["type"] as? String == "function_call" }
            let reply = items.filter { $0["type"] as? String == "message" }
                .flatMap { ($0["content"] as? [[String: Any]]) ?? [] }
                .compactMap { $0["text"] as? String }.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
            if fcalls.isEmpty { return finish(round > 1 || !reply.isEmpty, reply.isEmpty ? "nothing to do" : reply) }
            if tTool == nil { tTool = ProcessInfo.processInfo.systemUptime }
            var outputs: [[String: Any]] = [], summaries: [String] = [], allOK = true
            for c in fcalls {
                let name = c["name"] as? String ?? "", args = c["arguments"] as? String ?? "{}"
                let r = execute(name, args, windows: wins)
                log("voice: tool \(name) \(args) -> \(r.ok ? "ok" : "error"): \(r.text)")
                calls.append(["name": name, "arguments": args, "ok": r.ok, "result": r.text])
                outputs.append(["type": "function_call_output", "call_id": c["call_id"] as? String ?? "", "output": r.text])
                summaries.append(r.text)
                allOK = allOK && r.ok
            }
            // Every call worked: the tools' own summaries are the HUD text, no extra model round trip.
            if allOK { return finish(true, summaries.joined(separator: "; ")) }
            if round == Self.maxRounds { return finish(false, summaries.joined(separator: "; ")) }
            body["previous_response_id"] = resp["id"]
            body["input"] = outputs
        }
        return finish(false, "unreachable")
    }

    /// Validates and executes one tool call. Mac side effects are only logged in --lab; layout goes to the headset.
    func execute(_ name: String, _ argsJSON: String, windows wins: [VoiceWindow]) -> ToolResult {
        guard let schema = VoiceTools.schemas[name] else { return ToolResult(ok: false, text: "unknown tool \(name)") }
        guard let a = (try? JSONSerialization.jsonObject(with: Data(argsJSON.utf8))) as? [String: Any] else {
            return ToolResult(ok: false, text: "arguments are not a JSON object")
        }
        if let err = validateJSON(a, schema) { return ToolResult(ok: false, text: "invalid arguments: \(err)") }
        let lab = Lab.enabled
        func mac(_ what: String, _ f: @escaping () -> Bool) -> Bool {
            if lab { log("lab: voice would \(what)"); return true }
            return DispatchQueue.main.sync(execute: f)
        }
        switch name {
        case "layout":
            // Resolve every step first so a bad window name sends nothing.
            var cmds: [([String: Any], String)] = []
            for (i, action) in (a["actions"] as! [[String: Any]]).enumerated() {
                switch VoiceTools.command(action, windows: wins) {
                case .failure(let e): return ToolResult(ok: false, text: "action \(i + 1): \(e.message)")
                case .success(let c): cmds.append(c)
                }
            }
            guard !cmds.isEmpty else { return ToolResult(ok: false, text: "no actions") }
            for (cmd, _) in cmds {
                let data = (try? JSONSerialization.data(withJSONObject: cmd, options: [.sortedKeys])) ?? Data()
                log("voice: COMMAND \(String(decoding: data, as: UTF8.self))")
                guard toHeadset(Msg.command, data) else { return ToolResult(ok: false, text: "no headset connected") }
            }
            return ToolResult(ok: true, text: cmds.map { $0.1 }.joined(separator: ", "))
        case "open_url":
            guard let u = VoiceTools.allowedURL(a["url"] as! String) else { return ToolResult(ok: false, text: "only http, https and slack:// URLs") }
            return mac("open \(u)", { NSWorkspace.shared.open(u) }) ? ToolResult(ok: true, text: "Opened \(u.host ?? u.absoluteString)")
                : ToolResult(ok: false, text: "could not open \(u)")
        case "slack_open":
            switch SlackBook.default.resolve(a["name"] as! String) {
            case .failure(let e): return ToolResult(ok: false, text: e.message)
            case .success(let (nick, link)):
                guard let u = URL(string: link) else { return ToolResult(ok: false, text: "bad link saved for \(nick)") }
                return mac("open \(link)", { NSWorkspace.shared.open(u) }) ? ToolResult(ok: true, text: "Opened \(nick) in Slack")
                    : ToolResult(ok: false, text: "could not open Slack link for \(nick)")
            }
        case "slack_remember":
            let n = a["name"] as! String, link = a["link"] as! String
            guard SlackBook.validLink(link) else { return ToolResult(ok: false, text: "link must be slack:// or an https://…slack.com permalink") }
            if lab { log("lab: voice would save slack \(n) -> \(link)"); return ToolResult(ok: true, text: "Saved \(n)") }
            do { try SlackBook.default.remember(n, link) } catch { return ToolResult(ok: false, text: "could not save: \(error.localizedDescription)") }
            return ToolResult(ok: true, text: "Saved \(n)")
        case "focus_app":
            let n = a["name"] as! String
            return mac("focus app \(n)", { Self.activateApp(n) }) ? ToolResult(ok: true, text: "Switched to \(n)")
                : ToolResult(ok: false, text: "no app named \(n)")
        case "search_web":
            let query = a["query"] as! String, site = a["site"] as? String
            guard let u = VoiceTools.searchURL(query, site: site) else { return ToolResult(ok: false, text: "bad query") }
            return mac("open \(u)", { NSWorkspace.shared.open(u) }) ? ToolResult(ok: true, text: "Searched \(site ?? "the web") for \(query)")
                : ToolResult(ok: false, text: "could not open the browser")
        case "computer_use":
            // Phase 2 (gpt-6-astra + PyAutoGUI behind a headset/Mac confirmation) is not built yet.
            return ToolResult(ok: false, text: "computer use is not enabled yet")
        default:
            return ToolResult(ok: false, text: "unknown tool \(name)")
        }
    }

    /// Activates a running app by name or bundle id, else launches it from the usual app folders. Main thread.
    private static func activateApp(_ name: String) -> Bool {
        let n = name.lowercased()
        let apps = NSWorkspace.shared.runningApplications.filter { $0.activationPolicy == .regular }
        if let app = apps.first(where: { $0.localizedName?.lowercased() == n || $0.bundleIdentifier?.lowercased() == n })
            ?? apps.first(where: { $0.localizedName?.lowercased().contains(n) ?? false }) {
            return app.activate()
        }
        let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: name)
            ?? ["/Applications", "/System/Applications", "/Applications/Utilities", "/System/Applications/Utilities"]
            .map { URL(fileURLWithPath: "\($0)/\(name).app") }.first { FileManager.default.fileExists(atPath: $0.path) }
        guard let url else { return false }
        NSWorkspace.shared.openApplication(at: url, configuration: .init())
        return true
    }

    private var labDone: (([String: Any]) -> Void)?

    /// Lab inject `{"type":"voice-pcm","path":"…"}`: streams a raw 24 kHz mono PCM16 file through the realtime
    /// transcription socket at real-time pace (as if spoken), then commits and plans. Waits up to 60 s.
    func labPCM(_ path: String) -> [String: Any] {
        guard let pcm = FileManager.default.contents(atPath: path) else { return ["ok": false, "summary": "can't read \(path)"] }
        guard let key = OpenAIKey.get() else { return ["ok": false, "summary": OpenAIKey.missing] }
        var out: [String: Any] = ["ok": false, "summary": "timed out"]
        let done = DispatchSemaphore(value: 0)
        q.sync {
            labDone = { out = $0; done.signal() }
            stt.warm(key: key)
            stt.begin { [weak self] text in self?.status("listening", text) }
            status("listening", "")
            recording = true
        }
        let chunk = Int(Mic.rate / 10) * 2 // 100 ms
        for off in stride(from: 0, to: pcm.count, by: chunk) {
            let piece = pcm.subdata(in: off..<min(off + chunk, pcm.count))
            q.async { self.clip.append(piece); self.stt.append(piece) }
            usleep(100_000)
        }
        q.sync { holdEnded() }
        _ = done.wait(timeout: .now() + 60)
        q.sync { labDone = nil }
        return out
    }

    /// Lab inject `{"type":"voice","text":"…"}`: runs the planner on the transcript, waits up to 45 s.
    func labRun(_ text: String) -> [String: Any] {
        var out: [String: Any] = ["ok": false, "summary": "timed out"]
        let done = DispatchSemaphore(value: 0)
        planQ.async { [self] in
            out = run(text, releaseMs: nil)
            done.signal()
        }
        _ = done.wait(timeout: .now() + 45)
        return out
    }
}

let voice = VoiceAgent()

// MARK: self-test (--dry-run-voice): no taps, mic or network

func voiceSelfTest() -> Bool {
    var ok = true
    func check(_ cond: Bool, _ what: String) {
        print("\(cond ? "PASS" : "FAIL"): \(what)")
        ok = ok && cond
    }

    // Space-hold state machine on a fake clock.
    let p = PushToTalk()
    var clock = 0.0
    var timers: [(Double, () -> Void)] = []
    var events: [String] = []
    p.after = { d, f in timers.append((clock + d, f)) }
    p.postBackspace = { events.append("bs") }
    p.onPending = { events.append("warm") }
    p.onStart = { events.append("start") }
    p.onEnd = { events.append("end") }
    func advance(to t: Double) {
        clock = t
        while let i = timers.firstIndex(where: { $0.0 <= clock }) { timers.remove(at: i).1() }
    }
    func space(_ down: Bool, _ flags: CGEventFlags = [], rep: Bool = false, on: Bool = true) -> Bool {
        p.key(down: down, code: 49, flags: flags, isRepeat: rep, on: on)
    }
    func reset() { events.removeAll(); timers.removeAll() }

    reset(); clock = 0
    let tapDown = space(true)
    clock = 0.1
    check(!tapDown && !space(false), "short tap: keyDown and keyUp pass")
    advance(to: 1)
    check(events == ["warm"] && p.state == .idle, "short tap: no backspace, no recording (\(events))")

    reset(); clock = 10
    check(!space(true), "long hold: keyDown passes")
    advance(to: 10.2)
    check(space(true, rep: true), "autorepeat before 350 ms swallowed")
    advance(to: 10.36)
    check(events == ["warm", "bs", "start"] && p.state == .recording, "hold ≥350 ms: one backspace then start (\(events))")
    check(space(true, rep: true) && space(true, rep: true), "autorepeats while recording swallowed")
    check(!space(false), "keyUp passes")
    check(events.last == "end" && p.state == .idle && events.filter { $0 == "bs" }.count == 1, "keyUp ends the turn")

    reset(); clock = 20
    check(!space(true, .maskShift) && !space(true, .maskCommand) && !space(true, [.maskControl, .maskAlternate]), "space with modifiers passes")
    advance(to: 21)
    check(events.isEmpty && p.state == .idle, "modified space never records")
    check(!space(true, rep: true, on: true), "repeat with no hold in progress passes")
    _ = space(false)

    reset(); clock = 30
    check(!space(true, on: false), "control off: space passes")
    advance(to: 31)
    check(events.isEmpty, "control off: no recording")
    _ = space(false, on: false)

    reset(); clock = 40
    _ = space(true)
    check(!p.key(down: true, code: 0, flags: [], isRepeat: false, on: true) && p.state == .typing, "another key during the hold -> typing")
    advance(to: 41)
    check(!space(true, rep: true) && !events.contains("start"), "typing: no recording, space repeats pass")
    _ = space(false)

    reset(); clock = 50
    _ = space(true)
    advance(to: 50.4)
    advance(to: 80.5)
    check(events == ["warm", "bs", "start", "end"] && p.state == .capped, "30 s cap ends the utterance")
    check(space(true, rep: true), "repeats after the cap still swallowed")
    check(!space(false) && events.filter { $0 == "end" }.count == 1 && p.state == .idle, "keyUp after cap: no second end")

    reset(); clock = 60
    _ = space(true)
    advance(to: 60.4)
    check(!space(false, on: false) && events.last == "end", "control turned off mid-hold: keyUp still ends the turn")

    // Tool schemas: strict shape + validation.
    let defs = VoiceTools.definitions([])
    let strictShape = defs.allSatisfy { d in
        let params = d["parameters"] as! [String: Any], props = params["properties"] as! [String: Any]
        return d["strict"] as? Bool == true && params["additionalProperties"] as? Bool == false
            && Set(params["required"] as! [String]) == Set(props.keys)
    }
    check(defs.count == 7 && strictShape, "7 strict tools; every property required, no additional properties")
    let layout = VoiceTools.layoutAction
    let wrap = VoiceTools.schemas["layout"]!
    func v(_ json: String, _ s: [String: Any] = layout) -> String? {
        validateJSON(try! JSONSerialization.jsonObject(with: Data(json.utf8), options: .fragmentsAllowed), s)
    }
    check(v(#"{"actions":[{"cmd":"show","window":"Slack","steps":null,"preset":null},{"cmd":"bigger","window":"Slack","steps":2,"preset":null}]}"#, wrap) == nil,
          "layout actions array validates")
    check(v(#"{"actions":[{"cmd":"show","window":"Slack","steps":null,"preset":null},{"cmd":"bigger"}]}"#, wrap) != nil, "bad item inside actions rejected")
    check(v(#"{"cmd":"show","window":"Slack","steps":null,"preset":null}"#) == nil, "valid layout args pass")
    check(v(#"{"cmd":"explode","window":null,"steps":null,"preset":null}"#) != nil, "unknown cmd rejected")
    check(v(#"{"cmd":"show","window":"Slack","steps":null}"#) != nil, "missing required key rejected")
    check(v(#"{"cmd":"show","window":"Slack","steps":null,"preset":null,"x":1}"#) != nil, "extra key rejected")
    check(v(#"{"cmd":"bigger","window":null,"steps":1.5,"preset":null}"#) != nil, "non-integer steps rejected")
    check(v(#"{"cmd":"bigger","window":null,"steps":true,"preset":null}"#) != nil, "boolean steps rejected")
    check(v(#"{"cmd":"preset","window":null,"steps":null,"preset":"huge"}"#) != nil, "bad preset enum rejected")
    check(v(#"{"url":5}"#, VoiceTools.schemas["open_url"]!) != nil, "wrong type rejected")

    // layout -> COMMAND mapping and window targeting.
    let wins = [VoiceWindow(id: 1, app: "Slack", title: "general - Acme", shown: false),
                VoiceWindow(id: 2, app: "Google Chrome", title: "Slack pricing", shown: true),
                VoiceWindow(id: 3, app: "Ghostty", title: "zsh", shown: true)]
    func cmd(_ a: [String: Any]) -> String? {
        var full: [String: Any] = ["window": NSNull(), "steps": NSNull(), "preset": NSNull()]
        a.forEach { full[$0.key] = $0.value }
        guard case .success(let (c, _)) = VoiceTools.command(full, windows: wins) else { return nil }
        return String(decoding: try! JSONSerialization.data(withJSONObject: c, options: [.sortedKeys]), as: UTF8.self)
    }
    check(cmd(["cmd": "show", "window": "slack"]) == #"{"cmd":"show","id":1,"window":"Slack"}"#, "app name beats title substring")
    check(cmd(["cmd": "show", "window": "the ghostty terminal"])?.contains(#""id":3"#) ?? false, "spoken name containing the app name matches")
    check(cmd(["cmd": "nudge_left", "steps": 3]) == #"{"cmd":"nudge","dtheta":-3}"#, "nudge_left x3 -> dtheta -3, active panel")
    check(cmd(["cmd": "closer", "window": "zsh"]) == #"{"cmd":"depth","d":-1,"id":3,"window":"Ghostty"}"#, "closer by title")
    check(cmd(["cmd": "preset", "preset": "glance"]) == #"{"cmd":"preset","name":"glance"}"#, "preset")
    check(cmd(["cmd": "tidy", "window": "nope"]) == #"{"cmd":"tidy"}"#, "scene cmds ignore window")
    check(cmd(["cmd": "show", "window": "Figma"]) == nil && cmd(["cmd": "hide"]) == nil, "unknown window / missing window rejected")
    check(cmd(["cmd": "bigger", "steps": 11]) == nil, "steps > 10 rejected")
    check(VoiceTools.allowedURL("file:///etc/passwd") == nil && VoiceTools.allowedURL("slack://open") != nil, "open_url scheme allowlist")

    // slack.json resolution.
    let tmp = URL(fileURLWithPath: NSTemporaryDirectory() + "castle-slack-\(getpid()).json")
    let book = SlackBook(path: tmp)
    try? FileManager.default.removeItem(at: tmp)
    check(book.load().isEmpty, "missing slack.json -> empty book")
    try? book.remember("#Standup", "slack://channel?team=T1&id=C1")
    try? book.remember("design-crit", "https://acme.slack.com/archives/C2")
    try? book.remember("design-review", "https://acme.slack.com/archives/C3")
    func res(_ n: String) -> String? { if case .success(let (_, l)) = book.resolve(n) { return l }; return nil }
    check(res("standup") == "slack://channel?team=T1&id=C1" && res("#Standup ") == res("standup"), "exact match, normalized (#, case, spaces)")
    check(res("crit") == "https://acme.slack.com/archives/C2", "unique substring match")
    if case .failure(let e) = book.resolve("design") { check(e.message.contains("ambiguous"), "ambiguous substring -> error") } else { check(false, "ambiguous") }
    if case .failure(let e) = book.resolve("random") { check(e.message.contains("slack_remember") && e.message.contains("standup"), "unknown -> error listing known names") } else { check(false, "unknown") }
    check(SlackBook.validLink("slack://channel?id=C1") && SlackBook.validLink("https://acme.slack.com/x")
          && !SlackBook.validLink("https://evil.com/slack.com") && !SlackBook.validLink("javascript:alert(1)"), "slack_remember link check")
    try? FileManager.default.removeItem(at: tmp)

    check(Mic.wav(Data(count: 480)).count == 44 + 480, "WAV header 44 bytes")

    print(ok ? "voice self-test: ALL PASS" : "voice self-test: FAILURES")
    return ok
}
