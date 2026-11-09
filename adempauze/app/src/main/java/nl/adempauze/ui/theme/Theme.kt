package nl.adempauze.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Brand {
    val Ink = Color(0xFF101B2B)
    val Deep = Color(0xFF1C4A52)
    val Mint = Color(0xFF9BE7CF)
    val Sand = Color(0xFFF7F4EE)
    val Leaf = Color(0xFF2E7D6B)
}

private val Light = lightColorScheme(
    primary = Color(0xFF1F6B63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDEBDD),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4F6360),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E8E3),
    onSecondaryContainer = Color(0xFF0C1F1D),
    tertiary = Color(0xFF4A6080),
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF031C37),
    background = Brand.Sand,
    onBackground = Color(0xFF1A1C1B),
    surface = Brand.Sand,
    onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFFE3E6E1),
    onSurfaceVariant = Color(0xFF444845),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF9F5),
    surfaceContainer = Color(0xFFF1EEE8),
    surfaceContainerHigh = Color(0xFFEBE8E2),
    surfaceContainerHighest = Color(0xFFE5E2DC),
    outline = Color(0xFF747874),
    outlineVariant = Color(0xFFC4C8C3),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FD5C3),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF1F5149),
    onPrimaryContainer = Color(0xFFBDEBDD),
    secondary = Color(0xFFB6CCC7),
    onSecondary = Color(0xFF213532),
    secondaryContainer = Color(0xFF374B48),
    onSecondaryContainer = Color(0xFFD2E8E3),
    tertiary = Color(0xFFB2C8EC),
    tertiaryContainer = Color(0xFF324866),
    onTertiaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFF0F1418),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF0F1418),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceContainerLowest = Color(0xFF0A0F12),
    surfaceContainerLow = Color(0xFF151B1F),
    surfaceContainer = Color(0xFF192024),
    surfaceContainerHigh = Color(0xFF23292D),
    surfaceContainerHighest = Color(0xFF2E3438),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3F4945),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun AdempauzeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}

/** Tekststijl voor grote getallen in de statistieken. */
val NumberStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
