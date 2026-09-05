package net.ixcoin.wallet.core

import org.bitcoinj.core.BitcoinSerializer
import org.bitcoinj.core.Block
import org.bitcoinj.core.EmptyMessage
import org.bitcoinj.core.FilteredBlock
import org.bitcoinj.core.PartialMerkleTree
import org.bitcoinj.core.Utils
import org.bitcoinj.core.Message
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.ProtocolException
import org.bitcoinj.core.Sha256Hash
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * iXcoin's wire serializer.
 *
 * Two departures from stock bitcoinj:
 *
 *  1. blocks are built as [IxcoinBlock] so AuxPoW headers deserialize; and
 *  2. protocol messages bitcoinj 0.15 does not know are dropped instead of
 *     killing the connection. iXcoin Core 0.14 is a Bitcoin Core 0.14 fork and
 *     sends `sendcmpct` and `feefilter` right after the handshake. bitcoinj
 *     wraps unknown commands in UnknownMessage, whose superclass assigns
 *     `length = 0` only *after* Message's constructor has already validated it
 *     — so any unknown message with a non-empty payload throws and the peer is
 *     disconnected. Dropping them is correct: none are required by an SPV client.
 */
class IxcoinSerializer(
    private val netParams: NetworkParameters,
    parseRetain: Boolean
) : BitcoinSerializer(netParams, parseRetain) {

    /** A no-op message handed back for commands we deliberately ignore. */
    private class IgnoredMessage(params: NetworkParameters) : EmptyMessage(params)

    @Throws(ProtocolException::class)
    override fun makeBlock(payloadBytes: ByteArray, offset: Int, length: Int): Block =
        IxcoinBlock(netParams, payloadBytes, offset, null as Message?, this, length)

    /**
     * `merkleblock` also carries the AuxPoW proof between the header and the
     * partial merkle tree, and this is the message an SPV wallet actually
     * receives once it has a bloom filter set — so it matters more than `block`.
     *
     * bitcoinj's FilteredBlock reads the tree immediately after the 80-byte
     * header. On a merged-mined chain that lands inside the proof, reads a
     * nonsense varint, throws "Claimed value length too large", and the peer is
     * dropped. The wallet then looks like it has a flaky network: peers climb
     * to three or four, collapse to zero, and the sync never advances.
     *
     * The proof is measured and removed so bitcoinj sees the layout it expects.
     */
    @Throws(ProtocolException::class, BufferUnderflowException::class)
    override fun deserializePayload(header: BitcoinPacketHeader, `in`: ByteBuffer): Message {
        if (header.command in IGNORED_COMMANDS) {
            val payload = ByteArray(header.size)
            `in`.get(payload, 0, header.size)
            val hash = Sha256Hash.hashTwice(payload)
            if (header.checksum[0] != hash[0] || header.checksum[1] != hash[1] ||
                header.checksum[2] != hash[2] || header.checksum[3] != hash[3]
            ) {
                throw ProtocolException("Checksum failed to verify for '${header.command}'")
            }
            return IgnoredMessage(netParams)
        }
        return super.deserializePayload(header, `in`)
    }

    @Throws(ProtocolException::class)
    override fun makeFilteredBlock(payloadBytes: ByteArray): FilteredBlock {
        val version = Utils.readUint32(payloadBytes, 0)
        if (!AuxPoW.hasAuxPoW(version)) return super.makeFilteredBlock(payloadBytes)

        // Build the FilteredBlock by hand. FilteredBlock.parse() copies exactly
        // 80 header bytes and then reads the partial merkle tree from directly
        // after them — on a merged-mined chain that is the middle of the AuxPoW
        // proof. Letting it do that throws "Claimed value length too large" and
        // the peer is dropped, which reads as a flaky network rather than a
        // parser bug.
        val proof = AuxPoW.parse(netParams, payloadBytes, Block.HEADER_SIZE)
        val headerBytes = payloadBytes.copyOfRange(0, Block.HEADER_SIZE)
        val header = makeBlock(headerBytes, 0, headerBytes.size) as IxcoinBlock
        // Reattach the proof: without it the header cannot have its work
        // verified, since for a merged-mined block the work is on the parent
        // chain and its own hash is expected to be above the target.
        header.attachAuxPoW(proof)

        val tree = PartialMerkleTree(
            netParams, payloadBytes, Block.HEADER_SIZE + proof.messageSize
        )
        return FilteredBlock(netParams, header, tree)
    }

    companion object {
        /**
         * Post-handshake chatter from a Bitcoin Core 0.14-era node that an SPV
         * client neither needs nor answers.
         */
        val IGNORED_COMMANDS: Set<String> = setOf(
            "sendcmpct",    // BIP152 compact blocks
            "feefilter",    // BIP133 minimum relay fee
            "cmpctblock",
            "getblocktxn",
            "blocktxn",
            "xversion",
            "xverack"
        )
    }
}
