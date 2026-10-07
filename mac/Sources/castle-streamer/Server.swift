import Foundation

/// One connected headset. Reads run on a dedicated thread; writes on a writer thread that always sends control
/// messages (everything but FRAME/KEYFRAME) first. Video is lossy so a slow link never queues seconds of frames:
/// a frame arriving while its window still has an unwritten frame (or with >2 MB of video queued) drops that
/// window's pending P-frames and itself — every P-frame references the previous one — and the window then skips
/// frames until an IDR, which is requested from the encoder (`onNeedKeyframe`, at most every 250 ms).
final class Client {
    static let maxVideoBytes = 2 << 20
    static let sendBuffer: Int32 = 256 << 10 // fixed kernel buffer: autotuning can grow it to MBs of hidden latency
    static let notSentLowat: Int32 = 32 << 10 // writable only while <32 KB is unsent, so the next pick happens late
    let fd: Int32
    var onNeedKeyframe: (UInt32) -> Void = { _ in }

    private enum Item { case msg(Data), pong(Data) }
    private struct Frame { let wid: UInt32; let msg: Data; let key: Bool; let pts: UInt64 }

    private let cond = NSCondition()
    private var closed = false
    private var control: [Item] = []
    private var video: [Frame] = []
    private var videoBytes = 0
    private var waitingKey: [UInt32: Double] = [:] // window -> last keyframe request (uptime s)
    private var drops: [UInt32: Int] = [:]
    private let writerDone = DispatchSemaphore(value: 0)

    init(fd: Int32) {
        self.fd = fd
        var buf = Self.sendBuffer
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buf, socklen_t(MemoryLayout<Int32>.size))
        var lowat = Self.notSentLowat
        setsockopt(fd, IPPROTO_TCP, TCP_NOTSENT_LOWAT, &lowat, socklen_t(MemoryLayout<Int32>.size))
        let t = Thread { [self] in writeLoop() }
        t.name = "castle.write"
        t.qualityOfService = .userInteractive
        t.start()
    }

    func send(_ type: UInt8, _ windowId: UInt32, _ payload: Data = Data()) {
        let msg = Self.message(type, windowId, payload)
        if type == Msg.frame || type == Msg.keyframe {
            let pts = payload.count >= 8 ? payload.prefix(8).reduce(UInt64(0)) { $0 << 8 | UInt64($1) } : 0
            return enqueueVideo(Frame(wid: windowId, msg: msg, key: type == Msg.keyframe, pts: pts))
        }
        cond.lock()
        // A new codec config or a gone window makes that window's queued frames useless.
        if type == Msg.codecConfig || type == Msg.windowGone { purgeVideo(windowId, keepKey: false) }
        control.append(.msg(msg))
        cond.signal()
        cond.unlock()
    }

    /// PONG for a PING, stamped by the writer just before it hits the socket.
    func pong(_ headsetT0: Data) {
        cond.lock()
        control.append(.pong(headsetT0))
        cond.signal()
        cond.unlock()
    }

    /// Frames dropped for `wid` since the last call (for the 5 s stats line).
    func takeDrops(_ wid: UInt32) -> Int {
        cond.lock()
        defer { cond.unlock() }
        return drops.removeValue(forKey: wid) ?? 0
    }

    private func enqueueVideo(_ f: Frame) {
        var requestKey = false
        cond.lock()
        let now = ProcessInfo.processInfo.systemUptime
        if f.key {
            purgeVideo(f.wid, keepKey: false) // superseded by this IDR
            waitingKey[f.wid] = nil
            append(f)
        } else if let asked = waitingKey[f.wid] {
            drops[f.wid, default: 0] += 1
            if now - asked >= 0.25 { waitingKey[f.wid] = now; requestKey = true }
        } else if video.contains(where: { $0.wid == f.wid }) || videoBytes + f.msg.count > Self.maxVideoBytes {
            purgeVideo(f.wid, keepKey: true)
            drops[f.wid, default: 0] += 1
            waitingKey[f.wid] = now
            requestKey = true
        } else {
            append(f)
        }
        cond.unlock()
        if requestKey { onNeedKeyframe(f.wid) }
    }

    private func append(_ f: Frame) {
        video.append(f)
        videoBytes += f.msg.count
        cond.signal()
    }

    /// Drops queued frames of `wid` (counted as drops). `keepKey` keeps a queued IDR: it decodes on its own.
    private func purgeVideo(_ wid: UInt32, keepKey: Bool) {
        video.removeAll { f in
            guard f.wid == wid, !(keepKey && f.key) else { return false }
            videoBytes -= f.msg.count
            drops[wid, default: 0] += 1
            return true
        }
    }

    private static func message(_ type: UInt8, _ windowId: UInt32, _ payload: Data) -> Data {
        var msg = Data(capacity: 9 + payload.count)
        msg.append(type)
        msg.appendBE(windowId)
        msg.appendBE(UInt32(payload.count))
        msg.append(payload)
        return msg
    }

    private func writeLoop() {
        defer { writerDone.signal() }
        while true {
            cond.lock()
            while !closed && control.isEmpty && video.isEmpty { cond.wait() }
            cond.unlock()
            // Choose what to send only once the socket can take it: whatever arrived meanwhile (control first,
            // the newest video) wins, instead of committing early to a frame that then waits in the kernel.
            var pfd = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
            while !isClosed && poll(&pfd, 1, 100) == 0 {}
            cond.lock()
            if closed { cond.unlock(); return }
            if control.isEmpty && video.isEmpty { cond.unlock(); continue }
            var item: Item?, frame: Frame?
            if !control.isEmpty {
                item = control.removeFirst()
            } else {
                frame = video.removeFirst()
                videoBytes -= frame!.msg.count
            }
            cond.unlock()

            let ok: Bool
            switch item {
            case .msg(let m): ok = write(m)
            case .pong(let t0):
                var p = t0
                p.appendBE(hostMicros())
                ok = write(Self.message(Msg.pong, 0, p))
            case nil:
                ok = write(frame!.msg)
                if ok { Trace.shared.sent(id: frame!.wid, pts: frame!.pts, at: hostMicros()) }
            }
            if !ok { close(); return }
        }
    }

    private var isClosed: Bool {
        cond.lock()
        defer { cond.unlock() }
        return closed
    }

    /// Blocking write on the writer thread.
    private func write(_ msg: Data) -> Bool {
        msg.withUnsafeBytes { buf -> Bool in
            var off = 0
            while off < buf.count {
                let n = Darwin.send(fd, buf.baseAddress! + off, buf.count - off, 0)
                if n <= 0 { if n < 0 && errno == EINTR { continue }; return false }
                off += n
            }
            return true
        }
    }

    /// Shuts the socket down; the reader thread then sees EOF and reports the disconnect.
    func close() {
        cond.lock()
        if !closed {
            closed = true
            shutdown(fd, SHUT_RDWR) // also unblocks a writer stuck in send()
        }
        cond.broadcast()
        cond.unlock()
    }

    /// Called by the reader thread once; releases the fd after the writer thread has exited.
    fileprivate func finish() {
        close()
        writerDone.wait()
        Darwin.close(fd)
    }

    fileprivate func readExact(_ n: Int) -> Data? {
        var data = Data(count: n)
        var off = 0
        let ok = data.withUnsafeMutableBytes { buf -> Bool in
            while off < n {
                let r = recv(fd, buf.baseAddress! + off, n - off, 0)
                if r <= 0 { if r < 0 && errno == EINTR { continue }; return false }
                off += r
            }
            return true
        }
        return ok ? data : nil
    }
}

/// POSIX TCP listener. A new connection replaces the previous client (handles stale adb-reverse sockets).
final class Server {
    var onConnect: (Client) -> Void = { _ in }
    var onMessage: (Client, UInt8, UInt32, Data) -> Void = { _, _, _, _ in }
    var onDisconnect: (Client) -> Void = { _ in }

    func start(port: UInt16) throws {
        signal(SIGPIPE, SIG_IGN)
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

        Thread.detachNewThread { [self] in
            while true {
                let cfd = accept(lfd, nil, nil)
                if cfd < 0 { continue }
                setsockopt(cfd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
                setsockopt(cfd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
                let client = Client(fd: cfd)
                Thread.detachNewThread { [self] in readLoop(client) }
            }
        }
    }

    private func readLoop(_ c: Client) {
        onConnect(c)
        while let hdr = c.readExact(9) {
            let type = hdr[0]
            let wid = hdr[1..<5].reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
            let len = Int(hdr[5..<9].reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
            guard len < 1 << 20, let payload = len == 0 ? Data() : c.readExact(len) else { break }
            // Answered on the reader thread: no state-queue hop between receiving PING and stamping PONG.
            switch type {
            case Msg.ping where payload.count >= 8: c.pong(payload.prefix(8))
            case Msg.frameReport: Trace.shared.report(payload)
            default: onMessage(c, type, wid, payload)
            }
        }
        c.finish()
        onDisconnect(c)
    }
}
