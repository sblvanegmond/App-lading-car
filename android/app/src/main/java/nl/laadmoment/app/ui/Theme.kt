package nl.laadmoment.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import nl.laadmoment.core.PriceLevel

/**
 * One meaning per colour, everywhere: green is cheap, red is expensive,
 * amber is sun, and the bright green band is "charge now".
 */
object ChartColors {
    val BEST = 0xFF22E584.toInt()
    val BEST_BAND = 0x3322E584
    val SUN = 0xFFFBBF24.toInt()
    val CHEAP = 0xFF3FA66B.toInt()
    val NORMAL = 0xFF7C8799.toInt()
    val EXPENSIVE = 0xFFF87171.toInt()
    val UNKNOWN = 0xFF3A404C.toInt()

    fun forLevel(level: PriceLevel): Int = when (level) {
        PriceLevel.CHEAP -> CHEAP
        PriceLevel.NORMAL -> NORMAL
        PriceLevel.EXPENSIVE -> EXPENSIVE
        PriceLevel.UNKNOWN -> UNKNOWN
    }
}

@Immutable
data class Signals(
    val best: Color,
    val bestOn: Color,
    val bestBand: Color,
    val sun: Color,
    val sunBand: Color,
    val cheap: Color,
    val normal: Color,
    val expensive: Color,
    val unknown: Color,
    val muted: Color,
    val warning: Color,
) {
    fun forLevel(level: PriceLevel): Color = when (level) {
        PriceLevel.CHEAP -> cheap
        PriceLevel.NORMAL -> normal
        PriceLevel.EXPENSIVE -> expensive
        PriceLevel.UNKNOWN -> unknown
    }
}

private val DarkSignals = Signals(
    best = Color(0xFF22E584),
    bestOn = Color(0xFF04210F),
    bestBand = Color(0x2E22E584),
    sun = Color(0xFFFBBF24),
    sunBand = Color(0x26FBBF24),
    cheap = Color(0xFF3FA66B),
    normal = Color(0xFF7C8799),
    expensive = Color(0xFFF87171),
    unknown = Color(0xFF3A404C),
    muted = Color(0xFF9AA3B2),
    warning = Color(0xFFFFB74D),
)

private val LightSignals = Signals(
    best = Color(0xFF0E9F5A),
    bestOn = Color(0xFFFFFFFF),
    bestBand = Color(0x220E9F5A),
    sun = Color(0xFFE09A00),
    sunBand = Color(0x22E09A00),
    cheap = Color(0xFF2F8F57),
    normal = Color(0xFF8A94A6),
    expensive = Color(0xFFDC4B4B),
    unknown = Color(0xFFCBD1DA),
    muted = Color(0xFF5E6675),
    warning = Color(0xFFB45309),
)

val LocalSignals = staticCompositionLocalOf { DarkSignals }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF22E584),
    onPrimary = Color(0xFF04210F),
    secondary = Color(0xFFFBBF24),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE8EBF0),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE8EBF0),
    surfaceVariant = Color(0xFF1F2430),
    onSurfaceVariant = Color(0xFF9AA3B2),
    surfaceContainer = Color(0xFF171A21),
    surfaceContainerHigh = Color(0xFF1F2430),
    surfaceContainerHighest = Color(0xFF262C39),
    outline = Color(0xFF3A404C),
    outlineVariant = Color(0xFF2A303C),
    error = Color(0xFFF87171),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E9F5A),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFE09A00),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF151820),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF151820),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF5E6675),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F2F5),
    surfaceContainerHighest = Color(0xFFE6E9EE),
    outline = Color(0xFFCBD1DA),
    outlineVariant = Color(0xFFE1E5EB),
    error = Color(0xFFDC4B4B),
)

@Composable
fun LaadmomentTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalSignals provides if (dark) DarkSignals else LightSignals) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
