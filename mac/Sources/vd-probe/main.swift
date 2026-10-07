import CGVirtualDisplayShim
import CoreGraphics
import Foundation

func activeDisplays() -> [CGDirectDisplayID] {
    var ids = [CGDirectDisplayID](repeating: 0, count: 16)
    var n: UInt32 = 0
    CGGetActiveDisplayList(16, &ids, &n)
    return Array(ids.prefix(Int(n)))
}

func describe(_ d: CGDirectDisplayID) -> String {
    let b = CGDisplayBounds(d)
    let mode = CGDisplayCopyDisplayMode(d)
    return "id=\(d) bounds=\(b) mode=\(mode?.width ?? 0)x\(mode?.height ?? 0)pt/\(mode?.pixelWidth ?? 0)x\(mode?.pixelHeight ?? 0)px pixelsWide=\(CGDisplayPixelsWide(d)) builtin=\(CGDisplayIsBuiltin(d) != 0)"
}

print("before:"); activeDisplays().forEach { print("  " + describe($0)) }
var id: CGDirectDisplayID = 0
var err: NSString?
var vd: NSObject? = CVDCreate(2560, 1440, true, "Mind Castle probe", &id, &err)
guard vd != nil else { print("FAILED: \(err ?? "?")"); exit(1) }
print("created virtual display id=\(id)")
var seen = false
for _ in 0..<30 where !seen { // up to 3 s for it to come online
    RunLoop.current.run(until: Date().addingTimeInterval(0.1))
    seen = activeDisplays().contains(id)
}
RunLoop.current.run(until: Date().addingTimeInterval(1)) // let the mode settle
print("active with virtual:"); activeDisplays().forEach { print("  " + describe($0)) }
print(seen ? "OK: virtual display is active" : "FAIL: virtual display never appeared")
vd = nil
for _ in 0..<30 where activeDisplays().contains(id) { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
print("after release:"); activeDisplays().forEach { print("  " + describe($0)) }
print(activeDisplays().contains(id) ? "FAIL: still active after release" : "OK: destroyed")
exit(seen ? 0 : 1)
