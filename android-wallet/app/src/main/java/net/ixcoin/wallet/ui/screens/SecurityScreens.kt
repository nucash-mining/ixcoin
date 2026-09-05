package net.ixcoin.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The screen shown while the wallet is locked.
 *
 * Receiving and chain sync continue behind this; only spending is gated.
 */
/**
 * A passphrase field with a reveal toggle.
 *
 * Typing a long passphrase blind on a phone keyboard is how people end up
 * locked out of their own wallet, so let them look at what they typed. The
 * field still starts masked, and the toggle is per-field and not remembered.
 */
@Composable
fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    if (visible) "Hide passphrase" else "Show passphrase"
                )
            }
        },
        modifier = modifier
    )
}

@Composable
fun LockScreen(
    biometricAvailable: Boolean,
    pinEnabled: Boolean,
    totpEnabled: Boolean,
    error: String?,
    onBiometric: () -> Unit,
    onPassphrase: (String) -> Unit,
    onPin: (String) -> Unit,
    onTotp: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Wallet locked", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Your coins stay safe while locked. Unlock to spend.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        if (biometricAvailable) {
            Button(onClick = onBiometric, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp))
                Text("Unlock with biometrics")
            }
            Text("or", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        PassphraseField(
            value = passphrase, onValueChange = { passphrase = it },
            label = "Spending passphrase", modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onPassphrase(passphrase); passphrase = "" },
            enabled = passphrase.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("Unlock") }

        if (pinEnabled) {
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                label = { Text("App PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            FilledTonalButton(
                onClick = { onPin(pin); pin = "" },
                enabled = pin.length >= 4, modifier = Modifier.fillMaxWidth()
            ) { Text("Unlock with PIN") }
        }

        if (totpEnabled) {
            OutlinedTextField(
                value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("Authenticator code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            FilledTonalButton(
                onClick = { onTotp(code); code = "" },
                enabled = code.length == 6, modifier = Modifier.fillMaxWidth()
            ) { Text("Verify code") }
        }
    }
}

/** Security settings: what is on, and an honest note about what each layer does. */
@Composable
fun SecuritySection(
    encrypted: Boolean,
    locked: Boolean,
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    pinEnabled: Boolean,
    totpEnabled: Boolean,
    autoLockMinutes: Int,
    onEncrypt: (String) -> Unit,
    onChangePassphrase: (String, String) -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onSetPin: (String?) -> Unit,
    onToggleTotp: (Boolean) -> Unit,
    onAutoLock: (Int) -> Unit,
    onLockNow: () -> Unit,
) {
    var newPass by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var oldPass by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var showChange by remember { mutableStateOf(false) }

    Text("Security", style = MaterialTheme.typography.titleMedium)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            if (!encrypted) {
                Text(
                    "This wallet is not encrypted. Anyone who gets the wallet file can " +
                    "spend your coins. Set a spending passphrase to encrypt the private keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedTextField(
                    value = newPass, onValueChange = { newPass = it },
                    label = { Text("Spending passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPass, onValueChange = { confirmPass = it },
                    label = { Text("Confirm passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = confirmPass.isNotEmpty() && confirmPass != newPass,
                    supportingText = {
                        if (confirmPass.isNotEmpty() && confirmPass != newPass) Text("Passphrases do not match")
                        else Text("There is no way to recover this. Without it your coins are gone.")
                    },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onEncrypt(newPass); newPass = ""; confirmPass = "" },
                    enabled = newPass.length >= 8 && newPass == confirmPass,
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Filled.Lock, null); Spacer(Modifier.width(8.dp)); Text("Encrypt wallet") }
                HorizontalDivider()
                // Shown even before encryption so the options are discoverable;
                // hiding them entirely made the app look like it had no
                // biometric support at all.
                SwitchRow(
                    title = "Unlock with biometrics",
                    subtitle = "Set a spending passphrase first — biometrics unlock that passphrase",
                    checked = false, enabled = false, onChange = {}
                )
                SwitchRow(
                    title = "Authenticator (2FA)",
                    subtitle = "Set a spending passphrase first",
                    checked = false, enabled = false, onChange = {}
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Wallet encrypted", fontFamily = FontFamily.Default)
                        Text(
                            if (locked) "Locked — spending disabled" else "Unlocked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!locked) TextButton(onClick = onLockNow) { Text("Lock now") }
                }

                HorizontalDivider()

                SwitchRow(
                    title = "Unlock with biometrics",
                    subtitle = when {
                        !biometricAvailable ->
                            "Add a fingerprint or face in Android settings to use this"
                        biometricEnabled -> "Passphrase held in the hardware keystore"
                        else -> "Store the passphrase in the hardware keystore"
                    },
                    checked = biometricEnabled,
                    enabled = biometricAvailable,
                    onChange = onToggleBiometric
                )

                SwitchRow(
                    title = "Authenticator (2FA)",
                    subtitle = "A time-based code is required to open the app",
                    checked = totpEnabled,
                    enabled = true,
                    onChange = onToggleTotp
                )

                HorizontalDivider()

                Text("App PIN", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                        label = { Text(if (pinEnabled) "New PIN" else "Set a PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { onSetPin(pin); pin = "" }, enabled = pin.length >= 4) { Text("Set") }
                }
                if (pinEnabled) TextButton(onClick = { onSetPin(null) }) { Text("Remove PIN") }

                HorizontalDivider()

                Text("Auto-lock after $autoLockMinutes min of inactivity",
                    style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = autoLockMinutes.toFloat(),
                    onValueChange = { onAutoLock(it.toInt()) },
                    valueRange = 0f..30f, steps = 29
                )

                TextButton(onClick = { showChange = !showChange }) {
                    Text(if (showChange) "Cancel" else "Change spending passphrase")
                }
                if (showChange) {
                    OutlinedTextField(
                        value = oldPass, onValueChange = { oldPass = it },
                        label = { Text("Current passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPass, onValueChange = { newPass = it },
                        label = { Text("New passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onChangePassphrase(oldPass, newPass); oldPass = ""; newPass = ""; showChange = false },
                        enabled = oldPass.isNotEmpty() && newPass.length >= 8,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Change passphrase") }
                }
            }

            HorizontalDivider()
            Text(
                "What each layer actually does: the passphrase encrypts your private " +
                "keys, and is the only thing that protects your coins if someone gets " +
                "the wallet file. Biometrics unlock that passphrase from hardware-backed " +
                "storage. The PIN and authenticator code gate this app's screens — useful " +
                "if someone picks up your unlocked phone, but not a substitute for the " +
                "passphrase.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String, subtitle: String, checked: Boolean, enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
