import XCTest
@testable import IXcoinKit

/// Parses real merged-mined blocks captured from the live chain — the same
/// fixtures the Android wallet uses.
final class AuxPoWTests: XCTestCase {

    private func fixture(_ name: String) throws -> Data {
        let url = try XCTUnwrap(
            Bundle.module.url(forResource: "Fixtures/\(name)", withExtension: "hex")
                ?? Bundle.module.url(forResource: name, withExtension: "hex"),
            "missing fixture \(name).hex"
        )
        let text = try String(contentsOf: url, encoding: .ascii)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return try XCTUnwrap(Data(hex: text), "fixture \(name) is not hex")
    }

    func testParsesMergedMinedBlock() throws {
        let raw = try fixture("block_1051000")
        let header = try BlockHeader(raw: raw)

        XCTAssertTrue(header.hasAuxPoW, "block should be merged-mined")
        XCTAssertEqual(header.chainId, ChainParams.chainId)

        let proof = try AuxPoW(payload: raw, offset: BlockHeader.size)
        XCTAssertFalse(proof.coinbase.isEmpty)
        XCTAssertEqual(proof.parentBlockHash.count, 32)
        XCTAssertEqual(proof.parentHeader.raw.count, 80)
        XCTAssertLessThanOrEqual(proof.coinbaseBranch.count, AuxPoW.maxBranchLength)

        // The proof must land inside the message, not past it.
        XCTAssertLessThan(BlockHeader.size + proof.byteCount, raw.count)
    }

    /// The proof binds the parent's work to this chain: the coinbase has to
    /// hash up to the parent's merkle root.
    func testProofBindsToParentMerkleRoot() throws {
        let raw = try fixture("block_1051000")
        let header = try BlockHeader(raw: raw)
        let proof = try AuxPoW(payload: raw, offset: BlockHeader.size)

        let txid = Crypto.sha256d(proof.coinbase)
        let root = AuxPoW.applyBranch(leaf: txid,
                                      branch: proof.coinbaseBranch,
                                      index: proof.coinbaseIndex)
        XCTAssertEqual(root, proof.parentHeader.merkleRoot,
                       "coinbase must hash up to the parent merkle root")
    }

    /// The block that stalled the Android wallet: its *own* hash is above its
    /// target, which is correct and expected for merged mining — the work lives
    /// on the parent chain. Checking the wrong hash here is exactly the bug
    /// that froze that client, so it is asserted explicitly.
    func testMergedMinedHeaderHashIsAboveItsOwnTarget() throws {
        let raw = try fixture("block_failing")
        let header = try BlockHeader(raw: raw)
        XCTAssertTrue(header.hasAuxPoW)
        XCTAssertFalse(header.meetsTarget(header.hash),
                       "a merged-mined header is not expected to meet its own target")

        let proof = try AuxPoW(payload: raw, offset: BlockHeader.size)
        XCTAssertTrue(header.meetsTarget(proof.parentHeader.hash),
                      "the parent header must meet the child's target")
        XCTAssertTrue(proof.isValid(forTarget: header))
    }

    func testTruncatedProofIsRejectedNotCrashed() throws {
        let raw = try fixture("block_1051000")
        let truncated = raw.prefix(BlockHeader.size + 20)
        XCTAssertThrowsError(try AuxPoW(payload: Data(truncated), offset: BlockHeader.size))
    }

    func testTargetExpansion() throws {
        let raw = try fixture("block_1051000")
        let header = try BlockHeader(raw: raw)
        XCTAssertEqual(header.target.count, 32)
        // A target of all zeroes would make every hash "meet" it.
        XCTAssertTrue(header.target.contains { $0 != 0 }, "target must not be zero")
    }
}
