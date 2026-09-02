package net.ixcoin.wallet.wallet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.ixcoin.wallet.core.IxcoinMainNetParams
import net.ixcoin.wallet.core.IxcoinPeerGroup
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.PeerAddress
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.Transaction
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Date

/**
 * Owns the SPV stack: header chain, peer group and wallet.
 *
 * iXcoin's DNS seeds no longer resolve, so the peer group is bootstrapped from
 * a hard-coded list of nodes that were reachable when this build was made, and
 * grows from there via addr gossip.
 */
class IxcoinWalletService(private val appContext: Context) {

    private val params = IxcoinMainNetParams.get()
    private var kit: WalletAppKit? = null

    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    data class WalletUiState(
        val started: Boolean = false,
        val syncing: Boolean = true,
        val syncProgress: Int = 0,
        val chainHeight: Int = 0,
        val peers: Int = 0,
        val available: Coin = Coin.ZERO,
        val pending: Coin = Coin.ZERO,
        val receiveAddress: String = "",
        val transactions: List<TxRow> = emptyList(),
        val error: String? = null
    ) {
        val total: Coin get() = available.add(pending)
    }

    data class TxRow(
        val txId: String,
        val amount: Coin,
        val confirmations: Int,
        val time: Date?,
        val incoming: Boolean,
        val counterparty: String?
    )

    fun start() {
        if (kit != null) return
        val dir = File(appContext.filesDir, "spv")
        if (!dir.exists()) dir.mkdirs()

        val k = object : WalletAppKit(params, dir, WALLET_PREFIX) {
            // iXcoin nodes do not advertise NODE_WITNESS, which stock bitcoinj
            // treats as disqualifying for a download peer. See IxcoinPeerGroup.
            override fun createPeerGroup(): PeerGroup =
                IxcoinPeerGroup(params, vChain)

            override fun onSetupCompleted() {
                // A brand-new wallet has no keys until this point.
                if (wallet().importedKeys.isEmpty() && wallet().activeKeyChain == null) {
                    wallet().freshReceiveAddress()
                }
                wallet().addCoinsReceivedEventListener { _, _, _, _ -> publish() }
                wallet().addCoinsSentEventListener { _, _, _, _ -> publish() }
                wallet().addChangeEventListener { publish() }
                peerGroup().addConnectedEventListener { _, _ -> publish() }
                peerGroup().addDisconnectedEventListener { _, _ -> publish() }
                peerGroup().maxConnections = 6
                publish()
            }
        }

        k.setBlockingStartup(false)
        k.setUserAgent(USER_AGENT, VERSION)
        k.setAutoSave(true)
        k.setPeerNodes(*seedPeers())
        k.setDownloadListener(object : org.bitcoinj.core.listeners.DownloadProgressTracker() {
            override fun progress(pct: Double, blocksLeft: Int, date: Date?) {
                _state.value = _state.value.copy(syncing = true, syncProgress = pct.toInt())
            }

            override fun doneDownload() {
                _state.value = _state.value.copy(syncing = false, syncProgress = 100)
                publish()
            }
        })

        kit = k
        k.addListener(object : com.google.common.util.concurrent.Service.Listener() {
            override fun running() {
                _state.value = _state.value.copy(started = true)
                publish()
            }

            override fun failed(from: com.google.common.util.concurrent.Service.State, failure: Throwable) {
                _state.value = _state.value.copy(error = failure.message ?: failure.toString())
            }
        }, Runnable::run)
        k.startAsync()
    }

    fun stop() {
        kit?.let { runCatching { it.stopAsync().awaitTerminated() } }
        kit = null
    }

    val wallet: Wallet? get() = kit?.takeIf { it.isRunning }?.wallet()

    fun freshReceiveAddress(): String? =
        wallet?.freshReceiveAddress()?.toString()?.also { publish() }

    /** The BIP39 recovery words, for the backup screen. */
    fun mnemonic(): List<String>? =
        wallet?.keyChainSeed?.mnemonicCode

    fun seedCreationTime(): Long? = wallet?.keyChainSeed?.creationTimeSeconds

    fun isValidAddress(address: String): Boolean = runCatching {
        Address.fromString(params, address)
    }.isSuccess

    /**
     * Build, sign and broadcast a payment.
     * @return the transaction id, or throws with a human-readable reason.
     */
    @Throws(InsufficientMoneyException::class)
    fun send(toAddress: String, amount: Coin, feePerKb: Coin? = null, emptyWallet: Boolean = false): String {
        val w = wallet ?: throw IllegalStateException("Wallet is not running yet")
        val pg = kit?.peerGroup() ?: throw IllegalStateException("Not connected")
        val dest = Address.fromString(params, toAddress)
        val req = if (emptyWallet) SendRequest.emptyWallet(dest) else SendRequest.to(dest, amount)
        feePerKb?.let { req.feePerKb = it }
        val result = w.sendCoins(pg, req)
        publish()
        return result.tx.txId.toString()
    }

    /** Fee the wallet would pay for this payment, without broadcasting it. */
    fun estimateFee(toAddress: String, amount: Coin, feePerKb: Coin?): Coin? = runCatching {
        val w = wallet ?: return null
        val req = SendRequest.to(Address.fromString(params, toAddress), amount)
        feePerKb?.let { req.feePerKb = it }
        w.completeTx(req)
        val fee = req.tx.fee
        // completeTx marks the coins as spent candidates; undo that.
        w.unsignedTxSize(req)
        fee
    }.getOrNull()

    private fun Wallet.unsignedTxSize(req: SendRequest) {
        // completeTx() does not commit, so nothing to roll back; kept as a hook
        // in case a future bitcoinj version changes that.
    }

    private fun publish() {
        val w = wallet ?: return
        val chain = kit?.chain()
        val txs = w.getTransactions(false)
            .sortedByDescending { it.updateTime?.time ?: 0L }
            .take(200)
            .map { tx ->
                val value = tx.getValue(w)
                IxcoinWalletService.TxRow(
                    txId = tx.txId.toString(),
                    amount = value,
                    confirmations = tx.confidence?.depthInBlocks ?: 0,
                    time = tx.updateTime,
                    incoming = value.isPositive,
                    counterparty = counterpartyOf(tx, w, value.isPositive)
                )
            }
        _state.value = _state.value.copy(
            chainHeight = chain?.bestChainHeight ?: 0,
            peers = kit?.peerGroup()?.numConnectedPeers() ?: 0,
            available = w.getBalance(Wallet.BalanceType.AVAILABLE),
            pending = w.getBalance(Wallet.BalanceType.ESTIMATED)
                .subtract(w.getBalance(Wallet.BalanceType.AVAILABLE)),
            receiveAddress = w.currentReceiveAddress().toString(),
            transactions = txs
        )
    }

    private fun counterpartyOf(tx: Transaction, w: Wallet, incoming: Boolean): String? = runCatching {
        if (incoming) {
            tx.outputs.firstOrNull { it.isMine(w) }?.getScriptPubKey()
                ?.getToAddress(params, true)?.toString()
        } else {
            tx.outputs.firstOrNull { !it.isMine(w) }?.getScriptPubKey()
                ?.getToAddress(params, true)?.toString()
        }
    }.getOrNull()

    private fun seedPeers(): Array<PeerAddress> =
        SEED_NODES.mapNotNull { (host, port) ->
            runCatching { PeerAddress(params, InetSocketAddress(InetAddress.getByName(host), port)) }
                .getOrNull()
        }.toTypedArray()

    companion object {
        const val WALLET_PREFIX = "ixcoin"
        const val USER_AGENT = "iXcoin Wallet (Android)"
        const val VERSION = "1.0.0"

        /**
         * Bootstrap nodes. The project's DNS seeds (uk/nyc/sgp.ixcoin.co) stopped
         * resolving, so these were taken from a live crawl of the network.
         */
        val SEED_NODES: List<Pair<String, Int>> = listOf(
            "18.217.178.46" to 8337,
            "91.121.45.149" to 8337,
            "64.71.72.56" to 8337,
            "2600:1702:7860:6090::48" to 8337,
            "2a0d:c2c0:1:17:be24:11ff:feb0:9f6a" to 5007
        )
    }
}
