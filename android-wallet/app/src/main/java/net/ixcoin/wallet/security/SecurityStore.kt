package net.ixcoin.wallet.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the wallet's security settings and secrets live.
 *
 * What actually protects the coins is that bitcoinj encrypts the private keys
 * with a scrypt-derived key from the spending passphrase. Everything here exists
 * to hold that passphrase safely when the user has opted into unlocking with a
 * fingerprint, and to gate the UI.
 *
 * Be clear about the threat model:
 *
 *  - The **spending passphrase** is the only thing that protects funds against
 *    someone who has the wallet file. It is never stored in plaintext.
 *  - **Biometric unlock** stores that passphrase encrypted by a key held in the
 *    Android Keystore (StrongBox / TEE where the device has one), released only
 *    on a successful fingerprint or face match. The key material cannot be
 *    exported, so lifting the app's files off the device is not enough.
 *  - The **app PIN** and **TOTP** gate the interface. They do not re-encrypt
 *    anything, so on a rooted or physically compromised device they are a
 *    speed bump, not a wall. They are worth having against casual access to an
 *    unlocked phone, and no more than that.
 */
class SecurityStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ixcoin-security", Context.MODE_PRIVATE)

    // ---- feature switches -------------------------------------------------

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(v) = prefs.edit().putBoolean(KEY_BIOMETRIC, v).apply()

    var pinEnabled: Boolean
        get() = prefs.contains(KEY_PIN_HASH)
        private set(_) {}

    var totpEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOTP_ON, false)
        set(v) = prefs.edit().putBoolean(KEY_TOTP_ON, v).apply()

    /** Minutes of inactivity before the wallet re-locks. 0 = lock immediately. */
    var autoLockMinutes: Int
        get() = prefs.getInt(KEY_AUTOLOCK, 2)
        set(v) = prefs.edit().putInt(KEY_AUTOLOCK, v.coerceIn(0, 60)).apply()

    val anyLockEnabled: Boolean get() = biometricEnabled || pinEnabled || totpEnabled

    // ---- app PIN ----------------------------------------------------------
    // Stored as scrypt-class PBKDF2 over a random salt. This gates the UI only.

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_PIN_SALT, b64(salt))
            .putString(KEY_PIN_HASH, b64(pbkdf2(pin, salt)))
            .apply()
    }

    fun clearPin() = prefs.edit().remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).apply()

    fun verifyPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null)?.let { unb64(it) } ?: return false
        val want = prefs.getString(KEY_PIN_HASH, null)?.let { unb64(it) } ?: return false
        return constantTimeEquals(pbkdf2(pin, salt), want)
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 200_000, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    // ---- TOTP -------------------------------------------------------------

    fun totpSecret(): ByteArray? = prefs.getString(KEY_TOTP_SECRET, null)?.let { unb64(it) }

    fun setTotpSecret(secret: ByteArray) =
        prefs.edit().putString(KEY_TOTP_SECRET, b64(secret)).apply()

    fun clearTotp() =
        prefs.edit().remove(KEY_TOTP_SECRET).putBoolean(KEY_TOTP_ON, false).apply()

    // ---- passphrase held behind the Keystore ------------------------------

    /**
     * Store the spending passphrase encrypted under a Keystore key that is only
     * usable after a biometric match. Requires the user to have just
     * authenticated, because the key is created with
     * setUserAuthenticationRequired(true).
     */
    fun sealPassphrase(passphrase: CharArray, cipher: Cipher) {
        val bytes = String(passphrase).toByteArray(Charsets.UTF_8)
        val sealed = cipher.doFinal(bytes)
        bytes.fill(0)
        prefs.edit()
            .putString(KEY_SEALED, b64(sealed))
            .putString(KEY_SEALED_IV, b64(cipher.iv))
            .apply()
    }

    fun openPassphrase(cipher: Cipher): CharArray? {
        val sealed = prefs.getString(KEY_SEALED, null)?.let { unb64(it) } ?: return null
        return String(cipher.doFinal(sealed), Charsets.UTF_8).toCharArray()
    }

    fun hasSealedPassphrase(): Boolean = prefs.contains(KEY_SEALED)

    /** True only if a sealed passphrase exists AND its keystore key still works.
     *  Adding a fingerprint invalidates the key, which must not crash the UI. */
    fun hasSealedPassphraseSafe(): Boolean =
        hasSealedPassphrase() && runCatching { decryptCipher() != null }.getOrDefault(false)

    fun clearSealedPassphrase() =
        prefs.edit().remove(KEY_SEALED).remove(KEY_SEALED_IV).apply()

    /** Cipher for sealing — call after the biometric prompt succeeds. */
    fun encryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }

    /** Cipher for opening — needs the IV stored alongside the sealed blob. */
    fun decryptCipher(): Cipher? {
        val iv = prefs.getString(KEY_SEALED_IV, null)?.let { unb64(it) } ?: return null
        return Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        }
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // The key is unusable until the user authenticates, and is destroyed
            // if the device's biometric enrolment changes — so adding a new
            // fingerprint cannot be used to reach an existing wallet.
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        runCatching { spec.setIsStrongBoxBacked(true) }   // hardware element where present
        return try {
            gen.init(spec.build()); gen.generateKey()
        } catch (e: Exception) {
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true).build()
            )
            gen.generateKey()
        }
    }

    /** Forget every local secret. Does not touch the wallet file itself. */
    fun wipe() {
        prefs.edit().clear().apply()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ixcoin_wallet_passphrase_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_TOTP_ON = "totp_enabled"
        private const val KEY_TOTP_SECRET = "totp_secret"
        private const val KEY_AUTOLOCK = "autolock_minutes"
        private const val KEY_SEALED = "sealed_passphrase"
        private const val KEY_SEALED_IV = "sealed_passphrase_iv"
    }
}
