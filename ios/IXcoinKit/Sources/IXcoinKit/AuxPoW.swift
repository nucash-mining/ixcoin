import Foundation

/// A merged-mining proof.
///
/// Wire layout, immediately after the 80-byte iXcoin header:
///   parent coinbase transaction (legacy serialisation, never segwit)
///   parent block hash                              32 bytes
///   coinbase merkle branch      varint count + n * 32 bytes + 4-byte index
///   chain merkle branch         varint count + n * 32 bytes + 4-byte index
///   parent block header                            80 bytes
public struct AuxPoW {
    public static let versionAuxPoWBit: UInt32 = 0x100
    public static let versionChainIdShift: UInt32 = 16
    /// A branch longer than this cannot occur in a real proof; refuse it rather
    /// than allocate on a hostile length.
    public static let maxBranchLength = 30

    public enum Error: Swift.Error, Equatable {
        case branchTooLong(UInt64)
        case malformed(String)
    }

    public let coinbase: Data
    public let parentBlockHash: Data
    public let coinbaseBranch: [Data]
    public let coinbaseIndex: UInt32
    public let chainBranch: [Data]
    public let chainIndex: UInt32
    public let parentHeader: BlockHeader
    /// The exact bytes this proof occupies, so a block can be re-serialised
    /// byte-for-byte after parsing.
    public let raw: Data

    public var byteCount: Int { raw.count }

    public static func hasAuxPoW(version: UInt32) -> Bool { version & versionAuxPoWBit != 0 }
    public static func chainId(version: UInt32) -> Int { Int((version >> versionChainIdShift) & 0xffff) }

    public init(payload: Data, offset: Int) throws {
        let start = offset
        var r = ByteReader(payload, offset: offset)

        let coinbaseLength = try AuxPoW.measureLegacyTransaction(payload, offset: r.offset)
        coinbase = try r.read(coinbaseLength)

        parentBlockHash = try r.read(32)
        (coinbaseBranch, coinbaseIndex) = try AuxPoW.readBranch(&r)
        (chainBranch, chainIndex) = try AuxPoW.readBranch(&r)
        parentHeader = try BlockHeader(raw: try r.read(BlockHeader.size))

        let end = r.offset
        raw = payload.subdata(in: (payload.startIndex + start) ..< (payload.startIndex + end))
    }

    private static func readBranch(_ r: inout ByteReader) throws -> ([Data], UInt32) {
        let count = try r.readVarInt()
        guard count <= UInt64(maxBranchLength) else { throw Error.branchTooLong(count) }
        var hashes: [Data] = []
        hashes.reserveCapacity(Int(count))
        for _ in 0 ..< count { hashes.append(try r.read(32)) }
        return (hashes, try r.readUInt32LE())
    }

    /// Measure a legacy (non-segwit) transaction without building it.
    ///
    /// The parent coinbase is always serialised legacy here, so a segwit-aware
    /// parser would misread the marker byte as an input count of zero and slide
    /// the cursor into the middle of the proof — which shows up much later as a
    /// nonsense transaction count and a dropped peer.
    static func measureLegacyTransaction(_ payload: Data, offset: Int) throws -> Int {
        var r = ByteReader(payload, offset: offset)
        _ = try r.readInt32LE()                       // version

        let inputs = try r.readVarInt()
        guard inputs < 100_000 else { throw Error.malformed("absurd input count \(inputs)") }
        for _ in 0 ..< inputs {
            _ = try r.read(36)                        // outpoint
            _ = try r.readVarBytes()                  // scriptSig
            _ = try r.readUInt32LE()                  // sequence
        }

        let outputs = try r.readVarInt()
        guard outputs < 100_000 else { throw Error.malformed("absurd output count \(outputs)") }
        for _ in 0 ..< outputs {
            _ = try r.readUInt64LE()                  // value
            _ = try r.readVarBytes()                  // scriptPubKey
        }

        _ = try r.readUInt32LE()                      // lockTime
        return r.offset - offset
    }

    /// Fold a merkle branch upward from `leaf` at `index`.
    public static func applyBranch(leaf: Data, branch: [Data], index: UInt32) -> Data {
        var hash = leaf
        var i = index
        for step in branch {
            if i & 1 == 0 {
                hash = Crypto.sha256d(hash + step)
            } else {
                hash = Crypto.sha256d(step + hash)
            }
            i >>= 1
        }
        return hash
    }

    /// Does the parent block's work actually cover this chain?
    ///
    /// The coinbase must hash up to the parent's merkle root, and the parent
    /// header must itself meet the child's target.
    public func isValid(forTarget childHeader: BlockHeader) -> Bool {
        let coinbaseTxId = Crypto.sha256d(coinbase)
        let root = AuxPoW.applyBranch(leaf: coinbaseTxId, branch: coinbaseBranch, index: coinbaseIndex)
        guard root == parentHeader.merkleRoot else { return false }
        return childHeader.meetsTarget(parentHeader.hash)
    }
}
