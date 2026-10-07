import AppKit
import ApplicationServices

extension Msg {
    static let focusChanged: UInt8 = 23
    static let overlay: UInt8 = 24
}

/// AX element -> CGWindowID via the private `_AXUIElementGetWindow` (what window managers use), looked up at runtime.
private let axGetWindow: (@convention(c) (AXUIElement, UnsafeMutablePointer<CGWindowID>) -> AXError)? = {
    guard let sym = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "_AXUIElementGetWindow") else { return nil } // RTLD_DEFAULT
    return unsafeBitCast(sym, to: (@convention(c) (AXUIElement, UnsafeMutablePointer<CGWindowID>) -> AXError).self)
}()

/// v7 FOCUS_CHANGED: reports the frontmost Mac window whenever it changes (Raycast jump, ⌘-Tab, click), except
/// changes the headset caused itself (`noteOwnActivation`). Main thread: workspace activation + AXObserver on the
/// frontmost app (focused/main window changed), with a 0.5 s poll as a fallback.
final class FocusWatcher {
    var send: (Data) -> Void = { _ in }
    var isOverlayApp: (String) -> Bool = { _ in false }
    static let echoWindow = 0.3

    private var observer: AXObserver?
    private var observedPid: pid_t = 0
    private let lock = NSLock()
    private var lastID: CGWindowID = 0
    private var suppressUntil = 0.0

    func start() {
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didActivateApplicationNotification,
                                                          object: nil, queue: .main) { [weak self] _ in self?.check() }
        let t = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in self?.check() }
        RunLoop.main.add(t, forMode: .common)
        DispatchQueue.main.async { self.check() }
        log("focus: following the frontmost Mac window (FOCUS_CHANGED)")
    }

    /// Our own FOCUS/CLICK/MOUSE-down activated `id`: don't echo it (or anything else for ~300 ms).
    func noteOwnActivation(_ id: CGWindowID) {
        lock.lock()
        lastID = id
        suppressUntil = ProcessInfo.processInfo.systemUptime + Self.echoWindow
        lock.unlock()
    }

    private func check() {
        guard let app = NSWorkspace.shared.frontmostApplication, app.processIdentifier != getpid() else { return }
        let pid = app.processIdentifier
        if pid != observedPid { observe(pid) }
        let name = app.localizedName ?? ""
        guard !isOverlayApp(name) else { return } // launchers are reported as OVERLAY instead
        let axApp = AXUIElementCreateApplication(pid)
        var v: CFTypeRef?
        guard AXUIElementCopyAttributeValue(axApp, kAXFocusedWindowAttribute as CFString, &v) == .success, let v,
              CFGetTypeID(v) == AXUIElementGetTypeID() else { return }
        let win = v as! AXUIElement
        var id: CGWindowID = 0
        guard let axGetWindow, axGetWindow(win, &id) == .success, id != 0 else { return }
        lock.lock()
        let changed = id != lastID
        lastID = id
        let echo = ProcessInfo.processInfo.systemUptime < suppressUntil
        lock.unlock()
        guard changed, !echo else { return }
        var t: CFTypeRef?
        AXUIElementCopyAttributeValue(win, kAXTitleAttribute as CFString, &t)
        let obj: [String: Any] = ["id": id, "app": name, "title": (t as? String) ?? ""]
        log("focus: window \(id) (\(name))")
        send((try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data())
    }

    private func observe(_ pid: pid_t) {
        if let observer {
            CFRunLoopRemoveSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(observer), .defaultMode)
        }
        observer = nil
        observedPid = pid
        let cb: AXObserverCallback = { _, _, _, refcon in
            Unmanaged<FocusWatcher>.fromOpaque(refcon!).takeUnretainedValue().check()
        }
        var obs: AXObserver?
        guard AXObserverCreate(pid, cb, &obs) == .success, let obs else { return }
        let el = AXUIElementCreateApplication(pid), me = Unmanaged.passUnretained(self).toOpaque()
        for n in [kAXFocusedWindowChangedNotification, kAXMainWindowChangedNotification] {
            AXObserverAddNotification(obs, el, n as CFString, me)
        }
        CFRunLoopAddSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(obs), .defaultMode)
        observer = obs
    }
}

/// v7 OVERLAY: launcher windows (Raycast, Spotlight, Alfred, ...) appear on non-zero window layers and only for a
/// moment, so they're found by polling the on-screen window list at 20 Hz. Reports the full current set each poll;
/// Castle diffs it, streams new ones without a SUBSCRIBE and stops gone ones.
/// Apps: ~/.config/mind-castle/overlays.json, `{"apps":["Raycast","Spotlight","Alfred"]}` or a plain array.
final class OverlayWatcher {
    struct Seen { let app: String; let bounds: CGRect }
    static let defaultApps = ["Raycast", "Spotlight", "Alfred"]
    let apps: Set<String>
    var report: ([CGWindowID: Seen]) -> Void = { _ in }

    init() {
        let url = URL(fileURLWithPath: NSHomeDirectory() + "/.config/mind-castle/overlays.json")
        var list = Self.defaultApps
        if let data = try? Data(contentsOf: url), let obj = try? JSONSerialization.jsonObject(with: data) {
            if let a = obj as? [String] { list = a } else if let a = (obj as? [String: Any])?["apps"] as? [String] { list = a }
            log("overlay: apps from \(url.lastPathComponent): \(list)")
        }
        apps = Set(list.map { $0.lowercased() })
    }

    func isOverlayApp(_ name: String) -> Bool { apps.contains(name.lowercased()) }

    func start() {
        let t = Timer(timeInterval: 0.05, repeats: true) { [weak self] _ in self?.poll() }
        RunLoop.main.add(t, forMode: .common)
        log("overlay: watching \(apps.sorted()) (OVERLAY)")
    }

    private func poll() {
        guard let info = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID)
            as? [[String: Any]] else { return }
        var now: [CGWindowID: Seen] = [:]
        for w in info {
            guard let owner = w[kCGWindowOwnerName as String] as? String, isOverlayApp(owner),
                  (w[kCGWindowLayer as String] as? Int ?? 0) != 0,
                  (w[kCGWindowAlpha as String] as? Double ?? 1) > 0,
                  let id = w[kCGWindowNumber as String] as? CGWindowID,
                  let bd = w[kCGWindowBounds as String] as? NSDictionary,
                  let b = CGRect(dictionaryRepresentation: bd), b.width >= 100, b.height >= 40 else { continue }
            now[id] = Seen(app: owner, bounds: b)
        }
        report(now)
    }
}
