package net.ixcoin.wallet.node

import android.content.Context
import java.io.File

/**
 * How this wallet gets its view of the chain.
 *
 * [Light] is the default: headers only, verified against the merged-mining
 * proofs, a few hundred megabytes at most.
 *
 * [Full] runs the real iXcoin daemon on the device. The wallet keeps its own
 * keys and keeps using bitcoinj, but syncs from the local node instead of
 * public peers — so the chain is validated on this phone, and no third party
 * learns which addresses are being watched.
 */
enum class NodeMode {
    Light,
    Full;

    companion object {
        private const val PREFS = "ixcoin_node"
        private const val KEY = "mode"

        fun current(context: Context): NodeMode = runCatching {
            valueOf(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, Light.name)!!
            )
        }.getOrDefault(Light)

        fun set(context: Context, mode: NodeMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, mode.name).apply()
        }

        /**
         * The daemon, extracted by the installer into nativeLibraryDir.
         *
         * It is packaged as `libixcoind.so` because Android only extracts and
         * grants execute permission to files matching `lib*.so`, even though
         * this is an executable rather than a library.
         */
        fun daemon(context: Context): File? =
            File(context.applicationInfo.nativeLibraryDir, "libixcoind.so")
                .takeIf { it.exists() }

        fun cli(context: Context): File? =
            File(context.applicationInfo.nativeLibraryDir, "libixcoincli.so")
                .takeIf { it.exists() }

        /**
         * A full node is only offered where the daemon actually exists. The
         * binary ships for arm64 only, so elsewhere the option is hidden rather
         * than offered and then failing.
         */
        fun fullNodeSupported(context: Context): Boolean =
            daemon(context)?.canExecute() == true

        /** Where the full node keeps the chain. */
        fun dataDir(context: Context): File =
            File(context.filesDir, "node").apply { mkdirs() }

        /** Loopback only: the node must never be reachable from the network. */
        const val RPC_PORT = 18332
        const val P2P_PORT = 8337
    }
}
