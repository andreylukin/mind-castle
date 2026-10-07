// swift-tools-version:5.10
// Tools 5.10 builds in Swift 5 language mode (no strict concurrency).
import PackageDescription

let package = Package(
    name: "castle-streamer",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "castle-streamer", dependencies: ["CGVirtualDisplayShim"]),
        .target(name: "CGVirtualDisplayShim", linkerSettings: [.linkedFramework("CoreGraphics")]),
        // Standalone check of the private virtual-display API: create, list, destroy. Moves no windows.
        .executableTarget(name: "vd-probe", dependencies: ["CGVirtualDisplayShim"]),
        // Checks that posted mouse moves update another app's cursor as read by NSCursor.currentSystem.
        .executableTarget(name: "cursor-probe"),
        // Lab test windows for `castle-streamer --lab --only-app castle-testwin`.
        .executableTarget(name: "castle-testwin"),
    ]
)
