package net.ixcoin.wallet.seed

import net.ixcoin.wallet.core.IxcoinMainNetParams
import net.ixcoin.wallet.security.WalletLock
import org.bitcoinj.core.Context
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The first-run path: gesture entropy becomes a seed, the seed becomes a
 * wallet, and the wallet is locked with the user's passphrase before it is
 * ever written to disk.
 */
class WalletCreationTest {

    private val params = IxcoinMainNetParams.get()

    @Before
    fun setUp() = Context.propagate(Context(params))

    /** A gesture, as the entropy pad would deliver it. */
    private fun gesture(points: Int = 400, degenerate: Boolean = false): TouchEntropy {
        val e = TouchEntropy()
        repeat(points) { i ->
            if (degenerate) e.add(100f, 100f, 1f)
            else e.add(i.toFloat(), (i * 7 % 300).toFloat(), 0.5f)
        }
        return e
    }

    @Test
    fun `gesture produces a valid BIP39 phrase`() {
        val seed = SeedManager.createSeed(gesture())
        val words = SeedManager.words(seed)
        assertEquals("16 bytes of entropy is a 12-word phrase", 12, words.size)
        assertNull("the phrase it generates must be one it accepts",
            SeedManager.validateMnemonic(words.joinToString(" ")))
    }

    @Test
    fun `the phrase rebuilds the same wallet`() {
        val seed = SeedManager.createSeed(gesture())
        val words = SeedManager.words(seed)

        val restored = DeterministicSeed(words, null, "", seed.creationTimeSeconds)
        assertEquals(
            "restoring from the written-down words must give the same keys",
            Wallet.fromSeed(params, seed, Script.ScriptType.P2PKH).currentReceiveAddress(),
            Wallet.fromSeed(params, restored, Script.ScriptType.P2PKH).currentReceiveAddress()
        )
    }

    /**
     * The gesture is mixed with SecureRandom rather than trusted on its own, so
     * even a user who taps the same spot repeatedly cannot produce a repeatable
     * — or guessable — seed.
     */
    @Test
    fun `a degenerate gesture still yields unique seeds`() {
        val a = SeedManager.words(SeedManager.createSeed(gesture(degenerate = true)))
        val b = SeedManager.words(SeedManager.createSeed(gesture(degenerate = true)))
        assertNotEquals("identical gestures must not give identical seeds", a, b)
    }

    @Test
    fun `entropy pad reports progress and completion`() {
        val e = TouchEntropy()
        assertEquals(0f, e.progress, 0.001f)
        assertFalse(e.isComplete)
        repeat(1000) { i -> e.add(i.toFloat(), (i * 13 % 500).toFloat(), 0.7f) }
        assertTrue("a long gesture should reach the target", e.isComplete)
        assertEquals(1f, e.progress, 0.001f)
    }

    // ---- the lock ---------------------------------------------------------

    @Test
    fun `a new wallet is locked by its passphrase`() {
        val wallet = Wallet.fromSeed(params, SeedManager.createSeed(gesture()), Script.ScriptType.P2PKH)
        val lock = WalletLock()
        assertFalse(wallet.isEncrypted)

        lock.encrypt(wallet, "correct horse battery".toCharArray())

        assertTrue("wallet.dat must be encrypted at rest", wallet.isEncrypted)
        assertTrue("a key crypter must be attached", wallet.keyCrypter != null)
    }

    @Test
    fun `the wrong passphrase cannot unlock it`() {
        val wallet = Wallet.fromSeed(params, SeedManager.createSeed(gesture()), Script.ScriptType.P2PKH)
        val lock = WalletLock()
        lock.encrypt(wallet, "correct horse battery".toCharArray())
        lock.lock()
        assertNull("locking must drop the key", lock.aesKeyOrNull())

        assertFalse("a wrong passphrase must be rejected",
            lock.unlock(wallet, "not the passphrase".toCharArray()))
        assertNull("and must not leave a usable key behind", lock.aesKeyOrNull())

        assertTrue("the real passphrase still works",
            lock.unlock(wallet, "correct horse battery".toCharArray()))
        assertTrue(lock.aesKeyOrNull() != null)
    }

    @Test
    fun `locking again drops the key`() {
        val wallet = Wallet.fromSeed(params, SeedManager.createSeed(gesture()), Script.ScriptType.P2PKH)
        val lock = WalletLock()
        lock.encrypt(wallet, "correct horse battery".toCharArray())
        assertTrue(lock.aesKeyOrNull() != null)
        lock.lock()
        assertNull("spending must be impossible once locked", lock.aesKeyOrNull())
    }
}
