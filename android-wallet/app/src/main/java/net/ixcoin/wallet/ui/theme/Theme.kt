package net.ixcoin.wallet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The iXcoin gold, shared with the desktop wallet's theme. */
val IxGold = Color(0xFFF2B32A)
val IxGoldBright = Color(0xFFFFC64D)
val IxGoldDeep = Color(0xFFD29A15)
val IxInk = Color(0xFF1A1205)

private val DarkColors = darkColorScheme(
    primary = IxGold,
    onPrimary = IxInk,
    primaryContainer = Color(0xFF4A3708),
    onPrimaryContainer = IxGoldBright,
    secondary = Color(0xFF98A0B3),
    onSecondary = Color(0xFF12151C),
    background = Color(0xFF12151C),
    onBackground = Color(0xFFE4E8F1),
    surface = Color(0xFF191D27),
    onSurface = Color(0xFFE4E8F1),
    surfaceVariant = Color(0xFF212633),
    onSurfaceVariant = Color(0xFF98A0B3),
    outline = Color(0xFF333C4E),
    outlineVariant = Color(0xFF2B3242),
    error = Color(0xFFFF7A7F),
    onError = Color(0xFF3A0709),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC8880C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE7B5),
    onPrimaryContainer = Color(0xFF3D2A00),
    secondary = Color(0xFF5D6780),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF1B2030),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B2030),
    surfaceVariant = Color(0xFFEEF1F7),
    onSurfaceVariant = Color(0xFF5D6780),
    outline = Color(0xFFC3CCDB),
    outlineVariant = Color(0xFFD8DEE9),
    error = Color(0xFFC01C28),
    onError = Color(0xFFFFFFFF),
)

/** Amounts are read digit-by-digit, so they get a tabular monospace face. */
val AmountStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp
)

val MonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp
)

@Composable
fun IxcoinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
