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

    /** How long to watch a freshly started daemon before calling it up. */
    private const val STARTUP_GRACE_MS = 2500L

    /** Lines of daemon output kept so a startup failure can explain itself. */
    private const val LOG_TAIL = 40

    @Volatile
    private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    @Volatile
    private var rpcPassword: String? = null

    /** What the local node is doing, as far as it will tell us. */
    data class Status(
        val running: Boolean,
        val blocks: Int? = null,
        val headers: Int? = null,
        val progress: Double? = null,
        val detail: String? = null,
    )

    /**
     * Ask the node itself, over loopback RPC, rather than inferring.
     *
     * A live process is not the same as a syncing chain, and the sync takes
     * hours -- so the UI needs a height and a percentage from the node, not a
     * fixed sentence that cannot distinguish progress from a stall.
     */
    fun status(): Status {
        if (!isRunning) return Status(running = false)
        val pass = rpcPassword ?: return Status(running = true, detail = "starting")
        return runCatching {
            val url = java.net.URL("http://127.0.0.1:${NodeMode.RPC_PORT}/")
            val c = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2000
                readTimeout = 3000
                doOutput = true
                val cred = android.util.Base64.encodeToString(
                    "ixcoin:$pass".toByteArray(), android.util.Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $cred")
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use {
                it.write("""{"jsonrpc":"1.0","id":"ui","method":"getblockchaininfo","params":[]}"""
                    .toByteArray())
            }
            val body = c.inputStream.bufferedReader().readText()
            val o = org.json.JSONObject(body).getJSONObject("result")
            Status(
                running = true,
                blocks = o.optInt("blocks", -1).takeIf { it >= 0 },
                headers = o.optInt("headers", -1).takeIf { it >= 0 },
                progress = o.optDouble("verificationprogress", -1.0).takeIf { it >= 0 },
            )
        }.getOrElse {
            // The node is up but not answering yet: it loads the block index
            // before the RPC server starts, which on a phone takes a while.
            Status(running = true, detail = "starting")
        }
    }

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
            val tail = java.util.concurrent.LinkedBlockingDeque<String>()
            process = ProcessBuilder(
                bin.absolutePath,
                "-datadir=${dir.absolutePath}",
                "-printtoconsole",
            ).redirectErrorStream(true).start().also { p ->
                Thread({
                    runCatching {
                        p.inputStream.bufferedReader().forEachLine { line ->
                            Log.i(TAG, line)
                            // Keep the last few lines so a daemon that dies on
                            // startup can say why, instead of the UI reporting
                            // success and then sitting there forever.
                            while (tail.size >= LOG_TAIL) tail.pollFirst()
                            tail.addLast(line)
                        }
                    }
                }, "ixcoind-log").apply { isDaemon = true }.start()
            }

            // ProcessBuilder.start() only means the binary was exec'd. A node
            // that exits a moment later -- datadir already locked, no space,
            // corrupt block index -- would otherwise be indistinguishable from
            // one that is running, and every status afterwards would be a lie.
            val p = process
            if (p != null && p.waitFor(STARTUP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                val why = tail.toList().lastOrNull { it.contains("Error", true) }
                    ?: tail.toList().lastOrNull().orEmpty()
                val code = runCatching { p.exitValue() }.getOrNull()
                process = null
                Log.e(TAG, "node exited during startup (code=$code): $why")
                "The node stopped right after starting" +
                    (if (why.isNotBlank()) ": $why" else " (exit code $code).")
            } else {
                null
            }
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
        if (conf.exists()) {
            // Recover the existing credential: status queries need it, and the
            // config is only written once.
            rpcPassword = runCatching {
                conf.readLines().firstOrNull { it.startsWith("rpcpassword=") }
                    ?.substringAfter("=")
            }.getOrNull()
            return
        }
        val password = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        rpcPassword = password
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
