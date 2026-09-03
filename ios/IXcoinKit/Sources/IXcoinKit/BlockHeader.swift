import Foundation

/// An 80-byte block header.
public struct BlockHeader: Equatable {
    public static let size = 80

    public let version: UInt32
    public let prevBlock: Data      // 32 bytes, wire order
    public let merkleRoot: Data     // 32 bytes, wire order
    public let time: UInt32
    public let bits: UInt32
    public let nonce: UInt32
    public let raw: Data            // exactly the 80 bytes this was parsed from

    public init(raw: Data) throws {
        guard raw.count >= BlockHeader.size else {
            throw ByteReader.Error.truncated(needed: BlockHeader.size, available: raw.count)
        }
        let bytes = raw.prefix(BlockHeader.size)
        var r = ByteReader(Data(bytes))
        version = try r.readUInt32LE()
        prevBlock = try r.read(32)
        merkleRoot = try r.read(32)
        time = try r.readUInt32LE()
        bits = try r.readUInt32LE()
        nonce = try r.readUInt32LE()
        self.raw = Data(bytes)
    }

    /// The header's own hash. For a merged-mined block this is *expected* to be
    /// above the target — the work was done on the parent chain.
    public var hash: Data { Crypto.sha256d(raw) }
    public var hashHex: String { hash.reversedHex }

    /// True when the version marks this block as merged-mined.
    public var hasAuxPoW: Bool { (version & AuxPoW.versionAuxPoWBit) != 0 }
    /// Chain id, encoded in the top 16 bits of the version field.
    public var chainId: Int { Int((version >> AuxPoW.versionChainIdShift) & 0xffff) }

    /// Expand the compact `bits` encoding into a full 256-bit target.
    public var target: Data {
        let exponent = Int(bits >> 24)
        let mantissa = bits & 0x007f_ffff
        var out = Data(repeating: 0, count: 32)
        // The mantissa's most significant byte sits at position `exponent`
        // counting from the low end of the big-endian value.
        guard exponent >= 3, exponent <= 32 else { return out }
        let idx = 32 - exponent
        let m = [UInt8((mantissa >> 16) & 0xff), UInt8((mantissa >> 8) & 0xff), UInt8(mantissa & 0xff)]
        for (i, b) in m.enumerated() where idx + i < 32 { out[idx + i] = b }
        return out
    }

    /// Does `candidate` (a hash in wire order) meet this header's target?
    public func meetsTarget(_ candidate: Data) -> Bool {
        // Hashes are little-endian on the wire; compare big-endian.
        let be = Data(candidate.reversed())
        let t = target
        for i in 0 ..< 32 {
            if be[i] < t[i] { return true }
            if be[i] > t[i] { return false }
        }
        return true
    }
}
