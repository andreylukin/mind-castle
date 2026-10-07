import CoreMedia
import Foundation
import ScreenCaptureKit
import VideoToolbox

let maxWidth = 3840
let bitrate = 16_000_000

/// Native pixel size of a window (points x display backing scale), capped at `maxWidth`, even dimensions.
func pixelSize(_ frame: CGRect) -> (Int, Int) {
    var scale: CGFloat = 1
    var display: CGDirectDisplayID = 0
    var count: UInt32 = 0
    let center = CGPoint(x: frame.midX, y: frame.midY)
    if CGGetDisplaysWithPoint(center, 1, &display, &count) == .success, count > 0,
       let mode = CGDisplayCopyDisplayMode(display), mode.width > 0 {
        scale = CGFloat(mode.pixelWidth) / CGFloat(mode.width)
    }
    var w = frame.width * scale, h = frame.height * scale
    if w > CGFloat(maxWidth) { h *= CGFloat(maxWidth) / w; w = CGFloat(maxWidth) }
    return (max(2, Int(w) & ~1), max(2, Int(h) & ~1))
}

/// One SCStream + one VTCompressionSession for a single window.
/// All capture/encoder state lives on `q`.
final class WindowStream: NSObject, SCStreamOutput, SCStreamDelegate {
    let id: UInt32
    private let send: (UInt8, Data) -> Void
    private let onStop: () -> Void
    private let q: DispatchQueue
    private var stream: SCStream?
    private(set) var size: (Int, Int)

    private var session: VTCompressionSession?
    private var encW = 0, encH = 0
    private var forceKey = false
    private var lastConfig: Data?
    private var lastPixels: CVPixelBuffer?
    private var sessionGen = 0 // ignores callbacks from invalidated sessions
    private var inFlight = 0, lastSubmit: UInt64 = 0
    private var parked: (px: CVPixelBuffer, pts: CMTime, sck: UInt64)?
    private var stopped = false
    var isStopped: Bool { stopped }
    /// Frames the client's sender dropped for this window since the last call (backpressure), for the stats line.
    var takeDrops: () -> Int = { 0 }

    private var statFrames = 0, statBytes = 0, statStart = Date()
    private var statLatSum = 0.0, statLatMax = 0.0, statCoalesced = 0

    init(window: SCWindow, send: @escaping (UInt8, Data) -> Void, onStop: @escaping () -> Void) {
        id = window.windowID
        self.send = send
        self.onStop = onStop
        q = DispatchQueue(label: "castle.window.\(window.windowID)")
        size = pixelSize(window.frame)
        super.init()
        let filter = SCContentFilter(desktopIndependentWindow: window)
        let s = SCStream(filter: filter, configuration: config(), delegate: self)
        do {
            try s.addStreamOutput(self, type: .screen, sampleHandlerQueue: q)
        } catch {
            log("window \(id): addStreamOutput failed: \(error)")
        }
        stream = s
        s.startCapture { [self] err in
            if let err {
                log("window \(id): startCapture failed: \(err.localizedDescription)")
                q.async { self.finish() }
            } else {
                log("window \(id): capture started \(size.0)x\(size.1)")
            }
        }
    }

    private func config() -> SCStreamConfiguration {
        let c = SCStreamConfiguration()
        c.width = size.0
        c.height = size.1
        c.minimumFrameInterval = CMTime(value: 1, timescale: 60)
        c.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        c.colorMatrix = CGDisplayStream.yCbCrMatrix_ITU_R_709_2
        c.queueDepth = 3
        // Control mode on: the headset draws its own cursor, and the real one (moved by injected MOUSE) would be a laggy
        // second "mouse". Off: the user drives the Mac cursor directly and wants to see it in the panels.
        c.showsCursor = Self.showsCursor
        return c
    }

    /// Whether captures draw the Mac cursor (= control mode off). Set via `setShowsCursor`; new streams use it.
    private(set) static var showsCursor = true
    private static let cursorLock = NSLock()

    /// Flips cursor capture on every stream in `streams` via updateConfiguration (no restart, encoder untouched).
    static func setShowsCursor(_ on: Bool, streams: [WindowStream]) {
        cursorLock.lock()
        let changed = showsCursor != on
        showsCursor = on
        cursorLock.unlock()
        guard changed else { return }
        log("capture cursor \(on ? "shown" : "hidden") on \(streams.count) stream(s)")
        for s in streams { s.reconfigure("showsCursor=\(on)") }
    }

    private func reconfigure(_ why: String) {
        stream?.updateConfiguration(config()) { [id] err in
            if let err { log("window \(id): updateConfiguration (\(why)) failed: \(err.localizedDescription)") }
        }
    }

    /// Window bounds changed: capture at the new size; the encoder restarts when buffers change size.
    func resize(_ window: SCWindow) {
        let ns = pixelSize(window.frame)
        guard ns != size else { return }
        size = ns
        log("window \(id): resize to \(ns.0)x\(ns.1)")
        reconfigure("resize")
    }

    func requestKeyframe() {
        q.async { [self] in
            forceKey = true
            // Static windows produce no new frames, so re-encode the last one right away.
            if let px = lastPixels { submit(px, pts: CMClockGetTime(CMClockGetHostTimeClock()), sck: hostMicros()) }
        }
    }

    func stop() {
        stream?.stopCapture { _ in }
        q.async { self.finish() }
    }

    /// Lab: take the same path as an SCK stream error.
    func simulateFailure() {
        log("window \(id): stream stopped: simulated failure (lab)")
        stream?.stopCapture { _ in }
        q.async { self.finish() }
    }

    private func finish() {
        guard !stopped else { return }
        stopped = true
        stream = nil
        lastPixels = nil
        parked = nil
        if let session { VTCompressionSessionInvalidate(session) }
        session = nil
        onStop()
    }

    // MARK: SCStreamDelegate / SCStreamOutput

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        log("window \(id): stream stopped: \(error.localizedDescription)")
        q.async { self.finish() }
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, !stopped, sb.isValid,
              let att = (CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]])?.first,
              let raw = att[.status] as? Int, SCFrameStatus(rawValue: raw) == .complete,
              let px = CMSampleBufferGetImageBuffer(sb) else { return }
        let sck = hostMicros()
        lastPixels = px
        submit(px, pts: CMSampleBufferGetPresentationTimeStamp(sb), sck: sck)
    }

    // MARK: Encoder

    /// Encode now, or if the encoder still has a frame in flight, park this one (replacing an older parked frame)
    /// so a slow encode never builds a queue of stale frames.
    private func submit(_ px: CVPixelBuffer, pts: CMTime, sck: UInt64) {
        if inFlight > 0, hostMicros() - lastSubmit > 500_000 {
            log("window \(id): encoder output overdue, no longer waiting for it")
            inFlight = 0
        }
        guard inFlight == 0 else {
            if parked != nil { statCoalesced += 1 }
            parked = (px, pts, sck)
            return
        }
        encode(px, pts: pts, sck: sck)
    }

    private func encode(_ px: CVPixelBuffer, pts: CMTime, sck: UInt64) {
        guard !stopped else { return }
        let w = CVPixelBufferGetWidth(px), h = CVPixelBufferGetHeight(px)
        if session == nil || w != encW || h != encH {
            guard makeSession(w, h) else { return }
        }
        guard let session else { return }
        var props: CFDictionary?
        if forceKey {
            props = [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary
            forceKey = false
        }
        let gen = sessionGen, submitted = hostMicros()
        let st = VTCompressionSessionEncodeFrame(session, imageBuffer: px, presentationTimeStamp: pts, duration: .invalid,
                                                 frameProperties: props, infoFlagsOut: nil) { [weak self] status, _, out in
            let done = hostMicros()
            guard let self else { return }
            self.q.async { self.encoded(gen: gen, status == noErr ? out : nil, sck: sck, submit: submitted, done: done) }
        }
        if st == noErr { inFlight += 1; lastSubmit = submitted }
    }

    private func encoded(gen: Int, _ out: CMSampleBuffer?, sck: UInt64, submit: UInt64, done: UInt64) {
        guard gen == sessionGen else { return }
        inFlight = max(0, inFlight - 1)
        if let out { emit(out, sck: sck, submit: submit, done: done) }
        if let p = parked {
            parked = nil
            encode(p.px, pts: p.pts, sck: p.sck)
        }
    }

    private func makeSession(_ w: Int, _ h: Int) -> Bool {
        if let session { VTCompressionSessionInvalidate(session) }
        session = nil
        sessionGen += 1
        inFlight = 0
        func create(lowLatency: Bool) -> (OSStatus, VTCompressionSession?) {
            var spec: [CFString: Any] = [kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: true]
            if lowLatency { spec[kVTVideoEncoderSpecification_EnableLowLatencyRateControl] = true }
            var s: VTCompressionSession?
            let st = VTCompressionSessionCreate(allocator: nil, width: Int32(w), height: Int32(h), codecType: kCMVideoCodecType_H264,
                                                encoderSpecification: spec as CFDictionary, imageBufferAttributes: nil,
                                                compressedDataAllocator: nil, outputCallback: nil, refcon: nil, compressionSessionOut: &s)
            return (st, s)
        }
        var lowLatency = true
        var (st, created) = create(lowLatency: true)
        if st != noErr {
            log("window \(id): low-latency rate control unavailable (\(st)), falling back")
            lowLatency = false
            (st, created) = create(lowLatency: false)
        }
        guard st == noErr, let s = created else {
            log("window \(id): VTCompressionSessionCreate failed \(st)")
            return false
        }
        let props: [CFString: Any] = [
            kVTCompressionPropertyKey_RealTime: true,
            // Constrained Baseline has no frame reordering, so decoders can output each frame immediately.
            kVTCompressionPropertyKey_ProfileLevel: kVTProfileLevel_H264_ConstrainedBaseline_AutoLevel,
            kVTCompressionPropertyKey_AllowFrameReordering: false,
            kVTCompressionPropertyKey_ExpectedFrameRate: 60,
            kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality: true,
            kVTCompressionPropertyKey_MaxKeyFrameInterval: 60,
            kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration: 2,
            kVTCompressionPropertyKey_AverageBitRate: bitrate,
            kVTCompressionPropertyKey_DataRateLimits: [bitrate / 8 * 2, 1] as CFArray, // bytes per second, 2x headroom
            kVTCompressionPropertyKey_ColorPrimaries: kCVImageBufferColorPrimaries_ITU_R_709_2,
            kVTCompressionPropertyKey_TransferFunction: kCVImageBufferTransferFunction_ITU_R_709_2,
            kVTCompressionPropertyKey_YCbCrMatrix: kCVImageBufferYCbCrMatrix_ITU_R_709_2,
        ]
        for (k, v) in props {
            let r = VTSessionSetProperty(s, key: k, value: v as CFTypeRef)
            if r != noErr { log("window \(id): set \(k) failed \(r)") }
        }
        VTCompressionSessionPrepareToEncodeFrames(s)
        session = s
        encW = w; encH = h
        lastConfig = nil
        log("window \(id): encoder start \(w)x\(h) H.264 CBP \(bitrate / 1_000_000) Mbps, low-latency RC \(lowLatency ? "on" : "off")")
        return true
    }

    private func emit(_ sb: CMSampleBuffer, sck: UInt64, submit: UInt64, done: UInt64) {
        guard !stopped, let fmt = CMSampleBufferGetFormatDescription(sb), let block = CMSampleBufferGetDataBuffer(sb) else { return }
        let atts = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[CFString: Any]]
        let isKey = !((atts?.first?[kCMSampleAttachmentKey_NotSync] as? Bool) ?? false)

        if isKey, let cfg = parameterSets(fmt), cfg != lastConfig {
            lastConfig = cfg
            send(Msg.codecConfig, cfg)
        }
        guard lastConfig != nil else { return } // never send frames before config

        var avcc = Data(count: CMBlockBufferGetDataLength(block))
        let st = avcc.withUnsafeMutableBytes { CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: $0.count, destination: $0.baseAddress!) }
        guard st == noErr else { return }

        let pts = CMSampleBufferGetPresentationTimeStamp(sb)
        let ptsUs = micros(pts)
        var payload = Data(capacity: avcc.count + 16)
        payload.appendBE(ptsUs)
        // AVCC (4-byte big-endian lengths) -> Annex-B start codes.
        var off = 0
        while off + 4 <= avcc.count {
            let len = avcc[off..<off + 4].reduce(0) { $0 << 8 | Int($1) }
            off += 4
            guard len > 0, off + len <= avcc.count else { break }
            payload.append(contentsOf: [0, 0, 0, 1])
            payload.append(avcc[off..<off + len])
            off += len
        }
        Trace.shared.frame(id: id, pts: ptsUs, sck: sck, submit: submit, done: done, bytes: payload.count, keyframe: isKey)
        send(isKey ? Msg.keyframe : Msg.frame, payload)

        statFrames += 1
        statBytes += payload.count
        // pts is the SCK display time on the host clock, so this is capture + encode latency.
        let lat = CMTimeGetSeconds(CMTimeSubtract(CMClockGetTime(CMClockGetHostTimeClock()), pts)) * 1000
        statLatSum += lat; statLatMax = max(statLatMax, lat)
        let dt = Date().timeIntervalSince(statStart)
        if dt >= 5 {
            log(String(format: "window %u: %.1f fps %.2f Mbps (%dx%d) capture+encode avg %.1f ms max %.1f ms, coalesced %d, dropped %d", id,
                       Double(statFrames) / dt, Double(statBytes) * 8 / dt / 1_000_000, encW, encH,
                       statLatSum / Double(statFrames), statLatMax, statCoalesced, takeDrops()))
            statFrames = 0; statBytes = 0; statStart = Date(); statLatSum = 0; statLatMax = 0; statCoalesced = 0
        }
    }

    private func parameterSets(_ fmt: CMFormatDescription) -> Data? {
        var count = 0
        guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: 0, parameterSetPointerOut: nil,
                                                                 parameterSetSizeOut: nil, parameterSetCountOut: &count,
                                                                 nalUnitHeaderLengthOut: nil) == noErr else { return nil }
        var out = Data()
        for i in 0..<count {
            var ptr: UnsafePointer<UInt8>?
            var len = 0
            guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: i, parameterSetPointerOut: &ptr,
                                                                     parameterSetSizeOut: &len, parameterSetCountOut: nil,
                                                                     nalUnitHeaderLengthOut: nil) == noErr, let ptr else { return nil }
            out.append(contentsOf: [0, 0, 0, 1])
            out.append(ptr, count: len)
        }
        return out
    }
}
