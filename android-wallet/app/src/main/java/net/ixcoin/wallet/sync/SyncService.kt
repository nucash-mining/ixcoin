package net.ixcoin.wallet.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.ixcoin.wallet.R
import net.ixcoin.wallet.ui.MainActivity
import net.ixcoin.wallet.wallet.IxcoinWalletService

/**
 * Keeps the SPV stack alive while the app is in the background.
 *
 * Android suspends ordinary background work, and several vendors are more
 * aggressive still — this device freezes the process outright shortly after it
 * leaves the foreground, which stops the chain download dead. A foreground
 * service with a visible notification is the supported way to keep syncing, and
 * the notification doubles as the honest disclosure that the app is doing work.
 *
 * Locks are held only while actually catching up, and released once the chain
 * is at the tip, so an idle wallet does not sit on the CPU or the radio.
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var holdingLocks = false
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification("Starting…", null, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSyncing()
            return START_NOT_STICKY
        }

        val wallet = IxcoinWalletService.get(this)
        // Onboarding owns wallet creation. This service is sticky, so Android
        // restarts it on its own after a stop or a crash — and starting the kit
        // with no wallet on disk makes WalletAppKit generate a random one,
        // which silently replaces the seed the user was in the middle of
        // writing down. isStarted covers the gap right after creation, when the
        // kit is up but the file has not been written yet.
        if (!wallet.hasWallet() && !wallet.isStarted) {
            stopSelf()
            return START_NOT_STICKY
        }
        wallet.start()

        if (watcher == null) {
            watcher = scope.launch {
                wallet.state.collectLatest { s ->
                    val peers = "${s.peers} peer${if (s.peers == 1) "" else "s"}"
                    val title = when {
                        s.error != null -> "Sync problem"
                        s.syncing -> "Synchronising ${s.syncProgress}%"
                        else -> "Up to date"
                    }
                    val text = when {
                        s.error != null -> s.error
                        s.syncing -> "Block ${s.chainHeight} · $peers"
                        else -> "Block ${s.chainHeight} · $peers · watching for payments"
                    }
                    notify(buildNotification(title, text, if (s.syncing) s.syncProgress else 100))
                    // Only hold the CPU and wifi while there is catching up to do.
                    setLocks(s.syncing)
                }
            }
        }
        // START_STICKY: if the system kills us under pressure, come back and
        // carry on syncing rather than silently leaving the wallet stale.
        return START_STICKY
    }

    override fun onDestroy() {
        setLocks(false)
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopSyncing() {
        setLocks(false)
        IxcoinWalletService.get(this).stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- locks -------------------------------------------------------------

    private fun setLocks(want: Boolean) {
        if (want == holdingLocks) return
        holdingLocks = want
        if (want) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ixcoin:sync").apply {
                setReferenceCounted(false)
                // Bounded so a stuck sync can never pin the CPU indefinitely.
                acquire(30 * 60 * 1000L)
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ixcoin:sync").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
            wifiLock = null
        }
    }

    // ---- notification ------------------------------------------------------

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "Wallet sync", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while the wallet is following the iXcoin chain."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String?, progress: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(title)
            .apply { text?.let { setContentText(it) } }
            .setContentIntent(open)
            .addAction(0, "Stop syncing", stop)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Balances stay off the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .apply { if (progress in 0..99) setProgress(100, progress, false) }
            .build()
    }

    private fun notify(n: Notification) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL_ID = "ixcoin-sync"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "net.ixcoin.wallet.STOP_SYNC"

        fun start(context: Context) {
            val i = Intent(context, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SyncService::class.java).setAction(ACTION_STOP))
        }
    }
}
