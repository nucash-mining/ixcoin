package net.ixcoin.wallet.node

import android.content.Context
import android.util.Log
import java.io.File
import java.security.SecureRandom

/**
 * Runs the bundled iXcoin daemon on the device.
 *
 * The wallet does not put its keys in this node — it keeps its own bitcoinj
 * wallet and simply syncs from the local peer. That keeps the existing key
 * handling, encryption and recovery-phrase story intact while the chain is
 * validated on the phone rather than trusted from strangers.
 */
object FullNode {

    private const val TAG = "ixcoin-node"

    @Volatile
    private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Start the daemon if it is not already up.
     *
     * Returns null on success, or a message to show the user. Failure is
     * reported rather than thrown: a phone that cannot run a full node should
     * fall back to light mode, not crash.
     */
    @Synchronized
    fun start(context: Context): String? {
        if (isRunning) return null
        val bin = NodeMode.daemon(context) ?: return "This build has no bundled node for your device."
        if (!bin.canExecute()) return "The bundled node is not executable on this device."

        val dir = NodeMode.dataDir(context)
        writeConfig(context, dir)

        return try {
            // -printtoconsole keeps output on the pipe; without it the daemon
            // writes only to debug.log and a startup failure is invisible here.
            process = ProcessBuilder(
                bin.absolutePath,
                "-datadir=${dir.absolutePath}",
                "-printtoconsole",
            ).redirectErrorStream(true).start().also { p ->
                Thread({
                    runCatching {
                        p.inputStream.bufferedReader().forEachLine { Log.i(TAG, it) }
                    }
                }, "ixcoind-log").apply { isDaemon = true }.start()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "could not start the node", e)
            "The node could not be started: ${e.message}"
        }
    }

    @Synchronized
    fun stop() {
        process?.let { p ->
            // destroy() sends SIGTERM, which the daemon handles as a clean
            // shutdown; killing it outright can leave the block index corrupt
            // and force a reindex on next start.
            runCatching { p.destroy() }
            runCatching { p.waitFor() }
        }
        process = null
    }

    /**
     * The config is rewritten on every start so it always matches this build.
     *
     * It binds RPC to loopback and generates a fresh random password: the node
     * must not be reachable from the network, and a fixed credential in a
     * shipped app is the same as no credential at all.
     */
    private fun writeConfig(context: Context, dir: File) {
        val conf = File(dir, "ixcoin.conf")
        if (conf.exists()) return
        val password = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        conf.writeText(
            """
            server=1
            listen=1
            rpcbind=127.0.0.1
            rpcallowip=127.0.0.1
            rpcport=${NodeMode.RPC_PORT}
            rpcuser=ixcoin
            rpcpassword=$password
            # The wallet lives in the app, not in the node.
            disablewallet=1
            # A phone is not a good place for an unbounded block store.
            prune=2000
            """.trimIndent() + "\n"
        )
        // Owner-only: other apps must not be able to read the RPC credential.
        runCatching {
            conf.setReadable(false, false)
            conf.setReadable(true, true)
        }
    }
}
