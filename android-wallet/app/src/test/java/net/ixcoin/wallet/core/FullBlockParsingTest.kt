package net.ixcoin.wallet.core

import org.bitcoinj.core.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Full `block` messages, not just headers.
 *
 * Once past its fast-catchup point a wallet receives whole blocks, and an
 * AuxPoW block puts a variable-length proof between the header and the
 * transaction list. Getting that length even slightly wrong leaves the cursor
 * mid-field, the transaction count reads as garbage, and bitcoinj throws
 * "Claimed value length too large" — which kills the connection and drops the
 * peer, so it presents as a network problem rather than a parsing one.
 */
class FullBlockParsingTest {

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

    @Test
    fun `parses a coinbase-only auxpow block`() {
        val raw = hex("block_1051000.hex")
        val b = params.getSerializer(false).makeBlock(raw, 0, raw.size) as IxcoinBlock
        assertTrue("should carry an auxpow proof", b.hasAuxPoW)
        assertEquals(IxcoinBlock.CHAIN_ID, b.chainId)
        assertEquals(1, b.transactions!!.size)
        assertEquals("must consume the whole message", raw.size, b.messageSize)
    }

    @Test
    fun `parses an auxpow block containing real transactions`() {
        val raw = hex("block_multitx.hex")
        val b = params.getSerializer(false).makeBlock(raw, 0, raw.size) as IxcoinBlock
        assertTrue("should carry an auxpow proof", b.hasAuxPoW)
        assertEquals(2, b.transactions!!.size)
        assertEquals("must consume the whole message", raw.size, b.messageSize)
        // Every transaction must have been read from exactly the right offset,
        // which the byte-exact round trip below also proves.
        assertTrue("transactions should have ids", b.transactions!!.all { it.txId != null })
    }

    @Test
    fun `re-serialising a parsed block reproduces the original bytes`() {
        for (name in listOf("block_1051000.hex", "block_multitx.hex")) {
            val raw = hex(name)
            val b = params.getSerializer(false).makeBlock(raw, 0, raw.size) as IxcoinBlock
            assertEquals("$name: round trip must be byte-exact",
                raw.toList(), b.bitcoinSerialize().toList())
        }
    }

    @Test
    fun `parses the block that was killing live connections`() {
        // Height 1051158. This one repeatedly threw "Claimed value length too
        // large" on the device, which drops the peer — so the wallet looked
        // like it had a flaky network rather than a parser that mis-sized the
        // merged-mining proof.
        val raw = hex("block_failing.hex")
        val b = params.getSerializer(false).makeBlock(raw, 0, raw.size) as IxcoinBlock
        assertTrue(b.hasAuxPoW)
        assertEquals("must consume the whole message", raw.size, b.messageSize)
        assertEquals(raw.toList(), b.bitcoinSerialize().toList())
    }
}
