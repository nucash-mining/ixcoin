package net.ixcoin.wallet.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Strong biometrics only — no device-credential fallback for spending. */
private const val STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG

object BiometricGate {

    enum class Availability { AVAILABLE, NONE_ENROLLED, NO_HARDWARE, UNAVAILABLE }

    fun availability(activity: FragmentActivity): Availability =
        when (BiometricManager.from(activity).canAuthenticate(STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.NO_HARDWARE
            else -> Availability.UNAVAILABLE
        }

    sealed interface Result {
        data class Success(val cipher: Cipher?) : Result
        data class Failed(val message: String) : Result
        data object Cancelled : Result
    }

    /**
     * Prompt for a fingerprint/face match.
     *
     * Passing a [cipher] binds the match to a Keystore key: the returned cipher
     * is only usable because the user just authenticated, so this is a real
     * cryptographic gate rather than a boolean the UI could be tricked past.
     */
    suspend fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cipher: Cipher? = null,
    ): Result = suspendCoroutine { cont ->
        var resumed = false
        fun finish(r: Result) { if (!resumed) { resumed = true; cont.resume(r) } }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    finish(Result.Success(result.cryptoObject?.cipher))
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    if (code == BiometricPrompt.ERROR_USER_CANCELED ||
                        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) finish(Result.Cancelled) else finish(Result.Failed(msg.toString()))
                }
                // onAuthenticationFailed is a single bad read; the prompt stays
                // up and the user can try again, so it is not terminal.
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Use passphrase")
            .setAllowedAuthenticators(STRONG)
            .setConfirmationRequired(true)
            .build()

        if (cipher != null) prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        else prompt.authenticate(info)
    }
}
