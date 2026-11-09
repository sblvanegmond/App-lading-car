package nl.adempauze.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.adempauze.core.WorkHours
import nl.adempauze.util.rememberAppIcon
import java.time.DayOfWeek

@Composable
fun AppIcon(packageName: String, size: Dp = 40.dp) {
    val icon = rememberAppIcon(packageName)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(if (icon == null) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent),
    ) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(size))
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/**
 * "Weet je het zeker?" voor dingen die de pauze uitzetten. Ook hier eerst
 * even wachten: dat voorkomt dat je Adempauze in een impuls uitschakelt.
 */
@Composable
fun HoldOnDialog(
    title: String,
    text: String,
    confirmLabel: String,
    seconds: Int = 10,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var left by remember { mutableIntStateOf(seconds) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1_000)
            left--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { 1f - left / seconds.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = left == 0) {
                Text(if (left == 0) confirmLabel else "$confirmLabel ($left)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Toch niet", fontWeight = FontWeight.SemiBold) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(title: String, minute: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state) } },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("Opslaan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}

@Composable
fun StatPill(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun InfoRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Opmaak van tijden en dagen --------------------------------------------------

val dayShort = mapOf(
    DayOfWeek.MONDAY to "ma",
    DayOfWeek.TUESDAY to "di",
    DayOfWeek.WEDNESDAY to "wo",
    DayOfWeek.THURSDAY to "do",
    DayOfWeek.FRIDAY to "vr",
    DayOfWeek.SATURDAY to "za",
    DayOfWeek.SUNDAY to "zo",
)

/** "1 pauze", "3 pauzes". */
fun pauzes(n: Int): String = if (n == 1) "1 pauze" else "$n pauzes"

fun formatMinute(m: Int): String = "${m / 60}:${(m % 60).toString().padStart(2, '0')}"

/** "ma–vr 9:00–17:30", "ma, wo, vr 8:00–12:00". */
fun describeWorkHours(w: WorkHours): String {
    val days = DayOfWeek.entries.filter { it in w.days }
    val dayText = when {
        days.isEmpty() -> "geen dagen"
        days.size == 7 -> "elke dag"
        days.zipWithNext().all { (a, b) -> b.value == a.value + 1 } && days.size >= 3 ->
            "${dayShort[days.first()]}–${dayShort[days.last()]}"
        else -> days.joinToString(", ") { dayShort.getValue(it) }
    }
    return "$dayText ${formatMinute(w.startMinute)}–${formatMinute(w.endMinute)}"
}

fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Goedemorgen"
    in 12..17 -> "Goedemiddag"
    in 18..23 -> "Goedenavond"
    else -> "Goedenacht"
}
