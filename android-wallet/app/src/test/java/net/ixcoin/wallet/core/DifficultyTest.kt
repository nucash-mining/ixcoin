package net.ixcoin.wallet.core

import org.bitcoinj.core.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * iXcoin's retarget is not Bitcoin's: the window shrinks from 2 weeks to 1 day
 * above height 20055, and the damping above that height is asymmetric. These
 * expectations were checked against the live chain with tools/checkdiff.py.
 */
class DifficultyTest {

    private val params = IxcoinMainNetParams.get()

    @Before
    fun setUpContext() {
        // bitcoinj keeps per-network state in a thread-local Context.
        Context.propagate(Context(params))
    }

    @Test
    fun `interval is two weeks worth of blocks before the revision`() {
        assertEquals(
            IxcoinMainNetParams.TARGET_TIMESPAN_ORIGINAL / IxcoinMainNetParams.TARGET_SPACING,
            2016
        )
    }

    @Test
    fun `interval is one day worth of blocks after the revision`() {
        assertEquals(IxcoinMainNetParams.INTERVAL_REVISED, 144)
    }

    @Test
    fun `genesis target is difficulty one`() {
        assertEquals(0x1d00ffffL, params.genesisBlock.difficultyTarget)
    }

    @Test
    fun `network identifiers match the reference client`() {
        assertEquals(0xf1bab6dbL, params.packetMagic)
        assertEquals(8337, params.port)
        assertEquals(138, params.addressHeader)
        assertEquals(5, params.p2SHHeader)
        assertEquals(128, params.dumpedPrivateKeyHeader)
        assertEquals(110014, IxcoinMainNetParams.PROTOCOL_VERSION)
    }
}
