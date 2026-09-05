package net.ixcoin.wallet.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter

/**
 * The wallet lock.
 *
 * Encrypting the wallet has bitcoinj derive an AES key from the passphrase with
 * scrypt and re-encrypt every private key with it. Once encrypted, the keys on
 * disk are useless without the passphrase — that is the guarantee that actually
 * protects funds.
 *
 * "Locked" means we are not holding the derived key in memory. Watching the
 * chain, receiving, and showing history all keep working while locked; only
 * spending needs the key. That mirrors `walletlock` in the desktop client.
 */
class WalletLock {

    /** The derived AES key, held only while unlocked. Never persisted. */
    @Volatile
    private var aesKey: KeyParameter? = null

    @Volatile
    private var unlockedAtMillis: Long = 0L

    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun isEncrypted(wallet: Wallet?): Boolean = wallet?.isEncrypted == true

    /**
     * Turn on encryption. scrypt parameters are deliberately expensive: the
     * default cost makes an offline guess of a weak passphrase slow.
     */
    fun encrypt(wallet: Wallet, passphrase: CharArray) {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        if (wallet.isEncrypted) throw IllegalStateException("wallet is already encrypted")
        val crypter = KeyCrypterScrypt(SCRYPT_ITERATIONS)
        val key = crypter.deriveKey(String(passphrase))
        wallet.encrypt(crypter, key)
        aesKey = key
        unlockedAtMillis = System.currentTimeMillis()
        _locked.value = false
    }

    /** Remove encryption. Requires the current passphrase. */
    fun decryptPermanently(wallet: Wallet, passphrase: CharArray) {
        wallet.decrypt(String(passphrase))
        aesKey = null
        _locked.value = false
    }

    fun changePassphrase(wallet: Wallet, old: CharArray, new: CharArray) {
        require(new.isNotEmpty()) { "passphrase must not be empty" }
        // Round-trips through plaintext in memory only; the file is rewritten
        // encrypted under the new key before this returns.
        wallet.decrypt(String(old))
        val crypter = KeyCrypterScrypt(SCRYPT_ITERATIONS)
        val key = crypter.deriveKey(String(new))
        wallet.encrypt(crypter, key)
        aesKey = key
        unlockedAtMillis = System.currentTimeMillis()
        _locked.value = false
    }

    /**
     * Derive and hold the key so spending is possible.
     * @return false if the passphrase was wrong — the wallet stays locked.
     */
    fun unlock(wallet: Wallet, passphrase: CharArray): Boolean {
        val crypter = wallet.keyCrypter ?: return false
        val key = crypter.deriveKey(String(passphrase))
        // checkAESKey is the cheap way to reject a wrong passphrase without
        // attempting (and failing) a signature later.
        if (!wallet.checkAESKey(key)) return false
        aesKey = key
        unlockedAtMillis = System.currentTimeMillis()
        _locked.value = false
        return true
    }

    /** Drop the key. Spending is impossible until unlocked again. */
    fun lock() {
        aesKey?.key?.fill(0)
        aesKey = null
        unlockedAtMillis = 0
        _locked.value = true
    }

    /** The key to attach to a SendRequest, or null while locked. */
    fun aesKeyOrNull(): KeyParameter? = aesKey

    /** Re-lock once the configured idle period has passed. */
    fun lockIfIdle(autoLockMinutes: Int): Boolean {
        val key = aesKey ?: return false
        if (autoLockMinutes <= 0) { lock(); return true }
        val idleMs = System.currentTimeMillis() - unlockedAtMillis
        if (idleMs >= autoLockMinutes * 60_000L) { lock(); return true }
        return false
    }

    /** Called on user activity so the idle timer measures real inactivity. */
    fun touch() {
        if (aesKey != null) unlockedAtMillis = System.currentTimeMillis()
    }

    companion object {
        /**
         * bitcoinj's default is 16384. Phones can afford more, and every
         * increment multiplies the cost of guessing a weak passphrase offline.
         */
        const val SCRYPT_ITERATIONS = 65536
    }
}
