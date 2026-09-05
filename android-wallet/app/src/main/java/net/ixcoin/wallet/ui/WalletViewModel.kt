package net.ixcoin.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ixcoin.wallet.security.SecurityStore
import net.ixcoin.wallet.security.Totp
import net.ixcoin.wallet.wallet.IxcoinWalletService
import org.bitcoinj.core.Coin

class WalletViewModel(app: Application) : AndroidViewModel(app) {

    private val service = IxcoinWalletService.get(app.applicationContext)

    // ---- full node vs light -----------------------------------------------

    private val app = getApplication<Application>()

    fun nodeMode(): net.ixcoin.wallet.node.NodeMode =
        net.ixcoin.wallet.node.NodeMode.current(app)

    fun fullNodeSupported(): Boolean =
        net.ixcoin.wallet.node.NodeMode.fullNodeSupported(app)

    fun fullNodeRunning(): Boolean = net.ixcoin.wallet.node.FullNode.isRunning

    /**
     * Switch between a local full node and light (SPV) mode.
     *
     * Returns null on success, or a message to show. Restarting the sync stack
     * is what actually moves the wallet onto — or off — the local peer, since
     * the peer list is only consulted when discovery runs.
     */
    fun setNodeMode(mode: net.ixcoin.wallet.node.NodeMode): String? {
        net.ixcoin.wallet.node.NodeMode.set(app, mode)
        return if (mode == net.ixcoin.wallet.node.NodeMode.Full) {
            net.ixcoin.wallet.node.FullNode.start(app)
        } else {
            net.ixcoin.wallet.node.FullNode.stop()
            null
        }
    }

    // ---- first-run wallet creation ----------------------------------------

    /** False on a fresh install, which is what puts the user into onboarding. */
    fun hasWallet(): Boolean = service.hasWallet()

    /**
     * Create the wallet from gesture-derived entropy and lock it with
     * [passphrase]. The caller keeps the words to show the user; nothing about
     * the seed leaves the device.
     */
    fun createWallet(seed: org.bitcoinj.wallet.DeterministicSeed, passphrase: CharArray) {
        service.createWallet(seed, passphrase)
    }

    fun restoreWallet(seed: org.bitcoinj.wallet.DeterministicSeed, passphrase: CharArray) {
        service.restoreWallet(seed, passphrase)
    }

    /** Peer addresses the user has added, as "host:port". */
    fun userPeers(): Set<String> = service.userPeers()

    /** Returns null on success, or a message to show the user. */
    fun addPeer(address: String): String? = service.addPeer(address)

    fun removePeer(address: String) = service.removePeer(address)

    val security = SecurityStore(app.applicationContext)
    val lock get() = service.lock

    private val _gateOpen = MutableStateFlow(!security.anyLockEnabled)
    /** False while the app-level gate (PIN / authenticator) is unsatisfied. */
    val gateOpen: StateFlow<Boolean> = _gateOpen

    private val _securityError = MutableStateFlow<String?>(null)
    val securityError: StateFlow<String?> = _securityError

    val state: StateFlow<IxcoinWalletService.WalletUiState> =
        service.state.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            IxcoinWalletService.WalletUiState()
        )

    private val _sendResult = MutableStateFlow<SendResult?>(null)
    val sendResult: StateFlow<SendResult?> = _sendResult

    sealed interface SendResult {
        data class Sent(val txId: String) : SendResult
        data class Failed(val reason: String) : SendResult
    }

    init {
        // The foreground service owns the lifecycle; this is a no-op if it has
        // already started the stack.
        //
        // Never before onboarding, though: the ViewModel is constructed before
        // the first-run check runs, and WalletAppKit creates a random wallet
        // the instant it starts — which discards the seed the user is about to
        // generate, without either of us noticing.
        if (service.hasWallet()) service.start()
    }

    fun newReceiveAddress() = viewModelScope.launch(Dispatchers.IO) {
        service.freshReceiveAddress()
    }

    fun mnemonic(): List<String>? = service.mnemonic()

    fun isValidAddress(a: String) = service.isValidAddress(a)

    fun send(address: String, amount: Coin, emptyWallet: Boolean = false) =
        viewModelScope.launch {
            _sendResult.value = withContext(Dispatchers.IO) {
                try {
                    SendResult.Sent(service.send(address, amount, emptyWallet = emptyWallet))
                } catch (e: Exception) {
                    SendResult.Failed(e.message ?: e::class.java.simpleName)
                }
            }
        }

    fun clearSendResult() { _sendResult.value = null }

    // ---- security ---------------------------------------------------------

    fun encryptWallet(passphrase: String) = viewModelScope.launch(Dispatchers.IO) {
        val w = service.wallet ?: return@launch
        val chars = passphrase.toCharArray()
        runCatching { lock.encrypt(w, chars); lastPassphrase = chars }
            .onFailure { _securityError.value = it.message }
    }

    fun changePassphrase(old: String, new: String) = viewModelScope.launch(Dispatchers.IO) {
        val w = service.wallet ?: return@launch
        runCatching { lock.changePassphrase(w, old.toCharArray(), new.toCharArray()) }
            .onFailure { _securityError.value = "Could not change passphrase: ${it.message}" }
    }

    fun unlockWithPassphrase(passphrase: String) = viewModelScope.launch(Dispatchers.IO) {
        val w = service.wallet ?: return@launch
        val chars = passphrase.toCharArray()
        val ok = runCatching { lock.unlock(w, chars) }.getOrDefault(false)
        _securityError.value = if (ok) null else "Wrong passphrase."
        if (ok) { _gateOpen.value = true; lastPassphrase = chars } else chars.fill('\u0000')
    }

    fun lockNow() { lock.lock() }

    fun verifyPin(pin: String) {
        if (security.verifyPin(pin)) { _gateOpen.value = true; _securityError.value = null }
        else _securityError.value = "Wrong PIN."
    }

    fun setPin(pin: String?) {
        if (pin == null) security.clearPin() else security.setPin(pin)
    }

    fun verifyTotp(code: String) {
        val secret = security.totpSecret()
        if (secret != null && Totp.verify(secret, code)) {
            _gateOpen.value = true; _securityError.value = null
        } else _securityError.value = "That code is not valid."
    }

    /**
     * Begin 2FA setup: mint a secret and hand back what the UI needs to show.
     * Deliberately does NOT switch 2FA on — that only happens once the user has
     * typed back a working code, so a mis-scanned QR cannot lock them out.
     */
    data class TotpSetup(val uri: String, val secretBase32: String)

    fun beginTotpSetup(): TotpSetup {
        val secret = Totp.newSecret()
        pendingTotpSecret = secret
        val label = state.value.receiveAddress.take(12).ifEmpty { "wallet" }
        return TotpSetup(Totp.provisioningUri(secret, label), Totp.base32(secret))
    }

    /** Confirm setup. Returns false if the code does not match. */
    fun confirmTotpSetup(code: String): Boolean {
        val secret = pendingTotpSecret ?: return false
        if (!Totp.verify(secret, code)) return false
        security.setTotpSecret(secret)
        security.totpEnabled = true
        pendingTotpSecret = null
        return true
    }

    fun cancelTotpSetup() { pendingTotpSecret = null }

    @Volatile private var pendingTotpSecret: ByteArray? = null

    fun disableTotp() = security.clearTotp()

    /**
     * Seal the current spending passphrase behind the biometric-gated keystore
     * key. Only possible while unlocked, since that is when we hold the key.
     */
    fun armBiometric(cipher: javax.crypto.Cipher) {
        val pass = lastPassphrase
        if (pass == null) {
            _securityError.value = "Unlock with your passphrase first, then enable biometrics."
            return
        }
        runCatching { security.sealPassphrase(pass, cipher); security.biometricEnabled = true }
            .onFailure { _securityError.value = "Could not store passphrase: ${it.message}" }
    }

    /** Held only between an unlock and arming biometrics; cleared straight after. */
    @Volatile private var lastPassphrase: CharArray? = null

    fun onBiometricUnlocked(passphrase: CharArray) = viewModelScope.launch(Dispatchers.IO) {
        val w = service.wallet ?: return@launch
        val ok = runCatching { lock.unlock(w, passphrase) }.getOrDefault(false)
        passphrase.fill('\u0000')
        _securityError.value = if (ok) null else "Stored passphrase no longer matches."
        if (ok) _gateOpen.value = true
    }

    fun clearSecurityError() { _securityError.value = null }

    /** Re-lock after the configured idle period; call when the app backgrounds. */
    fun onPaused() {
        if (lock.lockIfIdle(security.autoLockMinutes) && security.anyLockEnabled) {
            _gateOpen.value = false
        }
    }

    fun onUserActivity() = lock.touch()

    override fun onCleared() {
        // Deliberately does NOT stop the SPV stack: the foreground service keeps
        // syncing while the UI is gone. SyncService.stop() owns teardown.
        super.onCleared()
    }
}
