package net.ixcoin.wallet.security

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * RFC 6238 time-based one-time passwords, compatible with Google Authenticator,
 * Aegis, 1Password and the rest.
 *
 * Implemented here rather than pulled in as a dependency: it is ~40 lines, and a
 * wallet should not take a third-party library into its authentication path
 * without a very good reason.
 */
object Totp {

    private const val PERIOD_SECONDS = 30L
    private const val DIGITS = 6
    /** Accept the neighbouring windows, so a slightly wrong clock still works. */
    private const val DRIFT_WINDOWS = 1

    /** A fresh 160-bit secret, the size RFC 4226 recommends for HMAC-SHA1. */
    fun newSecret(): ByteArray = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }

    fun code(secret: ByteArray, atMillis: Long = System.currentTimeMillis()): String =
        hotp(secret, atMillis / 1000 / PERIOD_SECONDS)

    /**
     * Constant-time verification across the accepted drift window.
     * Returns false for a malformed code rather than throwing.
     */
    fun verify(secret: ByteArray, candidate: String, atMillis: Long = System.currentTimeMillis()): Boolean {
        val cleaned = candidate.filter { it.isDigit() }
        if (cleaned.length != DIGITS) return false
        val counter = atMillis / 1000 / PERIOD_SECONDS
        var ok = false
        for (d in -DRIFT_WINDOWS..DRIFT_WINDOWS) {
            // No early exit: keep the work constant regardless of which window matches.
            if (constantTimeEquals(hotp(secret, counter + d), cleaned)) ok = true
        }
        return ok
    }

    private fun hotp(secret: ByteArray, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = (hash[hash.size - 1] and 0x0f).toInt()
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** RFC 3548 base32, for showing the secret and building the otpauth:// URI. */
    fun base32(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(alphabet[(buffer shr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(alphabet[(buffer shl (5 - bits)) and 0x1f])
        return sb.toString()
    }

    fun provisioningUri(secret: ByteArray, account: String, issuer: String = "iXcoin Wallet"): String =
        "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=${base32(secret)}" +
            "&issuer=${enc(issuer)}&algorithm=SHA1&digits=$DIGITS&period=$PERIOD_SECONDS"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
