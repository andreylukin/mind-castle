import AppKit
import ApplicationServices

/// Current global bounds (points, top-left origin) of a window from the window server.
func windowBounds(_ id: UInt32) -> CGRect? {
    guard let info = CGWindowListCopyWindowInfo(.optionIncludingWindow, CGWindowID(id)) as? [[String: Any]],
          let dict = info.first?[kCGWindowBounds as String] as? NSDictionary else { return nil }
    return CGRect(dictionaryRepresentation: dict as CFDictionary)
}

private func axAttr<T>(_ el: AXUIElement, _ name: String) -> T? {
    var v: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, name as CFString, &v) == .success else { return nil }
    return v as? T
}

func axFrame(_ el: AXUIElement) -> CGRect? {
    guard let p: AXValue = axAttr(el, kAXPositionAttribute), let s: AXValue = axAttr(el, kAXSizeAttribute) else { return nil }
    var pt = CGPoint.zero, sz = CGSize.zero
    AXValueGetValue(p, .cgPoint, &pt)
    AXValueGetValue(s, .cgSize, &sz)
    return CGRect(origin: pt, size: sz)
}

/// The AX window matching CGWindow `id` (same frame scores 2, same title 1), or nil.
func axWindow(id: UInt32, pid: pid_t, title: String) -> AXUIElement? {
    let app = AXUIElementCreateApplication(pid)
    guard let wins: [AXUIElement] = axAttr(app, kAXWindowsAttribute) else {
        log("window \(id): no AX windows for pid \(pid) (Accessibility granted? \(AXIsProcessTrusted()))")
        return nil
    }
    let bounds = windowBounds(id)
    var best: (AXUIElement, Int)?
    for w in wins {
        var score = 0
        if let b = bounds, let f = axFrame(w),
           abs(f.minX - b.minX) <= 2, abs(f.minY - b.minY) <= 2, abs(f.width - b.width) <= 2, abs(f.height - b.height) <= 2 { score += 2 }
        if let t: String = axAttr(w, kAXTitleAttribute), t == title { score += 1 }
        if score > 0, score > (best?.1 ?? 0) { best = (w, score) }
    }
    if best == nil { log("window \(id): no matching AX window") }
    return best?.0
}

/// Activate the owning app and raise this specific window via Accessibility.
func focusWindow(id: UInt32, pid: pid_t, title: String) {
    NSRunningApplication(processIdentifier: pid)?.activate()
    AXUIElementSetAttributeValue(AXUIElementCreateApplication(pid), kAXFrontmostAttribute as CFString, kCFBooleanTrue)
    guard let win = axWindow(id: id, pid: pid, title: title) else { return }
    AXUIElementSetAttributeValue(win, kAXMainAttribute as CFString, kCFBooleanTrue)
    AXUIElementPerformAction(win, kAXRaiseAction as CFString)
}

private func globalPoint(_ id: UInt32, _ x: Double, _ y: Double) -> CGPoint? {
    guard let b = windowBounds(id) else { return nil }
    let cx = min(max(x, 0), 1), cy = min(max(y, 0), 1)
    return CGPoint(x: b.minX + cx * b.width, y: b.minY + cy * b.height)
}

func click(id: UInt32, x: Double, y: Double) {
    guard let pt = globalPoint(id, x, y) else { return }
    for type in [CGEventType.leftMouseDown, .leftMouseUp] {
        postTagged(CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: pt, mouseButton: .left))
    }
}

func scroll(id: UInt32, x: Double, y: Double, dx: Int32, dy: Int32) {
    guard let pt = globalPoint(id, x, y) else { return }
    postTagged(CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: pt, mouseButton: .left))
    let ev = CGEvent(scrollWheelEvent2Source: nil, units: .line, wheelCount: 2, wheel1: dy, wheel2: dx, wheel3: 0)
    ev?.location = pt
    postTagged(ev)
}

/// Click counting like a real mouse: a down on the same button within the system double-click interval and
/// a few points of the previous down continues the sequence (2 = double, 3 = triple).
struct ClickCounter {
    static let slop = 4.0 // points
    private var last: (t: Double, pt: CGPoint, button: Int, count: Int64)?

    mutating func down(at pt: CGPoint, button: Int, t: Double, interval: Double = NSEvent.doubleClickInterval) -> Int64 {
        var n: Int64 = 1
        if let l = last, l.button == button, t - l.t <= interval, hypot(pt.x - l.pt.x, pt.y - l.pt.y) <= Self.slop {
            n = l.count + 1
        }
        last = (t, pt, button, n)
        return n
    }

    var current: Int64 { last?.count ?? 1 }
}

private var clicks = ClickCounter()
private var lastInjected: CGPoint?
private var heldButton: Int? // button the headset has down, released on disconnect

/// MOUSE (17), on inputQ. `t` is the receive time (s, uptime) so focus delays don't skew click counting.
/// Moves are posted as mouseMoved at the target point, which also moves the (hidden) cursor for hover/tooltips.
func mouse(id: UInt32, pid: pid_t, title: String, kind: String, x: Double, y: Double, button: Int, t: Double) {
    guard let pt = globalPoint(id, x, y) else { return }
    if kind == "down" {
        let wasFront = NSWorkspace.shared.frontmostApplication?.processIdentifier == pid
        focusWindow(id: id, pid: pid, title: title)
        if !wasFront { usleep(50_000) } // let the activation land before injecting
    }
    postTagged(mouseEvent(kind: kind, at: pt, button: button, t: t))
}

/// Builds the tagged CGEvent for a MOUSE message and updates click/held-button state. Doesn't post (self-test uses it).
func mouseEvent(kind: String, at pt: CGPoint, button: Int, t: Double) -> CGEvent? {
    let right = button == 1
    let (type, cgButton): (CGEventType, CGMouseButton)
    switch kind {
    case "move": (type, cgButton) = (.mouseMoved, .left)
    case "drag": (type, cgButton) = right ? (.rightMouseDragged, .right) : (.leftMouseDragged, .left)
    case "down": (type, cgButton) = right ? (.rightMouseDown, .right) : (.leftMouseDown, .left)
    case "up": (type, cgButton) = right ? (.rightMouseUp, .right) : (.leftMouseUp, .left)
    default:
        log("mouse: unknown kind \(kind)")
        return nil
    }
    guard let ev = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: pt, mouseButton: cgButton) else { return nil }
    if let l = lastInjected {
        ev.setDoubleValueField(.mouseEventDeltaX, value: pt.x - l.x)
        ev.setDoubleValueField(.mouseEventDeltaY, value: pt.y - l.y)
    }
    switch kind {
    case "down":
        ev.setIntegerValueField(.mouseEventClickState, value: clicks.down(at: pt, button: button, t: t))
        heldButton = button
    case "up", "drag":
        ev.setIntegerValueField(.mouseEventClickState, value: clicks.current)
        if kind == "up" { heldButton = nil }
    default: break
    }
    lastInjected = pt
    tagInjected(ev)
    return ev
}

/// Releases a button left down by the headset (disconnect mid-drag), so Mac apps don't see a stuck drag. On inputQ.
func releaseInjectedButtons() {
    guard let b = heldButton, let pt = lastInjected else { return }
    heldButton = nil
    let ev = CGEvent(mouseEventSource: nil, mouseType: b == 1 ? .rightMouseUp : .leftMouseUp, mouseCursorPosition: pt,
                     mouseButton: b == 1 ? .right : .left)
    ev?.setIntegerValueField(.mouseEventClickState, value: clicks.current)
    postTagged(ev)
    log("mouse: released stuck \(b == 1 ? "right" : "left") button")
}

/// Marks an event as injected by the streamer so the control-mode tap lets it through.
func tagInjected(_ ev: CGEvent) {
    ev.setIntegerValueField(.eventSourceUserData, value: castleEventTag)
}

private var lastPosted: CGPoint?

private func postTagged(_ ev: CGEvent?) {
    guard let ev else { return }
    tagInjected(ev)
    // Posting a located mouse event warps the (detached) cursor; a big jump can leak into the next physical delta.
    if ev.type != .keyDown && ev.type != .keyUp {
        let p = ev.location
        if let l = lastPosted { control.noteInjectedMove(from: l, to: p) }
        lastPosted = p
    }
    ev.post(tap: .cghidEventTap)
}

/// Set AX position and size; position first so the size is constrained by the destination display.
func axSetFrame(_ el: AXUIElement, _ f: CGRect) {
    var pt = f.origin, sz = f.size
    if let v = AXValueCreate(.cgPoint, &pt) { AXUIElementSetAttributeValue(el, kAXPositionAttribute as CFString, v) }
    if let v = AXValueCreate(.cgSize, &sz) { AXUIElementSetAttributeValue(el, kAXSizeAttribute as CFString, v) }
}
