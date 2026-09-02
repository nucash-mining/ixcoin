package net.ixcoin.wallet.core

import org.bitcoinj.core.Message
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.ProtocolException
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.Utils
import org.bitcoinj.core.VarInt

/**
 * The merged-mining proof carried by an iXcoin block whose version has the
 * AuxPoW bit set.
 *
 * Wire layout (matches CAuxPow::SerializationOp in the reference client):
 *
 *   parent coinbase transaction   (standard Bitcoin transaction encoding)
 *   parent block hash             (32 bytes)
 *   coinbase merkle branch        (varint count, then 32 bytes each)
 *   coinbase index                (int32, little endian)
 *   chain merkle branch           (varint count, then 32 bytes each)
 *   chain index                   (int32, little endian)
 *   parent block header           (80 bytes)
 *
 * The proof says: this iXcoin block's hash is committed inside the coinbase of
 * a block on a parent chain (Bitcoin), and that parent block satisfies the
 * proof-of-work. So the work backing an iXcoin block is the parent's work.
 */
class AuxPoW private constructor(
    val coinbase: Transaction,
    val parentBlockHash: Sha256Hash,
    val coinbaseBranch: List<Sha256Hash>,
    val coinbaseIndex: Int,
    val chainBranch: List<Sha256Hash>,
    val chainIndex: Int,
    /** The raw 80-byte parent header; its double-SHA256 is what carries the work. */
    val parentHeader: ByteArray,
    /** Total bytes this structure occupied on the wire. */
    val messageSize: Int
) {

    /** Double-SHA256 of the parent header, i.e. the hash that must meet the target. */
    val parentHash: Sha256Hash by lazy {
        Sha256Hash.wrapReversed(Sha256Hash.hashTwice(parentHeader))
    }

    /** The merkle root the parent header commits to. */
    val parentMerkleRoot: Sha256Hash
        get() = Sha256Hash.wrapReversed(parentHeader.copyOfRange(36, 68))

    /**
     * Recompute the parent chain's merkle root from the coinbase and its branch.
     * If this does not equal the root in the parent header, the proof is forged.
     */
    fun computedParentMerkleRoot(): Sha256Hash =
        foldBranch(coinbase.txId, coinbaseBranch, coinbaseIndex)

    /** True when the coinbase branch actually reconstructs the parent's merkle root. */
    fun coinbaseBranchIsValid(): Boolean =
        computedParentMerkleRoot() == parentMerkleRoot

    companion object {
        /** Set in a block's version field when the block carries an AuxPoW proof. */
        const val VERSION_AUXPOW_BIT = 0x100L

        /** Chain-id lives in the top 16 bits of the version field. iXcoin is 3. */
        const val VERSION_CHAIN_ID_SHIFT = 16

        private const val MAX_BRANCH_LENGTH = 30

        fun hasAuxPoW(version: Long): Boolean = (version and VERSION_AUXPOW_BIT) != 0L

        fun chainId(version: Long): Int =
            ((version shr VERSION_CHAIN_ID_SHIFT) and 0xffffL).toInt()

        /**
         * Parse an AuxPoW structure out of [payload] starting at [offset].
         * Throws [ProtocolException] on anything malformed.
         */
        @JvmStatic
        fun parse(params: NetworkParameters, payload: ByteArray, offset: Int): AuxPoW {
            var cursor = offset

            fun need(n: Int) {
                if (cursor + n > payload.size)
                    throw ProtocolException("AuxPoW truncated: need $n at $cursor of ${payload.size}")
            }

            fun readVarInt(): Long {
                need(1)
                val v = VarInt(payload, cursor)
                cursor += v.originalSizeInBytes
                return v.value
            }

            fun readHash(): Sha256Hash {
                need(32)
                val h = Sha256Hash.wrapReversed(payload.copyOfRange(cursor, cursor + 32))
                cursor += 32
                return h
            }

            fun readInt32(): Int {
                need(4)
                val v = Utils.readUint32(payload, cursor).toInt()
                cursor += 4
                return v
            }

            fun readBranch(what: String): List<Sha256Hash> {
                val n = readVarInt()
                if (n < 0 || n > MAX_BRANCH_LENGTH)
                    throw ProtocolException("AuxPoW $what branch too long: $n")
                return List(n.toInt()) { readHash() }
            }

            // Parent coinbase. Transaction parses itself and reports its length.
            val coinbase = Transaction(
                params, payload, cursor, null,
                params.defaultSerializer, Message.UNKNOWN_LENGTH, null
            )
            cursor += coinbase.messageSize

            val parentBlockHash = readHash()
            val coinbaseBranch = readBranch("coinbase")
            val coinbaseIndex = readInt32()
            val chainBranch = readBranch("chain")
            val chainIndex = readInt32()

            need(80)
            val parentHeader = payload.copyOfRange(cursor, cursor + 80)
            cursor += 80

            return AuxPoW(
                coinbase, parentBlockHash, coinbaseBranch, coinbaseIndex,
                chainBranch, chainIndex, parentHeader, cursor - offset
            )
        }

        /**
         * Walk a merkle branch upward from [leaf], where [index] selects the side
         * at each level (bit 0 = this node is the right child).
         */
        @JvmStatic
        fun foldBranch(leaf: Sha256Hash, branch: List<Sha256Hash>, index: Int): Sha256Hash {
            if (index < 0) return Sha256Hash.ZERO_HASH
            var h = leaf.reversedBytes
            var i = index
            for (step in branch) {
                val s = step.reversedBytes
                h = if (i and 1 == 1) Sha256Hash.hashTwice(s + h) else Sha256Hash.hashTwice(h + s)
                i = i shr 1
            }
            return Sha256Hash.wrapReversed(h)
        }
    }
}
