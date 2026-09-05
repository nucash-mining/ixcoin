package net.ixcoin.wallet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ixcoin.wallet.core.IxcoinMainNetParams
import net.ixcoin.wallet.node.NodeMode
import net.ixcoin.wallet.ui.WalletViewModel
import net.ixcoin.wallet.ui.qrBitmap
import net.ixcoin.wallet.ui.theme.AmountStyle
import net.ixcoin.wallet.ui.theme.MonoSmall
import net.ixcoin.wallet.wallet.IxcoinWalletService
import org.bitcoinj.core.Coin
import java.text.SimpleDateFormat
import java.util.Locale

private val fmt = IxcoinMainNetParams.IXC_FORMAT
private fun Coin.pretty(): String = fmt.format(this).toString()

// ------------------------------------------------------------------ Overview

@Composable
fun OverviewScreen(state: IxcoinWalletService.WalletUiState, onSend: () -> Unit, onReceive: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BalanceCard(state)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSend, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Filled.ArrowUpward, null); Spacer(Modifier.width(8.dp)); Text("Send")
            }
            FilledTonalButton(onClick = onReceive, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Filled.ArrowDownward, null); Spacer(Modifier.width(8.dp)); Text("Receive")
            }
        }
        SyncCard(state)
        Text("Recent activity", style = MaterialTheme.typography.titleMedium)
        if (state.transactions.isEmpty()) {
            EmptyHint(
                if (state.syncing) "Catching up with the network…"
                else "No transactions yet. Receive some IXC to get started."
            )
        } else {
            state.transactions.take(8).forEach { TxRowItem(it) }
        }
    }
}

@Composable
private fun BalanceCard(state: IxcoinWalletService.WalletUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Available balance", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(state.available.pretty(), style = AmountStyle, color = MaterialTheme.colorScheme.primary)
            if (state.pending.isPositive) {
                Spacer(Modifier.height(6.dp))
                Text("+ ${state.pending.pretty()} pending",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SyncCard(state: IxcoinWalletService.WalletUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (state.syncing) Icons.Filled.Sync else Icons.Filled.CheckCircle,
                    null,
                    tint = if (state.syncing) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (state.syncing) "Synchronising ${state.syncProgress}%" else "Up to date",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.weight(1f))
                Text("${state.peers} peer${if (state.peers == 1) "" else "s"}",
                    style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.syncing) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { state.syncProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append("Block ")
                    append(state.chainHeight.toString().reversed().chunked(3)
                        .joinToString(",").reversed())
                    if (state.syncing && state.blocksLeft > 0) {
                        append(" · ")
                        append(state.blocksLeft.toString().reversed().chunked(3)
                            .joinToString(",").reversed())
                        append(" to go")
                    }
                },
                style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ------------------------------------------------------------------- Receive

@Composable
fun ReceiveScreen(state: IxcoinWalletService.WalletUiState, vm: WalletViewModel) {
    val ctx = LocalContext.current
    val address = state.receiveAddress
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Your receiving address", style = MaterialTheme.typography.titleMedium)
        if (address.isNotEmpty()) {
            val bmp = remember(address) { qrBitmap("ixcoin:$address", 640) }
            bmp?.let {
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                    Image(it.asImageBitmap(), "Address QR code",
                        Modifier.size(280.dp).padding(14.dp))
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Text(address, Modifier.padding(16.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { copy(ctx, address) }) {
                    Icon(Icons.Filled.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy")
                }
                FilledTonalButton(onClick = { share(ctx, address) }) {
                    Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Share")
                }
                OutlinedButton(onClick = { vm.newReceiveAddress() }) {
                    Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("New")
                }
            }
            Text(
                "A fresh address each time keeps your payments unlinked. Older addresses keep working.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            EmptyHint("Preparing your wallet…")
        }
    }
}

// ---------------------------------------------------------------------- Send

@Composable
fun SendScreen(state: IxcoinWalletService.WalletUiState, vm: WalletViewModel) {
    var address by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var sendAll by rememberSaveable { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val result by vm.sendResult.collectAsState()

    val addressOk = address.isBlank() || vm.isValidAddress(address)
    val parsedAmount = amount.toBigDecimalOrNull()
    val amountCoin = runCatching { parsedAmount?.let { Coin.parseCoin(it.toPlainString()) } }.getOrNull()
    val canSend = state.started && vm.isValidAddress(address) &&
        (sendAll || (amountCoin != null && amountCoin.isPositive && amountCoin <= state.available))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Send IXC", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it.trim() },
            label = { Text("Recipient address") },
            placeholder = { Text("x…") },
            isError = !addressOk,
            supportingText = { if (!addressOk) Text("Not a valid iXcoin address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = if (sendAll) "everything" else amount,
            onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Amount (IXC)") },
            enabled = !sendAll,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(sendAll, { sendAll = it })
            Text("Send entire balance")
            Spacer(Modifier.weight(1f))
            Text("Available ${state.available.pretty()}", style = MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = { confirming = true },
            enabled = canSend,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (sendAll) "Review — send everything" else "Review payment") }

        if (!state.started) EmptyHint("Waiting for the wallet to finish starting…")
        else if (state.syncing) EmptyHint("Still syncing — your balance may be incomplete.")
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Confirm payment") },
            text = {
                Column {
                    Text("To", style = MaterialTheme.typography.labelMedium)
                    Text(address, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Amount", style = MaterialTheme.typography.labelMedium)
                    Text(if (sendAll) "Entire balance (${state.available.pretty()})"
                         else amountCoin?.pretty() ?: "-",
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text("A network fee is deducted on top. This cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    vm.send(address, amountCoin ?: Coin.ZERO, sendAll)
                }) { Text("Send") }
            },
            dismissButton = { TextButton({ confirming = false }) { Text("Cancel") } }
        )
    }

    result?.let { r ->
        AlertDialog(
            onDismissRequest = { vm.clearSendResult() },
            title = { Text(if (r is WalletViewModel.SendResult.Sent) "Payment sent" else "Could not send") },
            text = {
                when (r) {
                    is WalletViewModel.SendResult.Sent ->
                        Text(r.txId, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    is WalletViewModel.SendResult.Failed -> Text(r.reason)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (r is WalletViewModel.SendResult.Sent) { address = ""; amount = ""; sendAll = false }
                    vm.clearSendResult()
                }) { Text("OK") }
            }
        )
    }
}

// -------------------------------------------------------------- Transactions

@Composable
fun TransactionsScreen(state: IxcoinWalletService.WalletUiState) {
    if (state.transactions.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            EmptyHint(if (state.syncing) "Catching up with the network…" else "No transactions yet.")
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(state.transactions, key = { it.txId }) { TxRowItem(it) }
    }
}

/**
 * Open a transaction on the public block explorer.
 *
 * The explorer is a single-page app and routes on the fragment, so the path
 * form (/tx/<id>) is served as a 404 by the static host — the "#/" is load
 * bearing. Only the txid leaves the device; the wallet holds no account there.
 */
private fun openInExplorer(context: Context, txId: String) {
    val url = "https://ixc-exp.wattxchange.app/#/tx/$txId"
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun TxRowItem(tx: IxcoinWalletService.TxRow) {
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable { openInExplorer(context, tx.txId) }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (tx.incoming) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                null,
                tint = if (tx.incoming) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tx.counterparty ?: tx.txId,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp
                )
                Text(
                    buildString {
                        append(tx.time?.let { dateFmt.format(it) } ?: "pending")
                        append(" · ")
                        append(
                            when {
                                tx.confirmations <= 0 -> "unconfirmed"
                                tx.confirmations == 1 -> "1 confirmation"
                                tx.confirmations >= 6 -> "confirmed"
                                else -> "${tx.confirmations} confirmations"
                            }
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                tx.amount.pretty(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = if (tx.incoming) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.OpenInNew,
                "View on the block explorer",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------ Settings

/**
 * Connected peers, and a way to add one by hand.
 *
 * iXcoin's network is small enough that the handful of bootstrap nodes can all
 * be unreachable from a given phone — several are IPv6-only — which leaves the
 * wallet with nothing to sync from and no way for the user to fix it. Added
 * addresses are stored on the device and offered to peer discovery alongside
 * the built-in seeds.
 */
/**
 * Full node or light wallet.
 *
 * A full node on a phone is a real commitment — a multi-gigabyte download and
 * hours of validation — so the cost is stated plainly rather than buried, and
 * light mode stays the default.
 */
/**
 * One line describing what the node is really doing.
 *
 * The distinction that matters is Full-but-not-running: the switch is on, the
 * user believes the chain is being validated here, and nothing is. That has to
 * read as a fault, not as reassurance.
 */
private fun nodeSummary(
    mode: NodeMode,
    status: net.ixcoin.wallet.node.FullNode.Status,
): String {
    if (mode != NodeMode.Full) {
        return "Light mode: block headers only, verified against the " +
            "merged-mining proofs."
    }
    if (!status.running) return "The node is not running. Turn it off and on to retry."
    val blocks = status.blocks
    val headers = status.headers
    return when {
        blocks == null -> "Starting the node on this device\u2026"
        headers != null && headers > 0 && blocks < headers -> {
            val pct = ((status.progress ?: (blocks.toDouble() / headers)) * 100)
                .coerceIn(0.0, 100.0)
            "Validating on this device: block %,d of %,d (%.1f%%)".format(blocks, headers, pct)
        }
        else -> "Validating on this device: block %,d, up to date".format(blocks)
    }
}

@Composable
private fun NodeModeSection(vm: WalletViewModel) {
    val supported = remember { vm.fullNodeSupported() }
    if (!supported) return

    var mode by remember { mutableStateOf(vm.nodeMode()) }
    var status by remember { mutableStateOf(vm.nodeStatus()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }

    // The node is a separate process that can die at any point -- Android can
    // reclaim the app, the datadir can fill up -- so the panel has to keep
    // asking. Latching this once meant the screen went on claiming the chain
    // was being validated long after the daemon had gone.
    LaunchedEffect(mode) {
        while (true) {
            status = vm.nodeStatus()
            kotlinx.coroutines.delay(if (status.running) 5_000L else 15_000L)
        }
    }

    Text("Node", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Run a full node")
                    Text(
                        nodeSummary(mode, status),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mode == NodeMode.Full && !status.running)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = mode == NodeMode.Full,
                    onCheckedChange = { on ->
                        error = null
                        if (on) {
                            confirming = true
                        } else {
                            error = vm.setNodeMode(NodeMode.Light)
                            mode = NodeMode.Light
                            status = vm.nodeStatus()
                        }
                    }
                )
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Run a full node?") },
            text = {
                Text(
                    "Your phone will download and verify the whole iXcoin chain " +
                        "instead of trusting other nodes. Expect several gigabytes " +
                        "of storage and hours of syncing, and keep it on a charger " +
                        "and on Wi-Fi.\n\n" +
                        "Your keys stay in this wallet either way — the node only " +
                        "supplies blocks, and it is reachable only from this device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    error = vm.setNodeMode(NodeMode.Full)
                    if (error == null) mode = NodeMode.Full
                    status = vm.nodeStatus()
                }) { Text("Start the node") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        )
    }
}


@Composable
private fun PeersSection(
    state: IxcoinWalletService.WalletUiState,
    vm: WalletViewModel,
) {
    var entry by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(vm.userPeers()) }

    Text("Peers", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.peerRows.isEmpty()) {
                Text(
                    if (state.peers > 0) "Connecting…" else "No peers connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.peerRows.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.address, style = MonoSmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(
                                buildString {
                                    if (p.height > 0) append("height ").append(p.height)
                                    if (p.subVer.isNotEmpty()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(p.subVer)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (p.userAdded) {
                            Text("added", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Divider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { entry = it; error = null },
                    label = { Text("Add peer (host or host:port)") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        error = vm.addPeer(entry)
                        if (error == null) { entry = ""; added = vm.userPeers() }
                    },
                    enabled = entry.isNotBlank()
                ) { Text("Add") }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }

            if (added.isNotEmpty()) {
                Text("Saved addresses", style = MaterialTheme.typography.labelMedium)
                added.forEach { a ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(a, style = MonoSmall, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { vm.removePeer(a); added = vm.userPeers() }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun SettingsScreen(
    state: IxcoinWalletService.WalletUiState,
    vm: WalletViewModel,
    biometricAvailable: Boolean = false,
    onToggleBiometric: (Boolean) -> Unit = {},
    onToggleTotp: (Boolean) -> Unit = {},
) {
    val ctx = LocalContext.current
    var showSeed by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Wallet", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow("Network", "iXcoin mainnet")
                InfoRow("Block height", state.chainHeight.toString())
                InfoRow("Connected peers", state.peers.toString())
                InfoRow("Status", if (state.syncing) "syncing ${state.syncProgress}%" else "up to date")
                InfoRow("Protocol", IxcoinMainNetParams.PROTOCOL_VERSION.toString())
            }
        }

        NodeModeSection(vm)

        PeersSection(state, vm)

        Text("Backup", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Your recovery phrase is the only way to restore this wallet. " +
                    "Write it on paper and keep it offline — anyone who reads it can spend your coins.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!showSeed) {
                    FilledTonalButton(onClick = { showSeed = true }) {
                        Icon(Icons.Filled.Visibility, null); Spacer(Modifier.width(8.dp))
                        Text("Reveal recovery phrase")
                    }
                } else {
                    val words = vm.mnemonic()
                    if (words == null) {
                        Text("Not available until the wallet has started.")
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                words.mapIndexed { i, w -> "${i + 1}. $w" }.chunked(3)
                                    .joinToString("\n") { it.joinToString("   ") },
                                Modifier.padding(14.dp),
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp
                            )
                        }
                        TextButton(onClick = { showSeed = false }) { Text("Hide") }
                    }
                }
            }
        }

        SecuritySection(
            encrypted = state.encrypted,
            locked = state.locked,
            biometricEnabled = vm.security.biometricEnabled,
            biometricAvailable = biometricAvailable,
            pinEnabled = vm.security.pinEnabled,
            totpEnabled = vm.security.totpEnabled,
            autoLockMinutes = vm.security.autoLockMinutes,
            onEncrypt = { vm.encryptWallet(it) },
            onChangePassphrase = { a, b -> vm.changePassphrase(a, b) },
            onToggleBiometric = onToggleBiometric,
            onSetPin = { vm.setPin(it) },
            onToggleTotp = onToggleTotp,
            onAutoLock = { vm.security.autoLockMinutes = it },
            onLockNow = { vm.lockNow() },
        )

        Text("About", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("iXcoin Wallet 1.0.0", fontWeight = FontWeight.SemiBold)
                Text(
                    "A light (SPV) wallet. It verifies block headers — including the " +
                    "merged-mining proofs iXcoin inherits from Bitcoin — without downloading " +
                    "the full chain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(value, style = MonoSmall)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp)
    )
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("iXcoin address", text))
}

private fun share(ctx: Context, text: String) {
    ctx.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Share address"
        )
    )
}
