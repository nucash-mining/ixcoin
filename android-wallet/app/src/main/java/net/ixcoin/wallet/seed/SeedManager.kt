package net.ixcoin.wallet.seed

import net.ixcoin.wallet.core.IxcoinMainNetParams
import org.bitcoinj.core.DumpedPrivateKey
import org.bitcoinj.core.ECKey
import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import java.io.File

/**
 * Creating a wallet, and recovering an old one.
 *
 * Everything here happens on the device. No seed, key, mnemonic or wallet file
 * is written anywhere but the app's private storage, and none of it is ever
 * sent over the network — the only thing that leaves the phone is the ordinary
 * peer-to-peer traffic any wallet makes.
 */
object SeedManager {

    private val params = IxcoinMainNetParams.get()

    /** BIP39 gives 12 words for 128 bits and 24 for 256. */
    const val ENTROPY_BYTES = 16

    // ---- creation ----------------------------------------------------------

    /**
     * Build a new seed from gesture entropy mixed with the system CSPRNG.
     * See [TouchEntropy] for why the mixing matters.
     */
    fun createSeed(entropy: TouchEntropy, bip39Passphrase: String = ""): DeterministicSeed {
        val material = entropy.mix(ENTROPY_BYTES)
        val mnemonic = MnemonicCode.INSTANCE.toMnemonic(material)
        material.fill(0)
        return DeterministicSeed(mnemonic, null, bip39Passphrase, nowSeconds())
    }

    /** Words only, so the UI can show them for the user to write down. */
    fun words(seed: DeterministicSeed): List<String> = seed.mnemonicCode ?: emptyList()

    // ---- recovery ----------------------------------------------------------

    sealed interface RestoreResult {
        data class Ok(val wallet: Wallet) : RestoreResult
        data class Invalid(val reason: String) : RestoreResult
    }

    /** Validate a phrase without building anything, so the UI can guide typing. */
    fun validateMnemonic(phrase: String): String? {
        val words = normalise(phrase)
        if (words.size !in setOf(12, 15, 18, 21, 24))
            return "A recovery phrase is 12 or 24 words — this has ${words.size}."
        return try {
            MnemonicCode.INSTANCE.check(words)
            null
        } catch (e: MnemonicException.MnemonicWordException) {
            "\"${e.badWord}\" is not a valid recovery word."
        } catch (e: MnemonicException.MnemonicChecksumException) {
            "That phrase fails its checksum — a word is wrong or out of order."
        } catch (e: MnemonicException) {
            e.message ?: "That recovery phrase is not valid."
        }
    }

    /**
     * Rebuild a wallet from a phrase.
     *
     * [creationTimeSeconds] matters: bitcoinj only scans the chain from that
     * point, so guessing "now" on an old wallet would skip its history and show
     * an empty balance. When it is unknown, scan from the genesis block.
     */
    fun restoreFromMnemonic(
        phrase: String,
        bip39Passphrase: String = "",
        creationTimeSeconds: Long = 0L,
    ): RestoreResult {
        validateMnemonic(phrase)?.let { return RestoreResult.Invalid(it) }
        val words = normalise(phrase)
        return try {
            val seed = DeterministicSeed(words, null, bip39Passphrase, creationTimeSeconds)
            RestoreResult.Ok(Wallet.fromSeed(params, seed, Script.ScriptType.P2PKH))
        } catch (e: Exception) {
            RestoreResult.Invalid(e.message ?: "Could not rebuild that wallet.")
        }
    }

    // ---- importing keys from an older wallet -------------------------------

    data class KeyImport(val keys: List<ECKey>, val skipped: Int, val note: String?)

    /**
     * Pull private keys out of text.
     *
     * Accepts a bare list of WIF keys and the format `dumpwallet` produces,
     * which is the supported way to get keys out of a Bitcoin-Core-style
     * wallet.dat. Lines that are comments, addresses or anything unparseable
     * are counted and skipped rather than failing the whole import.
     */
    fun importFromText(text: String): KeyImport {
        val keys = ArrayList<ECKey>()
        var skipped = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // dumpwallet lines look like: <wif> <date> label=... # addr=...
            val candidate = line.substringBefore(' ').trim()
            try {
                keys.add(DumpedPrivateKey.fromBase58(params, candidate).key)
            } catch (e: Exception) {
                skipped++
            }
        }
        val note = when {
            keys.isEmpty() && skipped > 0 ->
                "No private keys found. If this came from a wallet.dat, run " +
                "`dumpwallet` in the desktop wallet first — wallet.dat is a " +
                "Berkeley DB file and cannot be read directly."
            skipped > 0 -> "$skipped line(s) skipped."
            else -> null
        }
        return KeyImport(keys, skipped, note)
    }

    /**
     * Is this actually a wallet.dat rather than an exported key list?
     *
     * Worth detecting so the app can explain the one command needed instead of
     * reporting a useless parse failure. Berkeley DB btree files carry the
     * magic 0x00053162 at offset 12, in either byte order.
     */
    fun looksLikeWalletDat(head: ByteArray): Boolean {
        if (head.size < 16) return false
        fun be(o: Int) = ((head[o].toInt() and 0xff) shl 24) or ((head[o + 1].toInt() and 0xff) shl 16) or
            ((head[o + 2].toInt() and 0xff) shl 8) or (head[o + 3].toInt() and 0xff)
        fun le(o: Int) = ((head[o + 3].toInt() and 0xff) shl 24) or ((head[o + 2].toInt() and 0xff) shl 16) or
            ((head[o + 1].toInt() and 0xff) shl 8) or (head[o].toInt() and 0xff)
        return be(12) == 0x00053162 || le(12) == 0x00053162
    }

    // ---- helpers -----------------------------------------------------------

    private fun normalise(phrase: String): List<String> =
        phrase.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    /** Wipe an existing wallet so a restore starts from a clean slate. */
    fun clearExisting(spvDir: File, prefix: String) {
        listOf("$prefix.wallet", "$prefix.spvchain").forEach { name ->
            File(spvDir, name).takeIf { it.exists() }?.delete()
        }
    }
}
