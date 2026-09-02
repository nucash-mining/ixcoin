package net.ixcoin.wallet

import android.app.Application
import net.ixcoin.wallet.core.IxcoinMainNetParams
import org.bitcoinj.core.Context

class IxcoinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // bitcoinj keeps per-network state in a thread-local Context; establish
        // it once on the main thread so every component agrees on the network.
        Context.propagate(Context(IxcoinMainNetParams.get()))
    }
}
