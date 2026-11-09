package nl.adempauze.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.adempauze.core.DayCounts
import nl.adempauze.repository
import nl.adempauze.ui.theme.NumberStyle
import nl.adempauze.util.InstalledApps
import java.time.LocalDate

/** Kleuren voor de grafiek, gecontroleerd op kleurenblindheid en contrast. */
private data class ChartColors(val dismissed: Color, val opened: Color)

@Composable
private fun chartColors(): ChartColors =
    if (isSystemInDarkTheme()) ChartColors(Color(0xFF199E70), Color(0xFFD95926))
    else ChartColors(Color(0xFF1BAF7A), Color(0xFFEB6834))

private val monthShort = listOf("jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec")

private fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Vandaag"
    today.minusDays(1) -> "Gisteren"
    else -> "${dayShort.getValue(date.dayOfWeek)} ${date.dayOfMonth} ${monthShort[date.monthValue - 1]}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit, today: LocalDate = LocalDate.now()) {
    val context = LocalContext.current
    val repo = context.repository
    val stats by repo.stats.collectAsState()
    val apps by repo.apps.collectAsState()

    val days = remember(today) { (6 downTo 0).map { today.minusDays(it.toLong()) } }
    val perDay = days.map { d -> stats[d].orEmpty().values.fold(DayCounts()) { a, c -> a + c } }
    val week = perDay.fold(DayCounts()) { a, c -> a + c }
    val previous = (7..13).map { today.minusDays(it.toLong()) }
        .map { d -> stats[d].orEmpty().values.fold(DayCounts()) { a, c -> a + c } }
        .fold(DayCounts()) { a, c -> a + c }

    val perApp = days.flatMap { d -> stats[d].orEmpty().entries }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, list) -> list.fold(DayCounts()) { a, c -> a + c } }
        .entries.sortedByDescending { it.value.attempts }

    var selected by remember { mutableIntStateOf(6) }
    val colors = chartColors()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Je week") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Terug") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Afgelopen 7 dagen") {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(week.dismissed.toString(), style = NumberStyle)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "keer bewust niet geopend",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatPill(week.attempts.toString(), "pauzes")
                    StatPill(week.opened.toString(), "geopend")
                    StatPill(if (week.attempts == 0) "–" else "${week.dismissed * 100 / week.attempts}%", "gelaten")
                }
                trend(week.attempts, previous.attempts)?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (week.attempts < previous.attempts) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(title = "Per dag") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LegendItem(colors.dismissed, "Gelaten")
                    LegendItem(colors.opened, "Geopend")
                }
                Spacer(Modifier.height(12.dp))
                val sel = perDay[selected]
                Text(
                    "${dayTitle(days[selected], today)}: ${sel.dismissed} gelaten · ${sel.opened} geopend",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                WeekChart(days, perDay, selected, colors, today) { selected = it }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tik op een dag voor de cijfers.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "Per app") {
                if (perApp.isEmpty()) {
                    Text(
                        "Nog geen pauzes deze week. Open een van je apps en kijk wat er gebeurt.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                perApp.forEach { (pkg, c) ->
                    val label = apps.firstOrNull { it.packageName == pkg }?.label
                        ?: remember(pkg) { InstalledApps.label(context, pkg) } ?: pkg
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(pkg, 36.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${pauzes(c.attempts)} · ${c.dismissed}× gelaten · ${c.opened}× geopend",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            if (c.attempts == 0) "–" else "${c.dismissed * 100 / c.attempts}%",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun trend(now: Int, before: Int): String? {
    if (before == 0 || now == before) return null
    val pct = ((before - now) * 100) / before
    return if (now < before) "$pct% minder pauzes dan de week ervoor. Je grijpt minder vaak naar je telefoon."
    else "${-pct}% meer pauzes dan de week ervoor. Elke pauze is een moment om bewust te kiezen."
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Gestapelde staafjes: onderaan wat je liet liggen, daarboven wat je opende.
 * Dun, met een afgeronde bovenkant en een kier van 2 dp tussen de delen.
 */
@Composable
private fun WeekChart(
    days: List<LocalDate>,
    values: List<DayCounts>,
    selected: Int,
    colors: ChartColors,
    today: LocalDate,
    onSelect: (Int) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.surfaceContainer
    val max = values.maxOf { it.dismissed + it.opened }.coerceAtLeast(4)
    val description = days.indices.joinToString("; ") { i ->
        "${dayTitle(days[i], today)}: ${values[i].dismissed} gelaten, ${values[i].opened} geopend"
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .semantics { contentDescription = description }
            .pointerInput(days) {
                detectTapGestures { pos ->
                    val slot = size.width / days.size.toFloat()
                    onSelect((pos.x / slot).toInt().coerceIn(0, days.lastIndex))
                }
            },
    ) {
        val labelH = 26.dp.toPx()
        val topPad = 22.dp.toPx()
        val chartH = size.height - labelH - topPad
        val slot = size.width / days.size
        val barW = (slot * 0.46f).coerceAtMost(28.dp.toPx())
        val gap = 2.dp.toPx()
        val radius = 4.dp.toPx()
        val baseY = topPad + chartH

        days.forEachIndexed { i, date ->
            val cx = slot * i + slot / 2
            if (i == selected) {
                drawRoundRect(
                    highlight,
                    topLeft = Offset(slot * i + 2.dp.toPx(), 0f),
                    size = Size(slot - 4.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }
            val v = values[i]
            val total = v.dismissed + v.opened
            val left = cx - barW / 2
            var top = baseY
            if (v.dismissed > 0) {
                val h = chartH * v.dismissed / max
                val isTop = v.opened == 0
                drawBar(left, top - h, barW, h, colors.dismissed, if (isTop) radius else 0f)
                top -= h + gap
            }
            if (v.opened > 0) {
                val h = (chartH * v.opened / max - gap).coerceAtLeast(2.dp.toPx())
                drawBar(left, top - h, barW, h, colors.opened, radius)
                top -= h
            }
            if (total > 0) {
                val t = measurer.measure(total.toString(), TextStyle(fontSize = 11.sp, color = muted))
                drawText(t, topLeft = Offset(cx - t.size.width / 2f, top - t.size.height - 3.dp.toPx()))
            }
            val isToday = date == today
            val label = measurer.measure(
                dayShort.getValue(date.dayOfWeek),
                TextStyle(
                    fontSize = 12.sp,
                    color = if (isToday || i == selected) ink else muted,
                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                ),
            )
            drawText(label, topLeft = Offset(cx - label.size.width / 2f, baseY + 7.dp.toPx()))
        }
        drawLine(baseline, Offset(0f, baseY), Offset(size.width, baseY), strokeWidth = 1.dp.toPx())
    }
}

/** Een staafdeel met (optioneel) alleen de bovenhoeken afgerond; de onderkant staat op de basislijn. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBar(
    x: Float, y: Float, w: Float, h: Float, color: Color, topRadius: Float,
) {
    if (h <= 0f) return
    val r = topRadius.coerceAtMost(h)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = x, top = y, right = x + w, bottom = y + h,
                topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
            ),
        )
    }
    drawPath(path, color)
}
