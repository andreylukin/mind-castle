import AVFoundation
import AppKit
import ScreenCaptureKit
import SystemConfiguration

extension Msg {
    static let hello: UInt8 = 22
}

/// Per-Mac identity and setup checks. State lives in $MIND_CASTLE_HOME (default ~/.mind-castle).
enum HostInfo {
    static let home = URL(fileURLWithPath: ProcessInfo.processInfo.environment["MIND_CASTLE_HOME"]
        ?? NSHomeDirectory() + "/.mind-castle")

    /// HELLO (22) payload: {"host": ComputerName, "id": stable per-Mac UUID, "version": git short sha}.
    static let hello: Data = {
        let obj: [String: Any] = ["host": computerName, "id": stableID, "version": version]
        return (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data()
    }()

    static var computerName: String {
        (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? Host.current().localizedName ?? "Mac"
    }

    /// Created once; survives rebuilds and re-clones (it's outside the repo).
    static var stableID: String {
        let f = home.appendingPathComponent("id")
        if let s = try? String(contentsOf: f, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty {
            return s
        }
        let id = UUID().uuidString
        try? FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
        try? (id + "\n").write(to: f, atomically: true, encoding: .utf8)
        return id
    }

    /// Written next to the binary by mac/build.sh (`git rev-parse --short HEAD`, "-dirty" if modified).
    static var version: String {
        let exe = URL(fileURLWithPath: Bundle.main.executablePath ?? CommandLine.arguments[0]).resolvingSymlinksInPath()
        let f = exe.deletingLastPathComponent().appendingPathComponent("castle-version")
        return (try? String(contentsOf: f, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)) ?? "unknown"
    }

    /// `--check-permissions` / `--request-permissions` (used by setup.sh): prints key=value lines and exits 0 if
    /// everything needed is granted. Run from the terminal that will run `castle`: macOS attributes these to it.
    /// Screen capture is tested for real (a window listing, no capture), since the preflight check can say
    /// "granted" while ScreenCaptureKit is denied.
    static func permissionsReport(request: Bool) -> Int32 {
        let screenPre = CGPreflightScreenCaptureAccess()
        var screen = "timeout"
        let done = DispatchSemaphore(value: 0)
        SCShareableContent.getExcludingDesktopWindows(true, onScreenWindowsOnly: true) { c, e in
            screen = c != nil ? "ok" : "denied (\(e?.localizedDescription ?? "?"))"
            done.signal()
        }
        _ = done.wait(timeout: .now() + 5)
        let ax = AXIsProcessTrusted()
        var mic: String {
            switch AVCaptureDevice.authorizationStatus(for: .audio) {
            case .authorized: return "granted"
            case .denied: return "denied"
            case .restricted: return "restricted"
            case .notDetermined: return "undetermined"
            @unknown default: return "unknown"
            }
        }
        if request {
            // Each request registers the terminal in the matching System Settings list (and may show a prompt).
            if screen != "ok" { CGRequestScreenCaptureAccess() }
            if !ax { AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue(): true] as CFDictionary) }
            if mic == "undetermined" {
                let m = DispatchSemaphore(value: 0)
                AVCaptureDevice.requestAccess(for: .audio) { _ in m.signal() }
                _ = m.wait(timeout: .now() + 60)
            }
        }
        print("screen_preflight=\(screenPre ? "granted" : "missing")")
        print("screen_capture=\(screen)")
        print("accessibility=\(ax ? "granted" : "missing")")
        print("microphone=\(mic)")
        print("host=\(computerName)")
        print("version=\(version)")
        return screen == "ok" && ax ? 0 : 1
    }
}
