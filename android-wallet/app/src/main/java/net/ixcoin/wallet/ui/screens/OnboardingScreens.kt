package net.ixcoin.wallet.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ixcoin.wallet.seed.SeedManager
import net.ixcoin.wallet.seed.TouchEntropy
import net.ixcoin.wallet.ui.WalletViewModel
import org.bitcoinj.wallet.DeterministicSeed

/**
 * First-run flow: generate a wallet from a gesture, or restore an existing one.
 *
 * Nothing here leaves the device. The seed is derived on the phone, shown once
 * for the user to write down, and the wallet is encrypted with their passphrase
 * before it is first written to disk.
 */
private enum class Step { Welcome, Gesture, ShowSeed, ConfirmSeed, Passphrase, Restore }

@Composable
fun OnboardingFlow(vm: WalletViewModel, onDone: () -> Unit) {
    var step by rememberSaveableStep()
    var seed by remember { mutableStateOf<DeterministicSeed?>(null) }
    var restoring by remember { mutableStateOf(false) }

    when (step) {
        Step.Welcome -> WelcomeStep(
            onCreate = { step = Step.Gesture },
            onRestore = { restoring = true; step = Step.Restore }
        )
        Step.Gesture -> GestureStep { entropy ->
            seed = SeedManager.createSeed(entropy)
            step = Step.ShowSeed
        }
        Step.ShowSeed -> ShowSeedStep(
            words = SeedManager.words(seed!!),
            onBack = { seed = null; step = Step.Gesture },
            onNext = { step = Step.ConfirmSeed }
        )
        Step.ConfirmSeed -> ConfirmSeedStep(
            words = SeedManager.words(seed!!),
            onBack = { step = Step.ShowSeed },
            onNext = { step = Step.Passphrase }
        )
        Step.Restore -> RestoreStep(
            onBack = { step = Step.Welcome },
            onSeed = { s -> seed = s; step = Step.Passphrase }
        )
        Step.Passphrase -> PassphraseStep { pass ->
            val s = seed ?: return@PassphraseStep
            if (restoring) vm.restoreWallet(s, pass) else vm.createWallet(s, pass)
            seed = null
            onDone()
        }
    }
}

@Composable
private fun rememberSaveableStep(): MutableState<Step> = remember { mutableStateOf(Step.Welcome) }

@Composable
private fun Frame(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun WelcomeStep(onCreate: () -> Unit, onRestore: () -> Unit) {
    Frame(
        "iXcoin Wallet",
        "Your keys are generated and kept on this device. Nothing is uploaded, " +
            "and no one — including us — can recover your wallet for you."
    ) {
        Button(onCreate, Modifier.fillMaxWidth()) { Text("Create a new wallet") }
        OutlinedButton(onRestore, Modifier.fillMaxWidth()) { Text("Restore from a recovery phrase") }
    }
}

/**
 * The entropy pad.
 *
 * Finger movement is a supplement to the system CSPRNG, never a replacement:
 * [TouchEntropy.mix] XORs the gesture digest with SecureRandom output, so even
 * a lazy or predictable scribble cannot make the seed weaker than the platform
 * RNG alone.
 */
@Composable
private fun GestureStep(onComplete: (TouchEntropy) -> Unit) {
    val entropy = remember { TouchEntropy() }
    var progress by remember { mutableStateOf(0f) }
    var samples by remember { mutableStateOf(0) }
    val trail = remember { mutableStateListOf<Offset>() }

    Frame(
        "Move your finger around",
        "Draw anywhere in the box below. The randomness of your movement and " +
            "its timing is mixed into your new wallet key."
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
        Text("${(progress * 100).toInt()}% · $samples samples",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Surface(
            Modifier.fillMaxWidth().height(360.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val p = change.position
                        entropy.add(p.x, p.y, change.pressure)
                        if (trail.size > 400) trail.removeAt(0)
                        trail.add(p)
                        progress = entropy.progress
                        samples = entropy.sampleCount
                    }
                }
            ) {
                for (i in 1 until trail.size) {
                    drawLine(
                        color = Color(0xFF3DDC84),
                        start = trail[i - 1],
                        end = trail[i],
                        strokeWidth = 4f
                    )
                }
            }
        }

        Button(
            onClick = { onComplete(entropy) },
            enabled = entropy.isComplete || progress >= 1f,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (progress >= 1f) "Generate my recovery phrase" else "Keep drawing…") }
    }
}

@Composable
private fun ShowSeedStep(words: List<String>, onBack: () -> Unit, onNext: () -> Unit) {
    var written by remember { mutableStateOf(false) }
    Frame(
        "Your recovery phrase",
        "Write these ${words.size} words down on paper, in order, and keep them " +
            "somewhere safe. Anyone who reads them can spend your coins, and " +
            "losing them means losing the wallet."
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                words.chunked(3).forEachIndexed { row, chunk ->
                    Row(Modifier.fillMaxWidth()) {
                        chunk.forEachIndexed { col, w ->
                            Text(
                                "${row * 3 + col + 1}. $w",
                                Modifier.weight(1f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(written, { written = it })
            Text("I have written these words down offline.")
        }
        Button(onNext, Modifier.fillMaxWidth(), enabled = written) { Text("Continue") }
        TextButton(onBack, Modifier.fillMaxWidth()) { Text("Start over") }
    }
}

/** Checks a few positions rather than all of them, which people actually complete. */
@Composable
private fun ConfirmSeedStep(words: List<String>, onBack: () -> Unit, onNext: () -> Unit) {
    val asked = remember(words) { words.indices.shuffled().take(3).sorted() }
    val answers = remember { mutableStateListOf("", "", "") }
    var error by remember { mutableStateOf<String?>(null) }

    Frame("Check your phrase", "Type the words at these positions to confirm you saved them.") {
        asked.forEachIndexed { i, idx ->
            OutlinedTextField(
                value = answers[i],
                onValueChange = { answers[i] = it.trim(); error = null },
                label = { Text("Word ${idx + 1}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                val ok = asked.withIndex().all { (i, idx) ->
                    answers[i].equals(words[idx], ignoreCase = true)
                }
                if (ok) onNext() else error = "Those do not match your phrase."
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Confirm") }
        TextButton(onBack, Modifier.fillMaxWidth()) { Text("Show the phrase again") }
    }
}

@Composable
private fun RestoreStep(onBack: () -> Unit, onSeed: (DeterministicSeed) -> Unit) {
    var phrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Frame(
        "Restore your wallet",
        "Enter the recovery phrase you wrote down when you created it. It is " +
            "checked on this device and never sent anywhere."
    ) {
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it; error = null },
            label = { Text("Recovery phrase") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                val problem = SeedManager.validateMnemonic(phrase)
                if (problem != null) { error = problem; return@Button }
                // Rebuild the seed itself; an old wallet needs a creation time
                // early enough that the rescan actually reaches its history.
                runCatching {
                    org.bitcoinj.wallet.DeterministicSeed(
                        phrase.trim().split(Regex("\\s+")), null, "", 0L
                    )
                }.onSuccess(onSeed)
                 .onFailure { error = it.message ?: "That phrase could not be read." }
            },
            enabled = phrase.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restore") }
        TextButton(onBack, Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun PassphraseStep(onDone: (CharArray) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Frame(
        "Lock your wallet",
        "This passphrase encrypts your wallet file on this device. It is required " +
            "to spend, and it is the fallback whenever a PIN or fingerprint is " +
            "unavailable. It cannot be reset — if you forget it, only your " +
            "recovery phrase can rebuild the wallet."
    ) {
        PassphraseField(
            value = pass,
            onValueChange = { pass = it; error = null },
            label = "Passphrase",
            isError = error != null,
            modifier = Modifier.fillMaxWidth()
        )
        PassphraseField(
            value = again,
            onValueChange = { again = it; error = null },
            label = "Repeat passphrase",
            isError = error != null,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                when {
                    pass.length < 8 -> error = "Use at least 8 characters."
                    pass != again -> error = "The two entries do not match."
                    else -> onDone(pass.toCharArray())
                }
            },
            enabled = pass.isNotEmpty() && again.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Create wallet") }
    }
}
