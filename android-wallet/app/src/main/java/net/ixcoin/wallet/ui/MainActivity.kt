package net.ixcoin.wallet.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import net.ixcoin.wallet.security.BiometricGate
import net.ixcoin.wallet.sync.SyncService
import net.ixcoin.wallet.ui.screens.*
import net.ixcoin.wallet.ui.theme.IxcoinTheme

private const val REQ_POST_NOTIFICATIONS = 1001

class MainActivity : androidx.fragment.app.FragmentActivity() {

    private val vm: WalletViewModel by viewModels()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep balances, addresses and the recovery phrase out of screenshots,
        // screen recordings and the app-switcher thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        // Android 13+ hides the sync notification without this. The service
        // still runs either way, so a refusal is not fatal.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // The Activity Result API generates a request code wider than 16
            // bits, which FragmentActivity — required by BiometricPrompt —
            // rejects outright. The classic call takes a code we control.
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS
            )
        }
        // Only once a wallet exists. WalletAppKit creates a random wallet the
        // instant it starts, which would throw away the seed the user is about
        // to generate before they ever see it.
        if (vm.hasWallet()) SyncService.start(this)

        setContent {
            IxcoinTheme {
                var onboarded by remember { mutableStateOf(vm.hasWallet()) }
                if (!onboarded) {
                    OnboardingFlow(vm) {
                        SyncService.start(this@MainActivity)
                        onboarded = true
                    }
                } else {
                    WalletApp(vm, this)
                }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Filled.Home),
    Send("Send", Icons.Filled.ArrowUpward),
    Receive("Receive", Icons.Filled.ArrowDownward),
    History("History", Icons.AutoMirrored.Filled.List),
    Settings("Settings", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletApp(vm: WalletViewModel, activity: androidx.fragment.app.FragmentActivity) {
    val state by vm.state.collectAsStateWithLifecycle()
    val gateOpen by vm.gateOpen.collectAsStateWithLifecycle()
    val securityError by vm.securityError.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Overview) }
    var totpSetup by remember { mutableStateOf<WalletViewModel.TotpSetup?>(null) }
    val scope = rememberCoroutineScope()
    val bioAvailable = remember {
        BiometricGate.availability(activity) == BiometricGate.Availability.AVAILABLE
    }

    // Re-lock when the app leaves the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onPaused() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // The gate covers the whole app: nothing is shown until it is satisfied.
    // An encrypted wallet always demands an unlock. Gating this on
    // anyLockEnabled meant a user who set only a wallet passphrase — no PIN,
    // no fingerprint — walked straight into the wallet.
    if (!gateOpen || (state.encrypted && state.locked)) {
        LockScreen(
            biometricAvailable = bioAvailable && vm.security.biometricEnabled && vm.security.hasSealedPassphraseSafe(),
            pinEnabled = vm.security.pinEnabled,
            totpEnabled = vm.security.totpEnabled,
            error = securityError,
            onBiometric = {
                scope.launch {
                    val cipher = vm.security.decryptCipher()
                    val r = BiometricGate.prompt(activity, "Unlock iXcoin Wallet",
                        "Confirm it is you to unlock spending", cipher)
                    if (r is BiometricGate.Result.Success && r.cipher != null) {
                        val pass = runCatching { vm.security.openPassphrase(r.cipher) }.getOrNull()
                        if (pass != null) vm.onBiometricUnlocked(pass)
                    }
                }
            },
            onPassphrase = { vm.unlockWithPassphrase(it) },
            onPin = { vm.verifyPin(it) },
            onTotp = { vm.verifyTotp(it) },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("iXcoin Wallet") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                Tab.Overview -> OverviewScreen(
                    state,
                    onSend = { tab = Tab.Send },
                    onReceive = { tab = Tab.Receive }
                )
                Tab.Send -> SendScreen(state, vm)
                Tab.Receive -> ReceiveScreen(state, vm)
                Tab.History -> TransactionsScreen(state)
                Tab.Settings -> SettingsScreen(
                    state, vm,
                    biometricAvailable = bioAvailable,
                    onToggleBiometric = { on ->
                        if (!on) { vm.security.biometricEnabled = false; vm.security.clearSealedPassphrase() }
                        else scope.launch {
                            // Sealing needs the passphrase, so it can only be armed
                            // while the wallet is unlocked.
                            val r = BiometricGate.prompt(activity, "Enable biometric unlock",
                                "Confirm it is you", vm.security.encryptCipher())
                            if (r is BiometricGate.Result.Success && r.cipher != null) {
                                vm.armBiometric(r.cipher)
                            }
                        }
                    },
                    onToggleTotp = { on ->
                        if (on) totpSetup = vm.beginTotpSetup() else vm.disableTotp()
                    },
                )
            }
        }

        totpSetup?.let { setup ->
            TotpSetupDialog(
                setup = setup,
                onConfirm = { vm.confirmTotpSetup(it) },
                onDismiss = { vm.cancelTotpSetup(); totpSetup = null },
            )
        }
    }
}
