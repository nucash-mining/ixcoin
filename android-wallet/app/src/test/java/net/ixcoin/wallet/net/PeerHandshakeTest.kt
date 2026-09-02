package net.ixcoin.wallet.net

import net.ixcoin.wallet.core.IxcoinMainNetParams
import org.bitcoinj.core.BlockChain
import org.bitcoinj.core.Context
import org.bitcoinj.core.Peer
import org.bitcoinj.core.PeerAddress
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.store.MemoryBlockStore
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Date

/** Diagnostic: connect one peer and report what actually happens. */
class PeerHandshakeTest {

    private val params = IxcoinMainNetParams.get()

    @Before
    fun setUp() { Context.propagate(Context(params)) }

    @Test
    fun diagnose() {
        val host = "18.217.178.46"; val port = 8337
        val reachable = runCatching {
            java.net.Socket().use { it.connect(InetSocketAddress(host, port), 8000) }; true
        }.getOrDefault(false)
        Assume.assumeTrue("node unreachable", reachable)

        val store = MemoryBlockStore(params)
        val chain = BlockChain(params, store)
        println("genesis      = ${params.genesisBlock.hashAsString}")
        println("chain head   = ${chain.chainHead.header.hashAsString} h=${chain.bestChainHeight}")

        val peers = net.ixcoin.wallet.core.IxcoinPeerGroup(params, chain)
        peers.maxConnections = 1
        peers.setUserAgent("ixcoin-diag", "1.0")
        peers.addAddress(PeerAddress(params, InetSocketAddress(InetAddress.getByName(host), port)))
        peers.addConnectedEventListener { p: Peer, _ ->
            println("CONNECTED ${p.peerVersionMessage?.subVer} " +
                    "proto=${p.peerVersionMessage?.clientVersion} " +
                    "height=${p.peerVersionMessage?.bestHeight}")
        }
        peers.addDisconnectedEventListener { p: Peer, count ->
            println("DISCONNECTED ${p.address} remaining=$count")
        }
        try {
            peers.start()
            peers.startBlockChainDownload(object : DownloadProgressTracker() {
                override fun progress(pct: Double, left: Int, date: Date?) {
                    println("progress ${"%.4f".format(pct)}%% left=$left")
                }
                override fun doneDownload() { println("doneDownload") }
            })
            repeat(12) {
                Thread.sleep(5000)
                println("t=${(it + 1) * 5}s height=${chain.bestChainHeight} peers=${peers.numConnectedPeers()}")
                if (chain.bestChainHeight > 5000) return@repeat
            }
            println("FINAL height=${chain.bestChainHeight}")
        } finally {
            runCatching { peers.stop() }
        }
    }
}
