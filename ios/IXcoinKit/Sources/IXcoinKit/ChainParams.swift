import Foundation

/// iXcoin mainnet constants.
///
/// iXcoin is a 2011 Bitcoin fork that has been merged-mined with Bitcoin since
/// height 45000. Everything here is deliberately expressed as data rather than
/// behaviour so the values can be asserted directly in tests.
public enum ChainParams {
    public static let id = "net.ixcoin.production"

    public static let packetMagic: UInt32 = 0xf1ba_b6db
    public static let port: UInt16 = 8337
    public static let protocolVersion: Int32 = 110_014
    public static let userAgent = "iXcoin Wallet iOS"

    /// Merged mining: iXcoin's chain id lives in the top 16 bits of nVersion.
    public static let chainId: Int = 3
    /// AuxPoW blocks begin here; below it, blocks are ordinary Bitcoin-style PoW.
    public static let auxPoWStartHeight: Int = 45_000

    public static let targetSpacing = 10 * 60
    /// Two weeks, used up to and including `revisedHeight`.
    public static let targetTimespanOriginal = 14 * 24 * 60 * 60
    /// One day, used from height 20056 onwards.
    public static let targetTimespanRevised = 24 * 60 * 60
    public static let intervalRevised = targetTimespanRevised / targetSpacing   // 144
    public static let revisedHeight = 20_055
    /// From here the retarget window walks back a full interval, not interval-1.
    public static let fullWindowHeight = 43_000

    public static let genesisHash =
        "0000000001534ef8893b025b9c1da67250285e35c9f76cae36a4904fdf72c591"

    public static let seedNodes: [(host: String, port: UInt16)] = [
        ("18.217.178.46", 8337),
        ("91.121.45.149", 8337),
        ("2600:1702:7860:6090::48", 8337),
    ]

    /// Explorer used for "view this transaction" links. The path form is served
    /// as a 404 by the static host, so the fragment is load bearing.
    public static func explorerURL(txId: String) -> URL? {
        URL(string: "https://ixc-exp.wattxchange.app/#/tx/\(txId)")
    }
}
