package net.ixcoin.wallet.core

import org.bitcoinj.core.BitcoinSerializer
import org.bitcoinj.core.Block
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.StoredBlock
import org.bitcoinj.core.Utils
import org.bitcoinj.core.VerificationException
import org.bitcoinj.params.AbstractBitcoinNetParams
import org.bitcoinj.store.BlockStore
import org.bitcoinj.store.BlockStoreException
import org.bitcoinj.utils.MonetaryFormat
import java.math.BigInteger

/**
 * Consensus parameters for the iXcoin main network.
 *
 * iXcoin is a 2011 Bitcoin fork, merged-mined with Bitcoin from block 45000
 * (chain id 3). Two things differ from Bitcoin in ways an SPV client must get
 * right:
 *
 *  - blocks may carry an AuxPoW proof, handled by [IxcoinBlock]; and
 *  - the difficulty retarget is iXcoin's own, not Bitcoin's. See
 *    [checkDifficultyTransitions], which mirrors pow.cpp in the reference client.
 */
class IxcoinMainNetParams : AbstractBitcoinNetParams() {

    init {
        id = ID_IXCOIN_MAINNET

        packetMagic = 0xf1bab6dbL
        port = 8337

        addressHeader = 138          // 'x' addresses
        p2shHeader = 5               // '3' addresses, same as Bitcoin
        dumpedPrivateKeyHeader = 128
        segwitAddressHrp = "ix"      // segwit is not deployed; present for completeness

        bip32HeaderP2PKHpub = 0x0488B21E
        bip32HeaderP2PKHpriv = 0x0488ADE4
        bip32HeaderP2WPKHpub = 0x04B24746
        bip32HeaderP2WPKHpriv = 0x04B2430C

        // powLimit 00000000ffff...ffff, the same "difficulty 1" as Bitcoin
        maxTarget = Utils.decodeCompactBits(0x1d00ffffL)

        // Nominal Bitcoin values. The real interval is height-dependent and is
        // computed in difficultyAdjustmentInterval(); these keep bitcoinj's own
        // bookkeeping sane.
        interval = INTERVAL_REVISED
        targetTimespan = TARGET_TIMESPAN_REVISED

        spendableCoinbaseDepth = 100
        subsidyDecreaseBlockCount = 210000

        majorityEnforceBlockUpgrade = 750
        majorityRejectBlockOutdated = 950
        majorityWindow = 1000

        genesisBlock = parseGenesis()
        val genesisHash = genesisBlock.hashAsString
        check(genesisHash == GENESIS_HASH) { "genesis hash mismatch: $genesisHash" }

        // The project's DNS seeds stopped resolving, so discovery starts from
        // known-reachable nodes and continues over addr gossip.
        dnsSeeds = arrayOf()
        addrSeeds = intArrayOf()

        checkpoints[4500] = Sha256Hash.wrap("00000000de37be98ca45cf0613fa2a321eba28e237543f9fee9b6a7605d03a94")
        checkpoints[198007] = Sha256Hash.wrap("00fdfc9130416482887e4d56f89f4568c2f4d7764d14cc66833503f31a6ac73d")
    }

    override fun getPaymentProtocolId(): String = "main"

    override fun getMaxMoney(): Coin = MAX_MONEY_IXC

    override fun hasMaxMoney(): Boolean = true

    override fun getMinNonDustOutput(): Coin = Coin.valueOf(5460)

    override fun getMonetaryFormat(): MonetaryFormat = IXC_FORMAT

    override fun getUriScheme(): String = "ixcoin"

    override fun getSerializer(parseRetain: Boolean): BitcoinSerializer =
        IxcoinSerializer(this, parseRetain)

    override fun getProtocolVersionNum(version: ProtocolVersion): Int = when (version) {
        // What we advertise: iXcoin nodes speak 110014, not Bitcoin's numbering.
        ProtocolVersion.CURRENT, ProtocolVersion.WITNESS_VERSION -> PROTOCOL_VERSION
        // Feature gates stay on Bitcoin's scale. Every iXcoin node reports
        // 110014, which clears all of them, so bloom filtering stays enabled.
        else -> version.bitcoinProtocolVersion
    }

    // ---------------------------------------------------------------- difficulty

    /** iXcoin moved to a 1-day retarget window after height 20055. */
    private fun revised(height: Int): Boolean = height > REVISED_HEIGHT

    private fun powTargetTimespan(height: Int): Int =
        if (revised(height)) TARGET_TIMESPAN_REVISED else TARGET_TIMESPAN_ORIGINAL

    private fun difficultyAdjustmentInterval(height: Int): Int =
        powTargetTimespan(height) / TARGET_SPACING

    @Throws(VerificationException::class, BlockStoreException::class)
    override fun checkDifficultyTransitions(storedPrev: StoredBlock, nextBlock: Block, blockStore: BlockStore) {
        val height = storedPrev.height + 1
        val intervalHere = difficultyAdjustmentInterval(height)

        if (height % intervalHere != 0) {
            // Not a retarget point: difficulty must be carried over unchanged.
            if (nextBlock.difficultyTarget != storedPrev.header.difficultyTarget) {
                throw VerificationException(
                    "Unexpected change in difficulty at height $height: " +
                        java.lang.Long.toHexString(nextBlock.difficultyTarget) + " vs " +
                        java.lang.Long.toHexString(storedPrev.header.difficultyTarget)
                )
            }
            return
        }

        // Walk back to the start of the window. iXcoin uses interval-1 blocks,
        // except from height 43000 onward where it uses the full interval (the
        // off-by-one fix inherited from Litecoin).
        var blocksToGoBack = intervalHere - 1
        if (height >= FULL_WINDOW_HEIGHT && height != intervalHere) {
            blocksToGoBack = intervalHere
        }

        var cursor: StoredBlock = storedPrev
        repeat(blocksToGoBack) {
            val prev = cursor.getPrev(blockStore)
            if (prev == null) {
                // We started from a checkpoint, so the window this retarget is
                // computed over predates anything we hold. The retarget cannot
                // be recomputed and there is nothing to compare against — the
                // checkpoint is the trust anchor for everything below it, so
                // accept and carry on rather than rejecting a valid chain.
                // Without this a checkpointed wallet stalls at the first
                // retarget it meets.
                return
            }
            cursor = prev
        }

        val timespan = calculateNextTimespan(
            actual = storedPrev.header.timeSeconds - cursor.header.timeSeconds,
            target = powTargetTimespan(height).toLong(),
            revised = revised(height)
        )

        var newTarget = Utils.decodeCompactBits(storedPrev.header.difficultyTarget)
        newTarget = newTarget.multiply(BigInteger.valueOf(timespan))
        newTarget = newTarget.divide(BigInteger.valueOf(powTargetTimespan(height).toLong()))
        if (newTarget > maxTarget) newTarget = maxTarget

        // Compare using the compact encoding, since that is what is in the block.
        val expected = Utils.encodeCompactBits(roundToCompactPrecision(newTarget))
        val received = nextBlock.difficultyTarget
        if (expected != received) {
            throw VerificationException(
                "Network provided difficulty bits do not match what was calculated at height $height: " +
                    java.lang.Long.toHexString(received) + " vs " + java.lang.Long.toHexString(expected)
            )
        }
    }

    /**
     * The damping applied to the measured timespan.
     *
     * Before the revision this is Bitcoin's symmetric 4x clamp. After it,
     * iXcoin uses a SolidCoin-derived rule that barely lets difficulty fall
     * when blocks come in fast, while still allowing a 4x rise when they are slow.
     */
    private fun calculateNextTimespan(actual: Long, target: Long, revised: Boolean): Long {
        if (!revised) {
            return actual.coerceIn(target / 4, target * 4)
        }
        val twoPercent = target / 50
        return when {
            actual < target -> when {
                actual < twoPercent * 16 -> twoPercent * 45   // pretend only 10% fast
                actual < twoPercent * 32 -> twoPercent * 47   // pretend only 6% fast
                else -> twoPercent * 49                       // pretend only 2% fast
            }
            actual > target * 4 -> target * 4
            else -> actual
        }
    }

    /** Mirror the precision loss of the 256-bit compact encoding round trip. */
    private fun roundToCompactPrecision(value: BigInteger): BigInteger =
        Utils.decodeCompactBits(Utils.encodeCompactBits(value))

    /**
     * Build the genesis block from its header fields.
     *
     * Deserialising the full block is not an option here: bitcoinj's
     * Transaction parsing requires a Context, and a Context requires the
     * NetworkParameters we are still constructing. A header is all the chain
     * needs anyway — the block hash is a function of the header alone.
     */
    private fun parseGenesis(): Block {
        val header = hexToBytes(GENESIS_HEADER_HEX)
        require(header.size == 80) { "genesis header must be 80 bytes" }
        return Block(
            this,
            Utils.readUint32(header, 0),                                   // version
            Sha256Hash.wrapReversed(header.copyOfRange(4, 36)),            // prev (all zero)
            Sha256Hash.wrapReversed(header.copyOfRange(36, 68)),           // merkle root
            Utils.readUint32(header, 68),                                  // time
            Utils.readUint32(header, 72),                                  // bits
            Utils.readUint32(header, 76),                                  // nonce
            emptyList()
        )
    }

    companion object {
        const val ID_IXCOIN_MAINNET = "net.ixcoin.production"

        /** Protocol version advertised by iXcoin Core 0.14.1 nodes. */
        const val PROTOCOL_VERSION = 110014

        const val TARGET_SPACING = 10 * 60
        const val TARGET_TIMESPAN_ORIGINAL = 14 * 24 * 60 * 60   // 2 weeks, pre-revision
        const val TARGET_TIMESPAN_REVISED = 24 * 60 * 60         // 1 day, from height 20056
        const val INTERVAL_REVISED = TARGET_TIMESPAN_REVISED / TARGET_SPACING  // 144
        const val REVISED_HEIGHT = 20055
        const val FULL_WINDOW_HEIGHT = 43000

        /** 21,000,000 IXC in satoshi-equivalents. */
        val MAX_MONEY_IXC: Coin = Coin.COIN.multiply(21_000_000L)

        val IXC_FORMAT: MonetaryFormat = MonetaryFormat.BTC.noCode()
            .code(0, "IXC").code(3, "mIXC").code(6, "µIXC")

        const val GENESIS_HASH = "0000000001534ef8893b025b9c1da67250285e35c9f76cae36a4904fdf72c591"

        /** The 80-byte genesis header. Coinbase: "To see the farm is to leave it". */
        private const val GENESIS_HEADER_HEX =
            "01000000" +                                                          // version
            "0000000000000000000000000000000000000000000000000000000000000000" +  // prev
            "3fba9773897bd4d69edefe6043a476c14bcd5513135d4c83eb7cc967b8e73acb" +  // merkle root
            "2731bb4d" +                                                          // time 1304113447
            "ffff001d" +                                                          // bits 0x1d00ffff
            "611ed485"                                                            // nonce 2245271137

        /** Utils.HEX exposes a Guava type; decode locally to keep that off our API. */
        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) {
                ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte()
            }

        @Volatile
        private var instance: IxcoinMainNetParams? = null

        @JvmStatic
        fun get(): IxcoinMainNetParams =
            instance ?: synchronized(this) {
                instance ?: IxcoinMainNetParams().also { instance = it }
            }
    }
}
