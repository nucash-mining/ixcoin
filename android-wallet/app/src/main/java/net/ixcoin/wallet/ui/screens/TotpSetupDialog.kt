package net.ixcoin.wallet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ixcoin.wallet.ui.WalletViewModel
import net.ixcoin.wallet.ui.qrBitmap

/**
 * Two-factor setup.
 *
 * Shows the QR to scan and the secret to type, then makes the user prove the
 * authenticator is working before 2FA is switched on — enabling it on a
 * mis-scanned code would lock them out of their own wallet.
 */
@Composable
fun TotpSetupDialog(
    setup: WalletViewModel.TotpSetup,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val qr = remember(setup.uri) { qrBitmap(setup.uri, 640) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up two-factor") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScrollIfNeeded()
            ) {
                Text(
                    "Scan this with Google Authenticator, Aegis, 1Password or any " +
                    "other TOTP app.",
                    style = MaterialTheme.typography.bodyMedium
                )
                qr?.let {
                    Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                        Image(it.asImageBitmap(), "Two-factor QR code",
                            Modifier.size(200.dp).padding(10.dp))
                    }
                }
                Text("Or enter this key by hand:", style = MaterialTheme.typography.labelMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        setup.secretBase32.chunked(4).joinToString(" "),
                        Modifier.padding(10.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp
                    )
                }
                TextButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("2FA secret", setup.secretBase32))
                }) { Text("Copy key") }

                HorizontalDivider()
                Text(
                    "Now type the 6-digit code your app shows, so we can check it works.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6); error = null },
                    label = { Text("6-digit code") },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.length == 6,
                onClick = {
                    if (onConfirm(code)) onDismiss()
                    else error = "That code did not match. Check your phone's clock is correct."
                }
            ) { Text("Turn on") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Dialog content can exceed the screen on small devices. */
@Composable
private fun Modifier.verticalScrollIfNeeded(): Modifier =
    this.then(Modifier.heightIn(max = 520.dp))
        .then(Modifier.verticalScroll(rememberScrollState()))
