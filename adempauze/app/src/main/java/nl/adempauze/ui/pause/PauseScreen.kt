package nl.adempauze.ui.pause

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nl.adempauze.core.Messages
import nl.adempauze.util.rememberAppIcon
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos

/** Alles wat het pauzescherm nodig heeft. */
data class PauseUi(
    val id: Long,
    val packageName: String,
    val appLabel: String,
    val mode: PauseActivity.Mode,
    val waitSeconds: Int,
    val message: String,
    val attemptsToday: Int,
    val openedToday: Int,
    val sessionMinutes: List<Int>,
    val minutesUsed: Int,
    val haptics: Boolean,
    val hour: Int,
)

/** Kleuren die meebewegen met het moment van de dag. */
data class PausePalette(val top: Color, val bottom: Color, val accent: Color, val onAccent: Color) {
    companion object {
        fun forHour(hour: Int): PausePalette = when (hour) {
            in 5..10 -> PausePalette(Color(0xFF1E2742), Color(0xFF6E5468), Color(0xFFFFCDB2), Color(0xFF3B1F14))
            in 11..16 -> PausePalette(Color(0xFF0E2A35), Color(0xFF1E5A5C), Color(0xFF9BE7CF), Color(0xFF0B2A24))
            in 17..21 -> PausePalette(Color(0xFF1B1937), Color(0xFF47335F), Color(0xFFD2C2FF), Color(0xFF22164A))
            else -> PausePalette(Color(0xFF090E1C), Color(0xFF1A2440), Color(0xFFAFC6FF), Color(0xFF0E1B3A))
        }
    }
}

/**
 * Eén ademhaling duurt tien seconden: vier in, zes uit. Een langere
 * uitademing kalmeert; met de standaardpauze van tien seconden doe je er
 * precies één.
 */
object Breath {
    const val INHALE_MS = 4_000L
    const val EXHALE_MS = 6_000L
    const val CYCLE_MS = INHALE_MS + EXHALE_MS

    /** 0 = helemaal uitgeademd, 1 = helemaal ingeademd. */
    fun level(elapsedMs: Long): Float {
        val t = elapsedMs.mod(CYCLE_MS)
        return if (t < INHALE_MS) ease(t / INHALE_MS.toFloat()) else 1f - ease((t - INHALE_MS) / EXHALE_MS.toFloat())
    }

    fun isInhaling(elapsedMs: Long): Boolean = elapsedMs.mod(CYCLE_MS) < INHALE_MS

    private fun ease(x: Float): Float = ((1 - cos(PI * x)) / 2).toFloat()
}

private enum class Phase { BREATHING, CHOOSING }

private val OnDark = Color.White

@Composable
fun PauseScreen(
    ui: PauseUi,
    celebrateCount: Int?,
    onOpen: (minutes: Int) -> Unit,
    onDismiss: () -> Unit,
    onCelebrationDone: () -> Unit,
    /** Alleen voor schermafbeeldingen in de tests: zet de klok vast. */
    frozenElapsedMs: Long? = null,
) {
    val palette = remember(ui.hour) { PausePalette.forHour(ui.hour) }
    val waitMs = ui.waitSeconds * 1000L

    var elapsed by remember { mutableLongStateOf(frozenElapsedMs ?: 0L) }
    LaunchedEffect(frozenElapsedMs) {
        if (frozenElapsedMs != null) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { elapsed = it - start }
    }

    var phase by rememberSaveable { mutableStateOf(Phase.BREATHING) }
    val waited = elapsed >= waitMs
    LaunchedEffect(waited) { if (waited) phase = Phase.CHOOSING }

    val view = LocalView.current
    val inhaling = Breath.isInhaling(elapsed)
    LaunchedEffect(inhaling) {
        if (ui.haptics && frozenElapsedMs == null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    LaunchedEffect(phase) {
        if (phase == Phase.CHOOSING && ui.haptics && frozenElapsedMs == null) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK,
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.top, palette.bottom))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            AppLine(ui)
            Spacer(Modifier.height(18.dp))
            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(300)) },
                label = "kop",
            ) { p ->
                Text(
                    text = headline(ui, p),
                    style = MaterialTheme.typography.headlineMedium,
                    color = OnDark,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.weight(1f))

            val ringAlpha by animateFloatAsState(if (phase == Phase.BREATHING) 1f else 0f, tween(800), label = "ring")
            BreathingCircle(
                level = Breath.level(elapsed),
                progress = (elapsed / waitMs.toFloat()).coerceIn(0f, 1f),
                ringAlpha = ringAlpha,
                palette = palette,
                label = if (inhaling) "Adem in" else "Adem uit",
            )
            Spacer(Modifier.height(14.dp))
            val remaining = ceil((waitMs - elapsed) / 1000.0).toInt().coerceAtLeast(0)
            Text(
                text = if (phase == Phase.BREATHING) "Nog $remaining ${if (remaining == 1) "seconde" else "seconden"}" else " ",
                style = MaterialTheme.typography.labelLarge,
                color = OnDark.copy(alpha = 0.6f),
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = ui.message,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 30.sp),
                color = OnDark.copy(alpha = 0.94f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = statsLine(ui),
                style = MaterialTheme.typography.labelLarge,
                color = OnDark.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            Box(Modifier.fillMaxWidth().heightIn(min = 148.dp), contentAlignment = Alignment.BottomCenter) {
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = {
                        (fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 4 }) togetherWith fadeOut(tween(200))
                    },
                    label = "knoppen",
                ) { p ->
                    when (p) {
                        Phase.BREATHING -> BreathingActions(ui, onDismiss)
                        Phase.CHOOSING -> ChoiceActions(ui, palette, onOpen, onDismiss)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (celebrateCount != null) {
            Celebration(celebrateCount, palette, onCelebrationDone)
        }
    }
}

private fun headline(ui: PauseUi, phase: Phase): String = when {
    phase == Phase.BREATHING && ui.mode == PauseActivity.Mode.TIME_UP -> "Tijd voor een adempauze"
    phase == Phase.BREATHING -> "Haal even rustig adem"
    ui.mode == PauseActivity.Mode.TIME_UP -> "Wil je echt verder?"
    else -> "Weet je het zeker?"
}

private fun statsLine(ui: PauseUi): String = when {
    ui.mode == PauseActivity.Mode.PREVIEW -> "Voorbeeld: er wordt niets bijgehouden"
    ui.attemptsToday <= 1 -> "Je eerste pauze voor ${ui.appLabel} vandaag"
    ui.openedToday == 0 -> "${ui.attemptsToday}e pauze vandaag · nog niet geopend"
    else -> "${ui.attemptsToday}e pauze vandaag · ${ui.openedToday}× geopend"
}

@Composable
private fun AppLine(ui: PauseUi) {
    val icon = rememberAppIcon(ui.packageName)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(10.dp))
        }
        val text = when (ui.mode) {
            PauseActivity.Mode.OPEN -> "Je opent ${ui.appLabel}"
            PauseActivity.Mode.PREVIEW -> "Voorbeeld · ${ui.appLabel}"
            PauseActivity.Mode.TIME_UP -> "Je ${Messages.minutes(ui.minutesUsed)} ${ui.appLabel} zitten erop"
        }
        Text(text, style = MaterialTheme.typography.titleSmall, color = OnDark.copy(alpha = 0.75f))
    }
}

@Composable
fun BreathingCircle(
    level: Float,
    progress: Float,
    ringAlpha: Float,
    palette: PausePalette,
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(252.dp)
            .semantics { contentDescription = "Ademhalingsoefening: $label" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            val accent = palette.accent
            val glow = r * (0.66f + 0.30f * level)
            drawCircle(
                brush = Brush.radialGradient(listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0f)), center, glow),
                radius = glow,
            )
            drawCircle(accent.copy(alpha = 0.14f), radius = r * (0.54f + 0.30f * level))
            drawCircle(accent.copy(alpha = 0.24f), radius = r * (0.45f + 0.25f * level))
            val core = r * (0.36f + 0.16f * level)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(lerp(accent, Color.White, 0.55f), accent),
                    center = center - Offset(core * 0.35f, core * 0.35f),
                    radius = core * 1.6f,
                ),
                radius = core,
            )

            if (ringAlpha > 0f) {
                val stroke = 3.dp.toPx()
                val ring = r - stroke
                drawCircle(Color.White.copy(alpha = 0.12f * ringAlpha), radius = ring, style = Stroke(stroke))
                drawArc(
                    color = accent.copy(alpha = ringAlpha),
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(center.x - ring, center.y - ring),
                    size = Size(ring * 2, ring * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(500)) },
            label = "adem",
        ) { text ->
            Text(text, style = MaterialTheme.typography.titleMedium, color = palette.onAccent)
        }
    }
}

@Composable
private fun BreathingActions(ui: PauseUi, onDismiss: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            border = BorderStroke(1.dp, OnDark.copy(alpha = 0.35f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = OnDark),
        ) {
            Text(
                if (ui.mode == PauseActivity.Mode.TIME_UP) "Stoppen" else "Nee, laat maar",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (ui.mode == PauseActivity.Mode.TIME_UP) "Doorgaan kan na de ademhaling" else "Openen kan na de ademhaling",
            style = MaterialTheme.typography.labelMedium,
            color = OnDark.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun ChoiceActions(ui: PauseUi, palette: PausePalette, onOpen: (Int) -> Unit, onDismiss: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
        ) {
            Text(
                if (ui.mode == PauseActivity.Mode.TIME_UP) "Stoppen" else "Nee, ik laat het",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (ui.mode == PauseActivity.Mode.TIME_UP) "Of ga nog even door" else "Of open ${ui.appLabel} voor",
            style = MaterialTheme.typography.labelLarge,
            color = OnDark.copy(alpha = 0.65f),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ui.sessionMinutes.forEach { minutes ->
                OutlinedButton(
                    onClick = { onOpen(minutes) },
                    modifier = Modifier.weight(1f).height(46.dp),
                    border = BorderStroke(1.dp, OnDark.copy(alpha = 0.3f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnDark),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text("$minutes min", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun Celebration(count: Int, palette: PausePalette, onDone: () -> Unit) {
    val grow = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        delay(1_300)
        onDone()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.top, palette.bottom)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(
                Modifier
                    .scale(grow.value)
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(palette.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Spa, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(56.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text("Goed gekozen.", style = MaterialTheme.typography.headlineMedium, color = OnDark)
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (count <= 1) "Je eerste teruggewonnen moment van vandaag." else "Al $count momenten teruggewonnen vandaag.",
                style = MaterialTheme.typography.bodyLarge,
                color = OnDark.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
