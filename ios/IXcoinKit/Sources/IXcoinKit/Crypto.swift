import Foundation
import CryptoKit

public enum Crypto {
    /// Bitcoin's double SHA-256.
    public static func sha256d(_ data: Data) -> Data {
        Data(SHA256.hash(data: Data(SHA256.hash(data: data))))
    }
}

public extension Data {
    /// Hex as displayed by block explorers, i.e. byte-reversed for hashes.
    var reversedHex: String { reversed().map { String(format: "%02x", $0) }.joined() }
    var hex: String { map { String(format: "%02x", $0) }.joined() }

    init?(hex: String) {
        let chars = Array(hex)
        guard chars.count % 2 == 0 else { return nil }
        var out = Data(capacity: chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let b = UInt8(String(chars[i...i+1]), radix: 16) else { return nil }
            out.append(b)
            i += 2
        }
        self = out
    }
}

/// Sequential reader over a wire message, with bounds checking on every read.
///
/// A malformed or hostile peer message must fail cleanly rather than crash or
/// read past the buffer, so every accessor is throwing.
public struct ByteReader {
    public enum Error: Swift.Error, Equatable {
        case truncated(needed: Int, available: Int)
        case varIntTooLarge(UInt64)
    }

    public let data: Data
    public private(set) var offset: Int

    public init(_ data: Data, offset: Int = 0) {
        self.data = data
        self.offset = offset
    }

    public var remaining: Int { data.count - offset }

    public mutating func read(_ count: Int) throws -> Data {
        guard count >= 0, remaining >= count else {
            throw Error.truncated(needed: count, available: remaining)
        }
        let start = data.startIndex + offset
        defer { offset += count }
        return data[start ..< start + count]
    }

    public mutating func readUInt32LE() throws -> UInt32 {
        let d = try read(4)
        return d.reduce(UInt32(0)) { acc, b in (acc >> 8) | (UInt32(b) << 24) }
    }

    public mutating func readInt32LE() throws -> Int32 { Int32(bitPattern: try readUInt32LE()) }

    public mutating func readUInt64LE() throws -> UInt64 {
        let d = try read(8)
        var v: UInt64 = 0
        for (i, b) in d.enumerated() { v |= UInt64(b) << (8 * UInt64(i)) }
        return v
    }

    /// Bitcoin's compact size integer.
    public mutating func readVarInt() throws -> UInt64 {
        let first = try read(1)[data.startIndex + offset - 1]
        switch first {
        case 0xfd:
            let d = try read(2)
            return d.enumerated().reduce(UInt64(0)) { $0 | (UInt64($1.element) << (8 * UInt64($1.offset))) }
        case 0xfe: return UInt64(try readUInt32LE())
        case 0xff: return try readUInt64LE()
        default:   return UInt64(first)
        }
    }

    /// A length-prefixed byte string, with a ceiling so a bogus length cannot
    /// be used to make us allocate wildly.
    public mutating func readVarBytes(max: Int = 4_000_000) throws -> Data {
        let n = try readVarInt()
        guard n <= UInt64(max) else { throw Error.varIntTooLarge(n) }
        return try read(Int(n))
    }
}
