package net.ixcoin.wallet.ui

import android.os.Bundle
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
import net.ixcoin.wallet.ui.screens.*
import net.ixcoin.wallet.ui.theme.IxcoinTheme

class MainActivity : ComponentActivity() {

    private val vm: WalletViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IxcoinTheme {
                WalletApp(vm)
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
fun WalletApp(vm: WalletViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Overview) }

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
                Tab.Settings -> SettingsScreen(state, vm)
            }
        }
    }
}
