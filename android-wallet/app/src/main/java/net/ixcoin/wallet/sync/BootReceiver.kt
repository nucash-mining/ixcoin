package net.ixcoin.wallet.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Resume syncing after a reboot, but only if the user had it running.
 *
 * Starting unasked would be rude — and on Android 12+ a background start of a
 * foreground service is refused anyway unless it is an allowed case like this
 * BOOT_COMPLETED one.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("ixcoin-sync", Context.MODE_PRIVATE)
        if (prefs.getBoolean("background_sync", true)) {
            runCatching { SyncService.start(context) }
        }
    }
}
