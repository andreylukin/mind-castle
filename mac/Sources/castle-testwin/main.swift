import AppKit

// Lab test windows for `castle-streamer --lab --only-app castle-testwin`: "Lab Editor", "Lab Browser", "Lab Terminal".
// Accessory app at a window level just above the desktop icons: never above normal windows, never takes focus.
// It keeps drawing while occluded (App Nap off), so streams of these windows always have changing content.

let mono = NSFont.monospacedSystemFont(ofSize: 15, weight: .regular)

final class EditorView: NSView {
    var lines: [String] = (1...48).map { i in
        ["func render(frame: Frame) -> Image {", "    let w = frame.width * scale", "    // composite layers back to front",
         "    for layer in layers.reversed() {", "        canvas.draw(layer, at: origin)", "    }", "    return canvas.snapshot()", "}", ""][i % 9]
    }
    var caretOn = true
    override var isFlipped: Bool { true }

    override func draw(_ r: NSRect) {
        NSColor(red: 1.0, green: 0.97, blue: 0.86, alpha: 1).setFill() // cream
        bounds.fill()
        NSColor(red: 0.9, green: 0.85, blue: 0.7, alpha: 1).setFill() // gutter
        NSRect(x: 0, y: 0, width: 56, height: bounds.height).fill()
        let lh = 20.0, visible = Int(bounds.height / lh) - 1
        let start = max(0, lines.count - visible)
        let num: [NSAttributedString.Key: Any] = [.font: mono, .foregroundColor: NSColor(white: 0.5, alpha: 1)]
        let code: [NSAttributedString.Key: Any] = [.font: mono, .foregroundColor: NSColor(red: 0.15, green: 0.1, blue: 0.3, alpha: 1)]
        var y = 8.0
        for i in start..<lines.count {
            NSAttributedString(string: String(format: "%3d", i + 1), attributes: num).draw(at: NSPoint(x: 10, y: y))
            NSAttributedString(string: lines[i], attributes: code).draw(at: NSPoint(x: 66, y: y))
            y += lh
        }
        if caretOn {
            let last = NSAttributedString(string: lines.last ?? "", attributes: code).size().width
            NSColor.systemRed.setFill()
            NSRect(x: 66 + last + 2, y: y - lh, width: 2, height: lh - 2).fill()
        }
    }
}

final class TerminalView: NSView {
    override var isFlipped: Bool { true }
    let text = [
        "user@castle ~/repos/mind-castle % ls", "PROTOCOL.md  headset  mac", "user@castle ~/repos/mind-castle % swift build -c release",
        "Building for production...", "Build complete! (5.28s)", "user@castle ~/repos/mind-castle % git status",
        "On branch main", "nothing to commit, working tree clean", "user@castle ~/repos/mind-castle % ",
    ]
    override func draw(_ r: NSRect) {
        NSColor(red: 0.05, green: 0.07, blue: 0.1, alpha: 1).setFill() // near-black
        bounds.fill()
        let attrs: [NSAttributedString.Key: Any] = [.font: mono, .foregroundColor: NSColor(red: 0.3, green: 1, blue: 0.45, alpha: 1)]
        for (i, l) in text.enumerated() { NSAttributedString(string: l, attributes: attrs).draw(at: NSPoint(x: 14, y: 12 + Double(i) * 22)) }
    }
}

func browserView() -> NSView {
    let v = NSView()
    v.wantsLayer = true
    v.layer?.backgroundColor = NSColor(red: 0.88, green: 0.94, blue: 1.0, alpha: 1).cgColor // pale blue
    let stack = NSStackView()
    stack.orientation = .vertical
    stack.alignment = .leading
    stack.spacing = 14
    stack.translatesAutoresizingMaskIntoConstraints = false
    let title = NSTextField(labelWithString: "Mind Castle Lab Browser")
    title.font = .boldSystemFont(ofSize: 30)
    stack.addArrangedSubview(title)
    for (i, link) in ["Open the window picker", "Read PROTOCOL.md", "Latency dashboard", "Settings"].enumerated() {
        let b = NSButton(title: link, target: nil, action: nil)
        b.isBordered = false
        b.attributedTitle = NSAttributedString(string: "→ \(link)", attributes: [
            .font: NSFont.systemFont(ofSize: 24, weight: .semibold), .foregroundColor: NSColor.systemBlue,
            .underlineStyle: NSUnderlineStyle.single.rawValue])
        stack.addArrangedSubview(b)
        let p = NSTextField(wrappingLabelWithString: "Paragraph \(i + 1). The quick brown fox jumps over the lazy dog while the headset "
            + "streams this window at native resolution. Text should stay sharp at reading distance.")
        p.font = .systemFont(ofSize: 17)
        p.preferredMaxLayoutWidth = 700
        stack.addArrangedSubview(p)
    }
    v.addSubview(stack)
    NSLayoutConstraint.activate([
        stack.leadingAnchor.constraint(equalTo: v.leadingAnchor, constant: 30),
        stack.trailingAnchor.constraint(lessThanOrEqualTo: v.trailingAnchor, constant: -30),
        stack.topAnchor.constraint(equalTo: v.topAnchor, constant: 24),
    ])
    return v
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .idleSystemSleepDisabled], reason: "lab test windows")

var windows: [NSWindow] = []
func makeWindow(_ title: String, _ frame: NSRect, _ content: NSView) {
    let w = NSWindow(contentRect: frame, styleMask: [.titled, .resizable, .miniaturizable], backing: .buffered, defer: false)
    w.title = title
    w.contentView = content
    w.isReleasedWhenClosed = false
    // Below every normal window (even if clicked), still on screen for ScreenCaptureKit.
    w.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.desktopIconWindow)) + 1)
    w.orderFrontRegardless()
    windows.append(w)
}

let editor = EditorView()
makeWindow("Lab Editor", NSRect(x: 80, y: 120, width: 900, height: 700), editor)
makeWindow("Lab Browser", NSRect(x: 240, y: 80, width: 800, height: 640), browserView())
makeWindow("Lab Terminal", NSRect(x: 400, y: 60, width: 760, height: 440), TerminalView())

let fmt = DateFormatter()
fmt.dateFormat = "HH:mm:ss"
Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { _ in
    editor.caretOn.toggle()
    editor.needsDisplay = true
}
Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { _ in
    editor.lines.append("// \(fmt.string(from: Date())) tick \(editor.lines.count + 1)")
    editor.needsDisplay = true
}
print("castle-testwin: 3 windows up (pid \(getpid()))")
app.run()
_ = activity
