package net.ixcoin.wallet.core

import org.bitcoinj.core.AbstractBlockChain
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Peer
import org.bitcoinj.core.PeerGroup

/**
 * A PeerGroup that will actually pick an iXcoin node to sync from.
 *
 * bitcoinj 0.15's [PeerGroup.selectDownloadPeer] skips any peer that does not
 * advertise NODE_WITNESS. SegWit was never activated on iXcoin — its nodes
 * advertise NODE_NETWORK|NODE_BLOOM and nothing else — so stock bitcoinj
 * rejects every candidate, logs "no clear candidate", and never starts the
 * chain download. This drops the witness requirement and otherwise keeps
 * bitcoinj's selection rules: a peer must be at the height the network agrees
 * on, and must serve the block chain.
 */
class IxcoinPeerGroup(
    params: NetworkParameters,
    chain: AbstractBlockChain?
) : PeerGroup(params, chain) {

    override fun selectDownloadPeer(peers: List<Peer>): Peer? {
        if (peers.isEmpty()) return null

        val mostCommonChainHeight = getMostCommonChainHeight(peers)
        if (mostCommonChainHeight == 0) return null

        val candidates = peers.filter { peer ->
            val version = peer.peerVersionMessage ?: return@filter false
            // NB: no isWitnessSupported() check — see the class comment.
            if (!version.hasBlockChain()) return@filter false
            val height = peer.bestHeight
            height >= mostCommonChainHeight && height <= mostCommonChainHeight + 1
        }
        if (candidates.isEmpty()) return null

        // Spread the load rather than always hammering the first peer.
        return candidates[(Math.random() * candidates.size).toInt()]
    }
}
