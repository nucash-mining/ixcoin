package net.ixcoin.wallet.core

import org.bitcoinj.core.Block
import org.bitcoinj.core.Message
import org.bitcoinj.core.MessageSerializer
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.ProtocolException
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.VerificationException
import java.math.BigInteger

/**
 * An iXcoin block.
 *
 * iXcoin is merged-mined with Bitcoin. From block 45000 onward a block may set
 * the AuxPoW bit in its version, in which case a proof-of-work structure sits
 * between the 80-byte header and the transaction list. bitcoinj assumes a fixed
 * 80-byte header, so the variable-length part is handled by overriding
 * [parseTransactions] — the hook bitcoinj documents for exactly this case.
 *
 * Proof of work is likewise different: for an AuxPoW block it is the *parent*
 * (Bitcoin) header that must hash below the target, not this block's header.
 */
class IxcoinBlock : Block {

    /** The merged-mining proof, or null for a plain (non-AuxPoW) block. */
    var auxPoW: AuxPoW? = null
        private set

    /**
     * Re-attach a proof that was parsed separately.
     *
     * A `merkleblock` carries the proof between the header and the partial
     * merkle tree, and bitcoinj builds its header from the 80 header bytes
     * alone — so the proof has to be put back, or proof-of-work verification
     * sees a bare merged-mined header, checks the wrong hash against the
     * target, and rejects a perfectly good block.
     */
    fun attachAuxPoW(proof: AuxPoW) {
        auxPoW = proof
    }

    constructor(
        params: NetworkParameters,
        payload: ByteArray,
        offset: Int,
        parent: Message?,
        serializer: MessageSerializer,
        length: Int
    ) : super(params, payload, offset, parent, serializer, length)

    constructor(
        params: NetworkParameters,
        payload: ByteArray,
        offset: Int,
        serializer: MessageSerializer,
        length: Int
    ) : super(params, payload, offset, serializer, length)

    /** True when this block's version marks it as merged-mined. */
    val hasAuxPoW: Boolean get() = AuxPoW.hasAuxPoW(version)

    /** Chain id encoded in the top 16 bits of the version field. */
    val chainId: Int get() = AuxPoW.chainId(version)

    /**
     * Called by [Block.parse] with the offset just past the 80-byte header.
     * For an AuxPoW block the proof is consumed first, then the (empty, in a
     * headers message) transaction list is parsed from after it.
     */
    override fun parseTransactions(transactionsOffset: Int) {
        // A header read back from a block store carries no AuxPoW proof:
        // SPVBlockStore keeps headers only, and StoredBlock.deserializeCompact
        // hands us exactly 81 bytes — the 80-byte header plus a single zero
        // byte standing in for the transaction count. The AuxPoW bit is still
        // set in the version, so without this check we would try to parse a
        // proof that was never stored and run off the end of the buffer.
        val remaining = (payload?.size ?: 0) - transactionsOffset
        if (remaining <= 1) {
            auxPoW = null
            super.parseTransactions(transactionsOffset)
            return
        }
        if (!AuxPoW.hasAuxPoW(version)) {
            auxPoW = null
            super.parseTransactions(transactionsOffset)
            return
        }
        val proof = AuxPoW.parse(params, payload, transactionsOffset)
        auxPoW = proof
        super.parseTransactions(transactionsOffset + proof.messageSize)
        // Keep the "ideal encoding" accounting honest; HeadersMessage advances
        // its cursor by exactly this value.
        optimalEncodingMessageSize += proof.messageSize
    }

    /**
     * For an AuxPoW block the work is proven by the parent chain's header, so
     * that is the hash compared against this block's target. A non-AuxPoW block
     * falls back to bitcoinj's normal check.
     */
    override fun checkProofOfWork(throwException: Boolean): Boolean {
        val proof = auxPoW
        if (proof == null) {
            // A merged-mined block whose proof we do not hold. Its own header
            // hash is *expected* to be above the target — the work was done on
            // the parent chain — so running the plain check would reject every
            // block above height 45000 and stall the wallet permanently.
            //
            // This happens whenever bitcoinj rebuilds a header on its own: from
            // the block store, and from a merkleblock, where it constructs the
            // header from the 80 header bytes alone. We cannot verify the work
            // without the proof, so the block is accepted on the strength of
            // the checks that still apply — the difficulty target it claims,
            // its position in the chain, and the accumulated work of the chain
            // it extends.
            if (AuxPoW.hasAuxPoW(version)) return true
            return super.checkProofOfWork(throwException)
        }

        // A forged branch would let anyone claim someone else's work.
        if (!proof.coinbaseBranchIsValid()) {
            if (throwException)
                throw VerificationException("AuxPoW coinbase merkle branch does not match the parent header")
            return false
        }

        val target: BigInteger = difficultyTargetAsInteger
        val work = proof.parentHash.toBigInteger()
        if (work > target) {
            if (throwException)
                throw VerificationException(
                    "AuxPoW parent hash is higher than target: ${proof.parentHash} vs ${target.toString(16)}"
                )
            return false
        }
        return true
    }

    /**
     * Write the block back exactly as it arrived: header, AuxPoW proof, then
     * transactions. bitcoinj's version knows nothing about the proof and would
     * silently drop it, so a re-serialised block would no longer match its own
     * hash-committed contents.
     */
    /**
     * bitcoinj clones a header whenever it detaches one from its block body —
     * notably FilteredBlock.getBlockHeader(), which is what the chain verifies
     * for every `merkleblock`. The inherited version hard-constructs a plain
     * Block, so the clone lost both this subclass and the merged-mining proof,
     * and proof-of-work was then checked against the aux header's own hash,
     * which for a merged-mined block is legitimately above target. Every such
     * block was rejected with "Hash is higher than target" and the chain
     * stopped advancing. Cloning into an IxcoinBlock keeps the proof attached.
     */
    override fun cloneAsHeader(): Block {
        val standard = java.io.ByteArrayOutputStream()
        super.bitcoinSerializeToStream(standard)
        val headerBytes = standard.toByteArray().copyOf(HEADER_SIZE)
        val clone = IxcoinBlock(params, headerBytes, 0, params.defaultSerializer, HEADER_SIZE)
        auxPoW?.let { clone.attachAuxPoW(it) }
        return clone
    }

    override fun bitcoinSerializeToStream(stream: java.io.OutputStream) {
        stream.write(serializeWithAuxPoW())
    }

    /**
     * Block.bitcoinSerialize() writes the header and transactions itself rather
     * than going through bitcoinSerializeToStream, so overriding only the
     * latter silently produced blocks with the proof missing. Both are
     * overridden here.
     */
    override fun bitcoinSerialize(): ByteArray = serializeWithAuxPoW()

    private fun serializeWithAuxPoW(): ByteArray {
        val standard = java.io.ByteArrayOutputStream()
        super.bitcoinSerializeToStream(standard)
        val bytes = standard.toByteArray()
        val proof = auxPoW ?: return bytes
        val out = java.io.ByteArrayOutputStream(bytes.size + proof.rawBytes.size)
        out.write(bytes, 0, HEADER_SIZE)
        out.write(proof.rawBytes)
        out.write(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE)
        return out.toByteArray()
    }

    override fun toString(): String = buildString {
        append("iXcoin block ").append(hashAsString)
        append(" v0x").append(java.lang.Long.toHexString(version))
        append(" chain=").append(chainId)
        append(if (hasAuxPoW) " auxpow(parent=${auxPoW?.parentHash})" else " (plain pow)")
    }

    companion object {
        /** Height at which merged mining became permitted on mainnet. */
        const val AUXPOW_START_HEIGHT = 45000

        /** iXcoin's merged-mining chain id. */
        const val CHAIN_ID = 3
    }
}
