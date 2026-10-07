import AppKit
import CGVirtualDisplayShim

/// `--virtual-display`: a large invisible display that subscribed windows are moved onto, tiled without overlap,
/// so nothing on the real screen can occlude them (occluded apps stop drawing and SCK gets no frames).
/// Original frames are restored on unsubscribe, client disconnect and exit.
final class VirtualDisplay {
    struct Win { let id: UInt32; let pid: pid_t; let title: String }

    let displayID: CGDirectDisplayID
    private let display: NSObject // keeps the display alive
    private let q = DispatchQueue(label: "castle.vdisplay")
    private var placed: [(id: UInt32, el: AXUIElement, original: CGRect)] = []

    init?(pointsWide: UInt32 = 2560, pointsHigh: UInt32 = 1440) {
        var id: CGDirectDisplayID = 0
        var err: NSString?
        guard let d = CVDCreate(pointsWide, pointsHigh, true, "Mind Castle", &id, &err) else {
            log("virtual display: unavailable (\(err ?? "unknown error")), continuing without it")
            return nil
        }
        display = d
        displayID = id
        // The display comes online asynchronously.
        for _ in 0..<30 where CGDisplayBounds(id).isEmpty { usleep(100_000) }
        let mode = CGDisplayCopyDisplayMode(id)
        log("virtual display: id \(id), bounds \(CGDisplayBounds(id)), \(mode?.pixelWidth ?? 0)x\(mode?.pixelHeight ?? 0) px")
        onTermination { [self] in restoreAll() }
    }

    /// `wins` is the full subscribed set: newcomers are moved on, dropped ones restored, then all re-tiled.
    func arrange(_ wins: [Win]) {
        q.async { [self] in
            let want = Set(wins.map(\.id))
            for p in placed where !want.contains(p.id) {
                axSetFrame(p.el, p.original)
                log("virtual display: window \(p.id) restored to \(p.original)")
            }
            placed.removeAll { !want.contains($0.id) }
            for w in wins where !placed.contains(where: { $0.id == w.id }) {
                guard let el = axWindow(id: w.id, pid: w.pid, title: w.title), let f = axFrame(el) else {
                    log("virtual display: window \(w.id) not movable (no AX window), left in place")
                    continue
                }
                placed.append((w.id, el, f))
            }
            tile()
        }
    }

    func restoreAll() {
        q.sync {
            for p in placed { axSetFrame(p.el, p.original) }
            if !placed.isEmpty { log("virtual display: restored \(placed.count) window(s)") }
            placed = []
        }
    }

    /// Shelf packing left to right, top to bottom, keeping each window's size (shrunk only if larger than the display).
    private func tile() {
        let b = CGDisplayBounds(displayID)
        guard !b.isEmpty else { return log("virtual display: no bounds, not tiling") }
        let gap = 16.0, top = 40.0 // clear a possible menu bar on this display
        var x = b.minX + gap, y = b.minY + top, rowH = 0.0
        for p in placed {
            let w = min(p.original.width, b.width - 2 * gap), h = min(p.original.height, b.height - top - gap)
            if x + w > b.maxX - gap, x > b.minX + gap {
                x = b.minX + gap
                y += rowH + gap
                rowH = 0
            }
            let target = CGRect(x: x, y: y, width: w, height: h)
            axSetFrame(p.el, target)
            let got = axFrame(p.el) ?? .null
            log("virtual display: window \(p.id) -> \(target)\(got.integral == target.integral ? "" : " (actual \(got))")")
            x += w + gap
            rowH = max(rowH, h)
        }
    }
}
