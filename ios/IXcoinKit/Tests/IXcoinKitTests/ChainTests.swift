import XCTest
@testable import IXcoinKit

/// These mirror the Android wallet's tests so the two clients cannot drift on
/// consensus-relevant details.
final class ChainTests: XCTestCase {

    func testChainConstants() {
        XCTAssertEqual(ChainParams.packetMagic, 0xf1ba_b6db)
        XCTAssertEqual(ChainParams.port, 8337)
        XCTAssertEqual(ChainParams.protocolVersion, 110_014)
        XCTAssertEqual(ChainParams.chainId, 3)
        XCTAssertEqual(ChainParams.intervalRevised, 144)
    }

    func testAuxPoWVersionBits() {
        // A real merged-mined iXcoin header: chain id 3, auxpow bit set.
        let version: UInt32 = 0x0003_0104
        XCTAssertTrue(AuxPoW.hasAuxPoW(version: version))
        XCTAssertEqual(AuxPoW.chainId(version: version), 3)

        // Same chain, plain PoW block.
        let legacy: UInt32 = 0x0003_0004
        XCTAssertFalse(AuxPoW.hasAuxPoW(version: legacy))
    }

    // MARK: difficulty

    func testRetargetWindowSwitchesAtRevisedHeight() {
        XCTAssertEqual(Difficulty.targetTimespan(height: 20_000), 14 * 24 * 60 * 60)
        XCTAssertEqual(Difficulty.targetTimespan(height: 20_055), 14 * 24 * 60 * 60)
        XCTAssertEqual(Difficulty.targetTimespan(height: 20_056), 24 * 60 * 60)
        XCTAssertEqual(Difficulty.interval(height: 20_056), 144)
        XCTAssertEqual(Difficulty.interval(height: 20_000), 2016)
    }

    func testBlocksToGoBackQuirk() {
        // Below 43000 the Bitcoin off-by-one applies.
        XCTAssertEqual(Difficulty.blocksToGoBack(height: 30_000), 143)
        // From 43000 the full interval is used.
        XCTAssertEqual(Difficulty.blocksToGoBack(height: 43_000), 144)
        XCTAssertEqual(Difficulty.blocksToGoBack(height: 1_000_000), 144)
        // Height 144 is still pre-revision, so the window is the 2-week one and
        // the interval is 2016 — not 144. The `height != interval` carve-out in
        // the consensus rule is therefore unreachable in practice: it can only
        // fire once the interval is 144, which only happens above height 20055,
        // and by then the full-window rule needs height >= 43000 anyway.
        XCTAssertEqual(Difficulty.interval(height: 144), 2016)
        XCTAssertEqual(Difficulty.blocksToGoBack(height: 144), 2015)
    }

    func testTimespanClamping() {
        let h = 100_000
        let t = ChainParams.targetTimespanRevised
        XCTAssertEqual(Difficulty.clampTimespan(1, height: h), t / 4)
        XCTAssertEqual(Difficulty.clampTimespan(t * 100, height: h), t * 4)
        XCTAssertEqual(Difficulty.clampTimespan(t, height: h), t)
    }

    // MARK: byte reader

    func testVarIntForms() throws {
        var r = ByteReader(Data([0x10]))
        XCTAssertEqual(try r.readVarInt(), 0x10)

        var r2 = ByteReader(Data([0xfd, 0x01, 0x02]))
        XCTAssertEqual(try r2.readVarInt(), 0x0201)

        var r3 = ByteReader(Data([0xfe, 0x01, 0x00, 0x00, 0x00]))
        XCTAssertEqual(try r3.readVarInt(), 1)
    }

    func testReaderRefusesToRunPastTheEnd() {
        var r = ByteReader(Data([0x01, 0x02]))
        XCTAssertThrowsError(try r.read(3)) { error in
            XCTAssertEqual(error as? ByteReader.Error, .truncated(needed: 3, available: 2))
        }
    }

    func testHexRoundTrip() throws {
        let d = try XCTUnwrap(Data(hex: "deadbeef"))
        XCTAssertEqual(d.hex, "deadbeef")
        XCTAssertEqual(d.reversedHex, "efbeadde")
    }

    func testExplorerLinkKeepsTheFragment() throws {
        let url = try XCTUnwrap(ChainParams.explorerURL(txId: "abc123"))
        // The path form is served as a 404 by the static host; "#/" is required.
        XCTAssertTrue(url.absoluteString.contains("/#/tx/abc123"), url.absoluteString)
    }
}
