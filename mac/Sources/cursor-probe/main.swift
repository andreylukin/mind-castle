import AppKit

// Checks that posted (tagged) mouseMoved events make another process's window update the system cursor,
// read via NSCursor.currentSystem as castle-streamer does — with the mouse associated and detached.
// `cursor-probe` spawns `cursor-probe window` (a non-activating window: text view left, link-style area right),
// moves the cursor over each half, reads the cursor, and puts the cursor back. Touches no other app's windows.

let tag: Int64 = 0x4D43_4153 // castleEventTag

final class HandView: NSView {
    override func resetCursorRects() { addCursorRect(bounds, cursor: .pointingHand) }
    override func updateTrackingAreas() {
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: bounds, options: [.cursorUpdate, .mouseMoved, .activeAlways, .inVisibleRect], owner: self))
    }
    override func cursorUpdate(with event: NSEvent) { NSCursor.pointingHand.set() }
    override func mouseMoved(with event: NSEvent) { NSCursor.pointingHand.set() }
    override func draw(_ r: NSRect) { NSColor.systemBlue.setFill(); r.fill() }
}

if CommandLine.arguments.dropFirst().first == "window" {
    let app = NSApplication.shared
    app.setActivationPolicy(.accessory)
    let w = NSPanel(contentRect: NSRect(x: 300, y: 300, width: 400, height: 160), styleMask: [.titled, .nonactivatingPanel],
                    backing: .buffered, defer: false)
    w.title = "cursor-probe"
    w.level = .floating
    let text = NSTextView(frame: NSRect(x: 0, y: 0, width: 200, height: 160))
    text.string = "probe text"
    w.contentView!.addSubview(text)
    w.contentView!.addSubview(HandView(frame: NSRect(x: 200, y: 0, width: 200, height: 160)))
    w.acceptsMouseMovedEvents = true
    w.orderFrontRegardless()
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
        let f = w.contentLayoutRect, primaryH = NSScreen.screens[0].frame.height
        let origin = w.convertPoint(toScreen: f.origin)
        // content rect in CG global coords (top-left origin)
        print("ready \(origin.x) \(primaryH - origin.y - f.height) \(f.width) \(f.height)")
        fflush(stdout)
    }
    DispatchQueue.main.asyncAfter(deadline: .now() + 15) { exit(0) }
    app.run()
}

_ = NSApplication.shared
// Refuse if the live streamer is in control mode (built-in gamma zeroed): its tap and cursor detachment are active.
var ids = [CGDirectDisplayID](repeating: 0, count: 16); var n: UInt32 = 0
CGGetOnlineDisplayList(16, &ids, &n)
for d in ids.prefix(Int(n)) where CGDisplayIsBuiltin(d) != 0 {
    var r = [CGGammaValue](repeating: 0, count: 256), g = r, b = r; var cnt: UInt32 = 0
    CGGetDisplayTransferByTable(d, 256, &r, &g, &b, &cnt)
    if (r.prefix(Int(cnt)).max() ?? 1) < 0.01 { print("ABORT: built-in display gamma is zero (control mode on?)"); exit(2) }
}

let child = Process()
child.executableURL = URL(fileURLWithPath: CommandLine.arguments[0])
child.arguments = ["window"]
let pipe = Pipe()
child.standardOutput = pipe
try! child.run()
var line = ""
while !line.contains("\n") { line += String(decoding: pipe.fileHandleForReading.availableData, as: UTF8.self) }
let v = line.split(separator: " ").dropFirst().compactMap { Double($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
let content = CGRect(x: v[0], y: v[1], width: v[2], height: v[3])
print("probe window content (CG global): \(content)")

func describe(_ c: NSCursor?) -> String {
    guard let c else { return "nil" }
    let same = { (o: NSCursor) in o.hotSpot == c.hotSpot && o.image.size == c.image.size && o.image.tiffRepresentation == c.image.tiffRepresentation }
    let name = same(.iBeam) ? "iBeam" : same(.pointingHand) ? "pointingHand" : same(.arrow) ? "arrow" : "other"
    return "\(name) \(Int(c.image.size.width))x\(Int(c.image.size.height))pt hot \(c.hotSpot)"
}
func move(_ p: CGPoint) {
    let e = CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: p, mouseButton: .left)!
    e.setIntegerValueField(.eventSourceUserData, value: tag)
    e.post(tap: .cghidEventTap)
}
func settle() { RunLoop.current.run(until: Date().addingTimeInterval(0.4)) }

let original = CGEvent(source: nil)!.location
let textPt = CGPoint(x: content.minX + 60, y: content.midY), handPt = CGPoint(x: content.minX + 300, y: content.midY)
var results: [String] = []
for detached in [false, true] {
    if detached { CGAssociateMouseAndMouseCursorPosition(0) }
    move(textPt); settle()
    let loc1 = CGEvent(source: nil)!.location
    let overText = describe(NSCursor.currentSystem)
    move(handPt); settle()
    let overHand = describe(NSCursor.currentSystem)
    let loc2 = CGEvent(source: nil)!.location
    if detached { CGAssociateMouseAndMouseCursorPosition(1) }
    results.append("detached=\(detached): over text -> \(overText) (cursor at \(loc1)); over hand area -> \(overHand) (cursor at \(loc2))")
}
move(original)
child.terminate()
results.forEach { print($0) }
print("cursor restored to \(original)")
