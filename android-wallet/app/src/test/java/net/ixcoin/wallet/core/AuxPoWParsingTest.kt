package net.ixcoin.wallet.core

import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Utils
import org.bitcoinj.core.VarInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Parses real `headers` payloads captured from a live iXcoin node.
 *
 * These are the two properties that matter for an SPV client:
 *  1. framing — an AuxPoW header is 80 bytes plus a variable-length proof, so
 *     mis-sizing it desynchronises the rest of the message; and
 *  2. the coinbase merkle branch must reproduce the parent block's merkle root,
 *     which is what ties iXcoin's chain to Bitcoin's work.
 */
class AuxPoWParsingTest {

    private val params = IxcoinMainNetParams.get()

    @Before
    fun setUpContext() {
        // bitcoinj keeps per-network state in a thread-local Context.
        Context.propagate(Context(params))
    }

    private fun load(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    /** Walk a headers payload the way HeadersMessage does, via optimalEncodingMessageSize. */
    private fun parseAll(payload: ByteArray): List<IxcoinBlock> {
        val countVar = VarInt(payload, 0)
        var cursor = countVar.originalSizeInBytes
        val blocks = ArrayList<IxcoinBlock>(countVar.value.toInt())
        repeat(countVar.value.toInt()) {
            val b = params.getSerializer(false).makeBlock(payload, cursor, Int.MIN_VALUE) as IxcoinBlock
            cursor += b.optimalEncodingMessageSize
            blocks.add(b)
        }
        assertEquals("payload must be consumed exactly", payload.size, cursor)
        return blocks
    }

    @Test
    fun `parses the window straddling auxpow activation`() {
        val blocks = parseAll(load("headers_44998.bin"))
        assertEquals(2000, blocks.size)
        assertEquals(1936, blocks.count { it.hasAuxPoW })
        // The first two are still legacy (pre-45000) blocks.
        assertTrue(!blocks[0].hasAuxPoW)
        assertEquals(
            "0000000000010c050665658381eb0c9af6b82885eb46724cd598d0c07275df74",
            blocks[0].hashAsString
        )
    }

    @Test
    fun `parses a window that is entirely auxpow`() {
        val blocks = parseAll(load("headers_500000.bin"))
        assertEquals(2000, blocks.size)
        assertEquals(2000, blocks.count { it.hasAuxPoW })
        assertEquals(
            "d4a0daefeb9decca87ef0eb81afb9250439e805c0563634781c7abab54d380bd",
            blocks[0].hashAsString
        )
    }

    @Test
    fun `every header links to the previous one`() {
        for (name in listOf("headers_44998.bin", "headers_500000.bin")) {
            val blocks = parseAll(load(name))
            for (i in 1 until blocks.size) {
                assertEquals(
                    "$name: header $i does not link to $i-1",
                    blocks[i - 1].hash, blocks[i].prevBlockHash
                )
            }
        }
    }

    @Test
    fun `auxpow coinbase branch reproduces the parent merkle root`() {
        for (name in listOf("headers_44998.bin", "headers_500000.bin")) {
            val proofs = parseAll(load(name)).mapNotNull { it.auxPoW }
            assertTrue("$name: expected auxpow proofs", proofs.isNotEmpty())
            for (p in proofs) {
                assertEquals(
                    "$name: forged or mis-parsed coinbase branch",
                    p.parentMerkleRoot, p.computedParentMerkleRoot()
                )
            }
        }
    }

    @Test
    fun `auxpow blocks carry the ixcoin chain id`() {
        val blocks = parseAll(load("headers_500000.bin"))
        assertTrue(blocks.all { it.chainId == IxcoinBlock.CHAIN_ID })
    }

    @Test
    fun `genesis block matches the known hash`() {
        assertEquals(IxcoinMainNetParams.GENESIS_HASH, params.genesisBlock.hashAsString)
    }

    @Test
    fun `merkle branch folding matches a hand-computed case`() {
        // index 0 with a single sibling: root = H(leaf || sibling)
        val leaf = Sha256Hash.wrap("00".repeat(31) + "01")
        val sib = Sha256Hash.wrap("00".repeat(31) + "02")
        val expected = Sha256Hash.wrapReversed(
            Sha256Hash.hashTwice(leaf.reversedBytes + sib.reversedBytes)
        )
        assertEquals(expected, AuxPoW.foldBranch(leaf, listOf(sib), 0))
    }

    @Test
    fun `a compact stored header with the auxpow bit parses as header-only`() {
        // This is how a block comes back out of SPVBlockStore: the header alone,
        // with no proof following it. Parsing must not run off the end.
        val withAux = parseAll(load("headers_500000.bin")).first { it.hasAuxPoW }
        // Exactly what StoredBlock.deserializeCompact hands the serializer:
        // 80 header bytes plus one zero byte for the transaction count.
        val headerOnly = ByteArray(81)
        withAux.bitcoinSerialize().copyInto(headerOnly, 0, 0, 80)
        assertEquals(81, headerOnly.size)

        val reparsed = params.getSerializer(false)
            .makeBlock(headerOnly, 0, headerOnly.size) as IxcoinBlock
        assertEquals(withAux.hash, reparsed.hash)
        assertTrue("version still marks it as auxpow", reparsed.hasAuxPoW)
        assertEquals("but no proof is present to parse", null, reparsed.auxPoW)
    }
}
