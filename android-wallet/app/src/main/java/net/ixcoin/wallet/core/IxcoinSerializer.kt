package net.ixcoin.wallet.core

import org.bitcoinj.core.BitcoinSerializer
import org.bitcoinj.core.Block
import org.bitcoinj.core.EmptyMessage
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
