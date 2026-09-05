package net.ixcoin.wallet.core

import org.bitcoinj.core.CheckpointManager
import org.bitcoinj.core.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The shipped checkpoints must actually load, and must chain correctly — a
 * malformed file would silently drop a new wallet back to syncing a million
 * headers from genesis.
 */
class CheckpointsTest {

    private val params = IxcoinMainNetParams.get()

    @Before
    fun setUp() = Context.propagate(Context(params))

    private fun stream() =
        File("src/main/assets/checkpoints.txt").inputStream()

    @Test
    fun `checkpoints load and reach near the chain tip`() {
        val cm = CheckpointManager(params, stream())
        assertTrue("expected a meaningful number of checkpoints", cm.numCheckpoints() > 100)

        // A wallet created now should land on the most recent checkpoint.
        val latest = cm.getCheckpointBefore(System.currentTimeMillis() / 1000)
        assertTrue(
            "latest checkpoint should be past the AuxPoW switch, got ${latest.height}",
            latest.height > 1_000_000
        )
    }

    @Test
    fun `an early wallet lands on an early checkpoint`() {
        val cm = CheckpointManager(params, stream())
        // 1 Jan 2013 — well before the tip, so it must not hand back the newest.
        val early = cm.getCheckpointBefore(1_356_998_400L)
        assertTrue("expected an early checkpoint, got ${early.height}", early.height < 500_000)
    }

    @Test
    fun `the genesis checkpoint matches the chain's genesis`() {
        val cm = CheckpointManager(params, stream())
        val first = cm.getCheckpointBefore(params.genesisBlock.timeSeconds + 1)
        assertEquals(IxcoinMainNetParams.GENESIS_HASH, first.header.hashAsString)
    }
}
