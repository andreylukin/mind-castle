import AppKit

extension Msg {
    static let cursor: UInt8 = 9
}

/// One CURSOR payload: `u16 hotspotX | u16 hotspotY` (image px) `| u16 pointsW | u16 pointsH | PNG`.
struct CursorImage {
    let png: Data
    let pixels: CGSize, points: CGSize, hotspot: CGPoint // hotspot in image pixels

    /// Encodes `c` from its 2x bitmap if it has one (else the closest larger, else the largest).
    init?(_ c: NSCursor) {
        let img = c.image
        let reps = img.representations.compactMap { $0 as? NSBitmapImageRep }
        let want = Int(img.size.width * 2)
        guard img.size.width > 0,
              let rep = reps.first(where: { $0.pixelsWide == want })
                ?? reps.filter({ $0.pixelsWide > want }).min(by: { $0.pixelsWide < $1.pixelsWide })
                ?? reps.max(by: { $0.pixelsWide < $1.pixelsWide }),
              let png = rep.representation(using: .png, properties: [:]) else { return nil }
        let scale = Double(rep.pixelsWide) / img.size.width
        self.png = png
        pixels = CGSize(width: rep.pixelsWide, height: rep.pixelsHigh)
        points = img.size
        hotspot = CGPoint(x: (c.hotSpot.x * scale).rounded(), y: (c.hotSpot.y * scale).rounded())
    }

    var payload: Data {
        var d = Data(capacity: 8 + png.count)
        for v in [hotspot.x, hotspot.y, points.width.rounded(), points.height.rounded()] { d.appendBE(UInt16(clamping: Int(v))) }
        d.append(png)
        return d
    }

    var summary: String {
        "\(Int(pixels.width))x\(Int(pixels.height)) px (\(Int(points.width))x\(Int(points.height)) pt), hotspot (\(Int(hotspot.x)),\(Int(hotspot.y)))"
    }
}

/// Polls the system-wide cursor at ~60 Hz while control mode is on; sends CURSOR on change. Main thread only.
final class CursorWatcher {
    private var timer: Timer?
    private var lastKey: Int?
    private let send: (Data) -> Void

    init(send: @escaping (Data) -> Void) { self.send = send }

    func start() {
        lastKey = nil // first poll always sends
        guard timer == nil else { return poll() }
        let t = Timer(timeInterval: 1.0 / 60, repeats: true) { [weak self] _ in self?.poll() }
        RunLoop.main.add(t, forMode: .common)
        timer = t
        poll()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func poll() {
        guard let c = NSCursor.currentSystem else { return }
        // Cheap change key: hotspot + the smallest rep's pixels. PNG encoding only happens on change.
        var h = Hasher()
        h.combine(c.hotSpot.x)
        h.combine(c.hotSpot.y)
        h.combine(c.image.size.width)
        h.combine(c.image.size.height)
        if let rep = c.image.representations.compactMap({ $0 as? NSBitmapImageRep }).min(by: { $0.pixelsWide < $1.pixelsWide }),
           let p = rep.bitmapData {
            h.combine(bytes: UnsafeRawBufferPointer(start: p, count: rep.bytesPerPlane * rep.numberOfPlanes))
        }
        let key = h.finalize()
        guard key != lastKey else { return }
        lastKey = key
        guard let img = CursorImage(c) else { return log("cursor: changed but could not encode") }
        log("cursor: \(img.summary)")
        send(img.payload)
    }
}
