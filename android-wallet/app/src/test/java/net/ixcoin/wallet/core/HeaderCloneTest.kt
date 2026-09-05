package net.ixcoin.wallet.core

import org.bitcoinj.core.Context
import org.bitcoinj.core.FilteredBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * bitcoinj hands the chain a *clone* of a block's header rather than the header
 * itself — FilteredBlock.getBlockHeader() is `header.cloneAsHeader()`, and that
 * is the object AbstractBlockChain.add() proof-of-work checks for every
 * `merkleblock` a filtered peer sends.
 *
 * Block.cloneAsHeader() hard-constructs a plain Block, so the clone came back
 * without this subclass and without the merged-mining proof. Proof-of-work was
 * then checked against the aux header's own hash, which for a merged-mined
 * block is legitimately above target: every block was rejected with "Hash is
 * higher than target" and the chain stopped dead. It presented as a stalled
 * sync with healthy peers, because parsing and networking were both fine.
 */
class HeaderCloneTest {

    private val params = IxcoinMainNetParams.get()

    @Before
    fun setUp() = Context.propagate(Context(params))

    private fun hex(name: String): ByteArray {
        val s = javaClass.classLoader!!.getResourceAsStream(name)!!
            .readBytes().toString(Charsets.US_ASCII).trim()
        return ByteArray(s.length / 2) {
            ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
        }
    }

    private fun block(name: String): IxcoinBlock {
        val raw = hex(name)
        return params.getSerializer(false).makeBlock(raw, 0, raw.size) as IxcoinBlock
    }

    @Test
    fun `cloned header keeps its type and its proof`() {
        for (name in listOf("block_1051000.hex", "block_multitx.hex", "block_failing.hex")) {
            val b = block(name)
            assertTrue("$name should be merged-mined", b.hasAuxPoW)
            val clone = b.cloneAsHeader()
            assertTrue("$name: clone must stay an IxcoinBlock, was ${clone.javaClass.name}",
                clone is IxcoinBlock)
            assertNotNull("$name: clone must keep the proof", (clone as IxcoinBlock).auxPoW)
            assertEquals("$name: clone must hash identically", b.hash, clone.hash)
        }
    }

    /** The clone is what actually gets verified, so it must pass on its own. */
    @Test
    fun `cloned header passes proof-of-work verification`() {
        for (name in listOf("block_1051000.hex", "block_multitx.hex", "block_failing.hex")) {
            val clone = block(name).cloneAsHeader()
            // verifyHeader() runs the proof-of-work check and throws
            // VerificationException("Hash is higher than target") on failure.
            clone.verifyHeader()
        }
    }

    /**
     * The end-to-end path: a merkleblock payload is header + proof + partial
     * merkle tree, and what the chain verifies is the header this returns.
     */
    @Test
    fun `filtered block hands back a verifiable merged-mined header`() {
        val full = block("block_failing.hex")
        val proof = full.auxPoW!!
        val header = full.cloneAsHeader()

        val filtered = FilteredBlock(params, header,
            org.bitcoinj.core.PartialMerkleTree.buildFromLeaves(
                params, ByteArray(1) { 0xff.toByte() }, listOf(full.transactions!![0].txId)))

        val fromChain = filtered.blockHeader
        assertTrue("chain must receive an IxcoinBlock, got ${fromChain.javaClass.name}",
            fromChain is IxcoinBlock)
        assertNotNull("proof must survive the round trip", (fromChain as IxcoinBlock).auxPoW)
        assertEquals(proof.rawBytes.size, fromChain.auxPoW!!.rawBytes.size)
        fromChain.verifyHeader()
    }
}
