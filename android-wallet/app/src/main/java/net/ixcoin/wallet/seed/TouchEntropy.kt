package net.ixcoin.wallet.seed

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Collects entropy from the user dragging a finger around the screen, the way
 * the old paper-wallet generators did.
 *
 * A word on what this is and is not. Android's [SecureRandom] is seeded from
 * the kernel CSPRNG and is already a sound source; finger movement is not
 * needed to make a safe key, and on its own it would be *worse* — touch
 * coordinates are far less random than they feel, being smooth, low-resolution
 * and heavily correlated between samples.
 *
 * So this never replaces the system generator. The gesture is hashed and then
 * XORed with [SecureRandom] output, which means the result is at least as
 * strong as [SecureRandom] alone no matter how poor the gesture was, and
 * strictly better if the gesture did carry real unpredictability. That is the
 * honest version of the feature: it can only add.
 */
class TouchEntropy {

    private val digest = MessageDigest.getInstance("SHA-512")
    private var samples = 0
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    /** Rough lower bound on bits gathered, used to drive the progress bar. */
    private var estimatedBits = 0.0

    val sampleCount: Int get() = samples

    /** 0f..1f against the target, for the UI. */
    val progress: Float get() = (estimatedBits / TARGET_BITS).coerceIn(0.0, 1.0).toFloat()

    val isComplete: Boolean get() = estimatedBits >= TARGET_BITS

    /**
     * Feed one touch sample. Timing is included because the interval between
     * events is a better source than position: it reflects scheduler and
     * digitiser jitter rather than the smooth path of a finger.
     */
    fun add(x: Float, y: Float, pressure: Float) {
        val now = System.nanoTime()
        digest.update(java.nio.ByteBuffer.allocate(24)
            .putFloat(x).putFloat(y).putFloat(pressure)
            .putLong(now)
            .putInt(samples)
            .array())
        samples++

        // Credit entropy only for genuine direction changes and distance, so
        // holding still or tracing one slow line does not fill the bar.
        if (!lastX.isNaN()) {
            val dx = x - lastX
            val dy = y - lastY
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > MIN_TRAVEL_PX) {
                // Deliberately conservative: a few bits per meaningful move.
                estimatedBits += BITS_PER_MOVE
            }
        }
        lastX = x
        lastY = y
    }

    /**
     * Produce [byteCount] bytes of seed material.
     *
     * gesture-hash XOR SecureRandom — see the class comment for why the XOR is
     * the whole point.
     */
    fun mix(byteCount: Int): ByteArray {
        // Fold in the clock and the collected count so two identical gestures
        // still differ.
        digest.update(java.nio.ByteBuffer.allocate(16)
            .putLong(System.nanoTime())
            .putLong(samples.toLong())
            .array())

        val out = ByteArray(byteCount)
        val system = ByteArray(byteCount)
        SecureRandom().nextBytes(system)

        var produced = 0
        var counter = 0
        while (produced < byteCount) {
            val block = digest.clone() as MessageDigest
            block.update(counter.toByte())
            val bytes = block.digest()
            val take = minOf(bytes.size, byteCount - produced)
            System.arraycopy(bytes, 0, out, produced, take)
            produced += take
            counter++
        }
        for (i in out.indices) out[i] = (out[i].toInt() xor system[i].toInt()).toByte()
        system.fill(0)
        return out
    }

    fun reset() {
        digest.reset()
        samples = 0
        estimatedBits = 0.0
        lastX = Float.NaN
        lastY = Float.NaN
    }

    companion object {
        /** Enough movement to feel deliberate without being tedious. */
        const val TARGET_BITS = 256.0
        private const val BITS_PER_MOVE = 1.5
        private const val MIN_TRAVEL_PX = 6f
    }
}
