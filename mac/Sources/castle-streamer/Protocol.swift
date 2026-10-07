import Foundation

// Message types from PROTOCOL.md.
enum Msg {
    static let windowList: UInt8 = 1
    static let codecConfig: UInt8 = 2
    static let frame: UInt8 = 3
    static let keyframe: UInt8 = 4
    static let windowGone: UInt8 = 5
    static let pong: UInt8 = 8

    static let subscribe: UInt8 = 10
    static let focus: UInt8 = 11
    static let click: UInt8 = 12
    static let scroll: UInt8 = 13
    static let requestKeyframe: UInt8 = 14
    static let ping: UInt8 = 15
    static let frameReport: UInt8 = 16
}

extension Data {
    mutating func appendBE<T: FixedWidthInteger>(_ v: T) {
        var be = v.bigEndian
        Swift.withUnsafeBytes(of: &be) { append(contentsOf: $0) }
    }
}

func log(_ s: String) {
    let ts = ISO8601DateFormatter.string(from: Date(), timeZone: .current, formatOptions: [.withTime, .withColonSeparatorInTime])
    print("[\(ts)] \(s)")
    fflush(stdout)
}
