package net.ixcoin.wallet.wallet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.ixcoin.wallet.core.IxcoinMainNetParams
import net.ixcoin.wallet.core.IxcoinPeerGroup
import net.ixcoin.wallet.security.WalletLock
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

    /** Peer addresses the user added; kept on the device, like everything else. */
    private val prefs by lazy {
        appContext.getSharedPreferences("ixcoin_peers", Context.MODE_PRIVATE)
    }

    private val log = org.slf4j.LoggerFactory.getLogger(IxcoinWalletService::class.java)

    private val params = IxcoinMainNetParams.get()
    private var kit: WalletAppKit? = null

    /** Encryption state and the in-memory spending key. See WalletLock. */
    val lock = WalletLock()

    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    data class WalletUiState(
        val started: Boolean = false,
        val syncing: Boolean = true,
        val syncProgress: Int = 0,
        val chainHeight: Int = 0,
        val blocksLeft: Int = 0,
        val peers: Int = 0,
        val peerRows: List<PeerRow> = emptyList(),
        val available: Coin = Coin.ZERO,
        val pending: Coin = Coin.ZERO,
        val receiveAddress: String = "",
        val encrypted: Boolean = false,
        val locked: Boolean = true,
        val transactions: List<TxRow> = emptyList(),
        val error: String? = null,
        /**
         * Set only when the wallet on disk does not match the seed the user
         * supplied. Nothing about the wallet can be trusted in that state, so
         * the UI must warn rather than show a balance or an address.
         */
        val fatalError: String? = null
    ) {
        val total: Coin get() = available.add(pending)
    }

    /** One connected peer, as shown on the Peers screen. */
    data class PeerRow(
        val address: String,
        val subVer: String,
        val height: Long,
        val userAdded: Boolean
    )

    data class TxRow(
        val txId: String,
        val amount: Coin,
        val confirmations: Int,
        val time: Date?,
        val incoming: Boolean,
        val counterparty: String?
    )

    private var heartbeat: java.util.Timer? = null
    private var lastStatusLog = 0L

    /** Set by [createWallet]/[restoreWallet] and consumed by the next [start]. */
    private var pendingSeed: org.bitcoinj.wallet.DeterministicSeed? = null
    private var pendingPassphrase: CharArray? = null

    /**
     * True once a wallet file exists on disk.
     *
     * The kit creates a random wallet the moment it starts, so onboarding has
     * to run *before* anything calls [start] — otherwise the generated seed
     * would be discarded in favour of one the user never saw.
     */
    /** True once [start] has built the kit, whether or not it is RUNNING yet. */
    val isStarted: Boolean get() = kit != null

    fun hasWallet(): Boolean =
        File(File(appContext.filesDir, "spv"), "$WALLET_PREFIX.wallet").exists()

    /**
     * Create the wallet from a freshly generated seed and lock it with
     * [passphrase]. The passphrase is applied before the first save, so the
     * wallet file is never written to disk unencrypted.
     */
    fun createWallet(seed: org.bitcoinj.wallet.DeterministicSeed, passphrase: CharArray) {
        pendingSeed = seed
        pendingPassphrase = passphrase

        // WalletAppKit honours a supplied seed ONLY when it decides to create a
        // wallet. If a wallet file is already on disk it loads that instead and
        // silently discards the seed -- so the user types a recovery phrase,
        // sees the previous wallet's addresses, and believes those addresses
        // belong to the phrase they just entered. They do not, and coins sent
        // there would not be recoverable from that phrase. Tear the kit down
        // and remove the old files so the seed is actually the one used.
        stop()
        net.ixcoin.wallet.seed.SeedManager.clearExisting(
            File(appContext.filesDir, "spv"), WALLET_PREFIX)

        start()
    }

    /** Same, for a seed the user is restoring from their recovery phrase. */
    fun restoreWallet(seed: org.bitcoinj.wallet.DeterministicSeed, passphrase: CharArray) =
        createWallet(seed, passphrase)

    fun start() {
        // A pending seed must always win: returning early here meant a restore
        // requested while the app was already running was a silent no-op.
        if (kit != null && pendingSeed == null) return
        if (kit != null) stop()
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

                // Belt and braces for the worst failure this app can have: the
                // wallet on screen not being the one the user's phrase builds.
                // Deleting the old files should make this impossible, but if it
                // ever is possible again the app must say so rather than show
                // addresses the recovery phrase does not control.
                pendingSeed?.let { requested ->
                    val actual = wallet().keyChainSeed?.mnemonicCode
                    if (actual != null && actual != requested.mnemonicCode) {
                        log.error("wallet does not match the seed that was supplied; " +
                            "refusing to present it")
                        _state.value = _state.value.copy(
                            fatalError = "This wallet does not match the recovery phrase " +
                                "you entered. Do not send coins to it. Reinstall the app " +
                                "and restore again."
                        )
                    }
                }
                wallet().addCoinsReceivedEventListener { _, _, _, _ -> publish() }
                wallet().addCoinsSentEventListener { _, _, _, _ -> publish() }
                wallet().addChangeEventListener { publish() }
                peerGroup().addConnectedEventListener { _, _ -> publish() }
                peerGroup().addDisconnectedEventListener { _, _ -> publish() }
                // bitcoinj elects a download peer in exactly one place —
                // handleNewPeer, behind `connected > maxConnections / 2` — and
                // nowhere else re-runs that election from scratch. So the
                // target has to be low enough that the condition can actually
                // become true with the number of peers this network gives us.
                //
                // At 4 it needs 3 peers. iXcoin has two reachable IPv4 nodes
                // (the seed crawl's other entries are IPv6, which most mobile
                // networks cannot reach), so only 2 ever connected, 2 > 2 was
                // false, and no download peer was ever chosen: peers healthy,
                // nothing thrown, height frozen forever. At 2 the second peer
                // opens the gate.
                peerGroup().maxConnections = 2

                // Encrypt before the first autosave so the wallet file only
                // ever exists on disk in its locked form.
                pendingPassphrase?.let { pass ->
                    if (!wallet().isEncrypted) {
                        runCatching { lock.encrypt(wallet(), pass) }
                            .onFailure { log.error("could not encrypt new wallet", it) }
                    }
                    pass.fill('\u0000')
                }
                pendingPassphrase = null
                pendingSeed = null
                publish()
            }
        }

        // Without checkpoints a new wallet starts at the genesis block and has
        // to pull about a million headers before it is usable. With them it
        // jumps to the last checkpoint at or before the wallet's creation time.
        runCatching { k.setCheckpoints(appContext.assets.open("checkpoints.txt")) }
            .onFailure { log.warn("checkpoints unavailable, syncing from genesis: {}", it.toString()) }

        // Must precede startAsync(): the kit only honours a supplied seed while
        // it is deciding whether to create or load a wallet.
        pendingSeed?.let { k.restoreWalletFromSeed(it) }
        k.setBlockingStartup(false)
        k.setUserAgent(USER_AGENT, VERSION)
        k.setAutoSave(true)
        // Use discovery rather than setPeerNodes(): the latter pins the peer
        // list and switches discovery off entirely ("0 discoverers" in the
        // logs), so when one of the handful of seeds drops there is nothing to
        // fall back on and the download stalls. As a discovery source the seeds
        // still bootstrap us, but bitcoinj also learns further peers from addr
        // gossip and can replace ones that die.
        k.setDiscovery(object : org.bitcoinj.net.discovery.PeerDiscovery {
            override fun getPeers(services: Long, timeout: Long, unit: java.util.concurrent.TimeUnit) =
                seedSocketAddresses()
            override fun shutdown() {}
        })
        k.setDownloadListener(object : org.bitcoinj.core.listeners.DownloadProgressTracker() {
            override fun progress(pct: Double, blocksLeft: Int, date: Date?) {
                // Carry the height through too: publish() only runs on wallet
                // and peer events, so during a long header download nothing
                // else would ever update it and the UI sat at "Block height 0"
                // while the chain was in fact downloading.
                _state.value = _state.value.copy(
                    syncing = true,
                    syncProgress = pct.toInt(),
                    chainHeight = kit?.chain()?.bestChainHeight ?: _state.value.chainHeight,
                    blocksLeft = blocksLeft,
                )
            }

            override fun doneDownload() {
                _state.value = _state.value.copy(syncing = false, syncProgress = 100, blocksLeft = 0)
                publish()
            }
        })

        kit = k
        // bitcoinj emits no event per header, so without this the UI would show
        // a stale height for the whole download.
        heartbeat = java.util.Timer("ixcoin-state", true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    runCatching { publish() }
                    runCatching { logStatus() }
                }
            }, 2_000L, 2_000L)
        }
        log.info("starting SPV kit in {}", dir)
        k.addListener(object : com.google.common.util.concurrent.Service.Listener() {
            override fun running() {
                _state.value = _state.value.copy(started = true)
                publish()
            }

            override fun failed(from: com.google.common.util.concurrent.Service.State, failure: Throwable) {
                log.error("wallet kit failed from state {}", from, failure)
                _state.value = _state.value.copy(error = failure.message ?: failure.toString())
            }
        }, Runnable::run)
        k.startAsync()
    }

    /**
     * Shut the SPV stack down. Only the foreground service should call this —
     * a ViewModel being cleared on a rotation must not stop the sync.
     */
    fun stop() {
        heartbeat?.cancel(); heartbeat = null
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
        if (w.isEncrypted) {
            req.aesKey = lock.aesKeyOrNull()
                ?: throw IllegalStateException("Wallet is locked. Unlock it to send.")
        }
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
            chainHeight = chain?.bestChainHeight ?: _state.value.chainHeight,
            peers = kit?.peerGroup()?.numConnectedPeers() ?: 0,
            peerRows = peerRows(),
            available = w.getBalance(Wallet.BalanceType.AVAILABLE),
            pending = w.getBalance(Wallet.BalanceType.ESTIMATED)
                .subtract(w.getBalance(Wallet.BalanceType.AVAILABLE)),
            receiveAddress = w.currentReceiveAddress().toString(),
            encrypted = w.isEncrypted,
            locked = w.isEncrypted && lock.aesKeyOrNull() == null,
            transactions = txs
        )
    }

    /**
     * A once-a-minute line of sync state. Without it a stalled chain is
     * invisible: peers stay connected and nothing throws, so the log looks
     * healthy while the height never moves.
     */
    private fun logStatus() {
        val now = System.currentTimeMillis()
        if (now - lastStatusLog < 30_000L) return
        lastStatusLog = now
        val k = kit
        val st = _state.value
        val height = runCatching { k?.chain()?.bestChainHeight }.getOrNull()
        val peers = runCatching { k?.peerGroup()?.numConnectedPeers() }.getOrNull()
        android.util.Log.i(
            "ixcoin-status",
            "kit=${runCatching { k?.state()?.toString() }.getOrNull()} " +
                "height=${height ?: st.chainHeight} peers=${peers ?: st.peers} " +
                "progress=${st.syncProgress}% left=${st.blocksLeft} " +
                "wallet=${wallet != null}"
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

    private fun peerRows(): List<IxcoinWalletService.PeerRow> = runCatching {
        val user = userPeers()
        kit?.peerGroup()?.connectedPeers.orEmpty().map { p ->
            val a = p.address
            val hostPort = "${a.addr?.hostAddress ?: a.hostname}:${a.port}"
            IxcoinWalletService.PeerRow(
                address = hostPort,
                subVer = p.peerVersionMessage?.subVer ?: "",
                height = p.bestHeight,
                userAdded = hostPort in user
            )
        }.sortedBy { it.address }
    }.getOrDefault(emptyList())

    /** Peer addresses the user typed in, as "host:port". */
    fun userPeers(): Set<String> =
        prefs.getStringSet(KEY_USER_PEERS, emptySet())!!.toSortedSet()

    /**
     * Add a peer by "host" or "host:port". Returns null on success, or a
     * message to show the user. The address is resolved first so a typo is
     * reported straight away rather than becoming a silent no-op.
     */
    fun addPeer(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return "Enter an address."
        val (host, port) = splitHostPort(text) ?: return "Could not read that address."
        val resolved = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return "$host could not be resolved."
        }
        val entry = "${resolved.hostAddress}:$port"
        prefs.edit().putStringSet(KEY_USER_PEERS, userPeers() + entry).apply()
        // Nudge the group to try it now rather than at the next discovery pass.
        runCatching {
            kit?.peerGroup()?.addAddress(PeerAddress(params, InetSocketAddress(resolved, port)))
        }
        publish()
        return null
    }

    fun removePeer(entry: String) {
        prefs.edit().putStringSet(KEY_USER_PEERS, userPeers() - entry).apply()
        publish()
    }

    /** "1.2.3.4", "1.2.3.4:8337", "[::1]:8337" and "::1" all parse. */
    private fun splitHostPort(text: String): Pair<String, Int>? {
        if (text.startsWith("[")) {                       // bracketed IPv6
            val close = text.indexOf(']')
            if (close < 0) return null
            val host = text.substring(1, close)
            val port = text.substring(close + 1).removePrefix(":")
            return host to (port.toIntOrNull() ?: DEFAULT_PORT)
        }
        // A bare IPv6 literal has several colons; only treat the last one as a
        // port separator when there is exactly one.
        val colons = text.count { it == ':' }
        if (colons == 1) {
            val (h, p) = text.split(":")
            val port = p.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return h to port
        }
        return text to DEFAULT_PORT
    }

    private fun seedSocketAddresses(): Array<InetSocketAddress> {
        // In full-node mode the local daemon is the only peer we want: the
        // point of running it is that this device validates the chain itself
        // and no stranger learns which addresses we are watching. Fall through
        // to the public seeds if it is not up yet, so the wallet still works
        // while the node is starting or if it failed.
        if (net.ixcoin.wallet.node.NodeMode.current(appContext) ==
            net.ixcoin.wallet.node.NodeMode.Full &&
            net.ixcoin.wallet.node.FullNode.isRunning
        ) {
            log.info("full-node mode: syncing from the local daemon only")
            return arrayOf(
                InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"),
                    net.ixcoin.wallet.node.NodeMode.P2P_PORT
                )
            )
        }

        val out = SEED_NODES.mapNotNull { (host, port) ->
            try {
                InetSocketAddress(InetAddress.getByName(host), port)
            } catch (e: Exception) {
                log.warn("seed peer {}:{} unusable: {}", host, port, e.toString())
                null
            }
        }
        val extra = userPeers().mapNotNull { entry ->
            val (h, p) = splitHostPort(entry) ?: return@mapNotNull null
            try { InetSocketAddress(InetAddress.getByName(h), p) } catch (e: Exception) { null }
        }
        val all = (out + extra).distinct()
        log.info("offering {} seed + {} user peers to discovery", out.size, extra.size)
        if (all.isEmpty()) {
            _state.value = _state.value.copy(error = "No reachable seed nodes could be resolved.")
        }
        return all.toTypedArray()
    }

    companion object {

        /**
         * One SPV stack per process.
         *
         * The foreground service and the UI both need it, and two WalletAppKits
         * over the same files would race on the wallet and the chain store.
         */
        @Volatile private var INSTANCE: IxcoinWalletService? = null

        fun get(context: Context): IxcoinWalletService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: IxcoinWalletService(context.applicationContext).also { INSTANCE = it }
            }

        const val WALLET_PREFIX = "ixcoin"
        // BIP14 subver components may not contain "/", ":", "(" or ")".
        // bitcoinj rejects them outright, which aborts PeerGroup startup.
        const val USER_AGENT = "iXcoin Wallet Android"
        const val VERSION = "1.0.0"

        /**
         * Bootstrap nodes. The project's DNS seeds (uk/nyc/sgp.ixcoin.co) stopped
         * resolving, so these were taken from a live crawl of the network.
         */
        // Only nodes a live crawl actually answered on. Dead entries make
        // bitcoinj churn reconnect attempts, since setPeerNodes() disables
        // discovery and it has nothing else to try.
        const val DEFAULT_PORT = 8337
        private const val KEY_USER_PEERS = "user_peers"

        val SEED_NODES: List<Pair<String, Int>> = listOf(
            "18.217.178.46" to 8337,
            "91.121.45.149" to 8337,
            "2600:1702:7860:6090::48" to 8337
        )
    }
}
