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
            ?: return super.checkProofOfWork(throwException)

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
