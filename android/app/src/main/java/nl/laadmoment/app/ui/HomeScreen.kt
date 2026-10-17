@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package nl.laadmoment.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.laadmoment.core.Briefing
import nl.laadmoment.core.DaySummary
import nl.laadmoment.core.Fmt
import nl.laadmoment.core.HourInsight
import nl.laadmoment.core.Ranking
import nl.laadmoment.core.Settings
import nl.laadmoment.core.Snapshot
import nl.laadmoment.core.weatherSymbol
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.math.roundToInt

private val DAY_TITLE = DateTimeFormatter.ofPattern("EEEE d MMMM", Fmt.NL)

@Composable
fun HomeScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onUpdate: ((Settings) -> Settings) -> Unit,
) {
    val s = state.snapshot
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text("Laadmoment", fontWeight = FontWeight.Bold)
                        Text(
                            text = subtitle(state),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Verversen") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Instellingen") }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.errors.isNotEmpty()) item { ErrorCard(state.errors, s.hasPrices) }
                item { BestHoursCard(s, state.loading) }
                item { ChoiceCard(s.settings, onUpdate) }
                if (s.insights.isNotEmpty()) item { StripCard(s) }
                val days = s.insights.groupBy { it.slot.start.atZone(s.zone).toLocalDate() }
                for ((date, hours) in days) {
                    item(key = "dag-$date") { HourListCard(s, hours) }
                }
                if (s.days.isNotEmpty()) item { SunDaysCard(s) }
                item { Footer(s) }
            }
        }
    }
}

/** Keeps a number and its unit on one line. */
private fun nb(text: String) = text.replace(' ', ' ')

private fun subtitle(state: UiState): String {
    val s = state.snapshot
    val fetched = s.forecast?.fetchedAt
    return when {
        state.loading && fetched == null -> "${s.settings.location.name} · ophalen…"
        fetched == null -> s.settings.location.name
        else -> {
            val day = Fmt.dayWord(fetched, s.now, s.zone).takeIf { it != "vandaag" }?.plus(" ") ?: ""
            "${s.settings.location.name} · bijgewerkt $day${Fmt.clock(fetched, s.zone)}"
        }
    }
}

/* ------------------------------------------------------------------ */
/* The answer: when to charge                                          */
/* ------------------------------------------------------------------ */

@Composable
private fun BestHoursCard(s: Snapshot, loading: Boolean) {
    val signals = LocalSignals.current
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(Modifier.background(Brush.verticalGradient(listOf(signals.bestBand, Color.Transparent)))) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    if (s.best.size == 1) "⚡  HET BESTE UUR OM TE LADEN" else "⚡  DE ${s.best.size} BESTE UREN OM TE LADEN",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = signals.best,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(10.dp))
                when {
                    s.windows.isNotEmpty() -> {
                        s.windows.take(4).forEachIndexed { i, window ->
                            if (i > 0) Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        Fmt.dayWord(window.from, s.now, s.zone).replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        Fmt.range(window.from, window.to, s.zone),
                                        fontSize = if (i == 0) 36.sp else 28.sp,
                                        lineHeight = if (i == 0) 40.sp else 32.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    if (window.sunny) Text("☀️ zon", style = MaterialTheme.typography.labelLarge, color = signals.sun)
                                    Text(
                                        "gem. ${nb(Fmt.cents(window.avgPrice))}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        if (window.length == 1) "1 uur" else "${window.length} uur",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        val more = s.windows.size - 4
                        if (more > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text("en nog $more ${if (more == 1) "los uur" else "losse uren"}, zie hieronder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Prijzen en weer ophalen…", style = MaterialTheme.typography.titleMedium)
                    }
                    else -> {
                        Text("Nog geen prijzen", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("Trek het scherm omlaag om prijzen en weer op te halen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                val advice = Briefing.now(s)
                if (advice != null && s.windows.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val now = s.insights.first { it.isNow }
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (advice.good) signals.best else signals.forLevel(now.priceLevel)))
                        Spacer(Modifier.width(10.dp))
                        Text(advice.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** How many hours, and whether the sun counts. */
@Composable
private fun ChoiceCard(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hoeveel uren laten zien?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StepButton("−", enabled = settings.bestHours > Settings.MIN_BEST_HOURS) {
                    onUpdate { it.copy(bestHours = (it.bestHours - 1).coerceAtLeast(Settings.MIN_BEST_HOURS)) }
                }
                Text(
                    settings.bestHours.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.Center,
                )
                StepButton("+", enabled = settings.bestHours < Settings.MAX_BEST_HOURS) {
                    onUpdate { it.copy(bestHours = (it.bestHours + 1).coerceAtMost(Settings.MAX_BEST_HOURS)) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ranking.entries.forEach { ranking ->
                    FilterChip(
                        colors = chipColors(),
                        selected = settings.ranking == ranking,
                        onClick = { onUpdate { it.copy(ranking = ranking) } },
                        label = { Text(ranking.label) },
                    )
                }
            }
            Text(settings.ranking.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ErrorCard(errors: List<String>, hasData: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("⚠️ Niet alles kon worden opgehaald", fontWeight = FontWeight.Bold, color = LocalSignals.current.warning)
            errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (hasData) Text("Je ziet de laatst bekende gegevens.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/* ------------------------------------------------------------------ */
/* The hour strip                                                      */
/* ------------------------------------------------------------------ */

/** 0 to 1: minutes of sunshine in the hour, or the sunlight when those are missing. */
private fun sunShare(hour: HourInsight): Float {
    val weather = hour.slot.weather ?: return 0f
    val minutes = weather.sunshineMinutes
    return if (minutes != null) (minutes / 60).toFloat().coerceIn(0f, 1f) else (weather.irradiance / 700).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun StripCard(s: Snapshot) {
    val hours = s.insights
    val prices = hours.mapNotNull { it.slot.allInPrice }
    val low = prices.minOrNull() ?: 0.0
    val high = prices.maxOrNull() ?: 1.0
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Komende uren", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("prijs in ct/kWh", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                itemsIndexed(hours, key = { _, h -> h.slot.start.epochSecond }) { i, hour ->
                    HourColumn(
                        s = s,
                        hour = hour,
                        joinLeft = hours.getOrNull(i - 1)?.isBest == true,
                        joinRight = hours.getOrNull(i + 1)?.isBest == true,
                        low = low,
                        high = high,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Legend(Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun HourColumn(s: Snapshot, hour: HourInsight, joinLeft: Boolean, joinRight: Boolean, low: Double, high: Double) {
    val signals = LocalSignals.current
    val local = hour.slot.start.atZone(s.zone)
    val divider = MaterialTheme.colorScheme.outline
    val band = RoundedCornerShape(
        topStart = if (joinLeft) 0.dp else 12.dp,
        bottomStart = if (joinLeft) 0.dp else 12.dp,
        topEnd = if (joinRight) 0.dp else 12.dp,
        bottomEnd = if (joinRight) 0.dp else 12.dp,
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(40.dp)
            .drawBehind {
                // A thin line where a new day starts.
                if (local.hour == 0) drawLine(divider, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx())
            }
            .clip(band)
            .background(if (hour.isBest) signals.bestBand else Color.Transparent)
            .padding(vertical = 6.dp),
    ) {
        val dayLabel = when {
            hour.isNow -> "nu"
            local.hour == 0 -> local.dayOfWeek.getDisplayName(TextStyle.SHORT, Fmt.NL)
            else -> ""
        }
        Text(dayLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(if (hour.isBest) "⚡" else "", fontSize = 14.sp, lineHeight = 18.sp, modifier = Modifier.height(20.dp))
        val weather = hour.slot.weather
        Text(weatherSymbol(weather?.weatherCode, weather?.isDay ?: true, weather?.irradiance), fontSize = 17.sp)
        val price = hour.slot.allInPrice
        Text(
            price?.let { (it * 100).roundToInt().toString() } ?: "–",
            fontSize = 11.sp,
            fontWeight = if (hour.isBest) FontWeight.Bold else FontWeight.Normal,
            color = if (price == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Box(Modifier.height(84.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            val fraction = if (price == null || high - low < 1e-6) 0.15f else (0.12 + 0.88 * (price - low) / (high - low)).toFloat()
            Box(
                Modifier
                    .width(20.dp)
                    .fillMaxHeight(fraction.coerceIn(0.05f, 1f))
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .background(if (hour.isBest) signals.best else signals.forLevel(hour.priceLevel)),
            )
        }
        Text(
            "%02d".format(local.hour),
            fontSize = 11.sp,
            fontWeight = if (hour.isNow) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(vertical = 3.dp),
        )
        Box(Modifier.height(28.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val sun = sunShare(hour)
            if (sun > 0.02f) {
                Box(
                    Modifier
                        .width(20.dp)
                        .fillMaxHeight(sun.coerceIn(0.08f, 1f))
                        .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                        .background(signals.sun),
                )
            }
        }
    }
}

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    val signals = LocalSignals.current
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LegendItem(signals.best, "⚡ beste uren")
        LegendItem(signals.cheap, "goedkoop")
        LegendItem(signals.normal, "gemiddeld")
        LegendItem(signals.expensive, "duur")
        LegendItem(signals.sun, "zon")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ------------------------------------------------------------------ */
/* Every hour in words                                                 */
/* ------------------------------------------------------------------ */

@Composable
private fun HourListCard(s: Snapshot, hours: List<HourInsight>) {
    val signals = LocalSignals.current
    val first = hours.first().slot.start
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "${Fmt.dayWord(first, s.now, s.zone).replaceFirstChar { it.uppercase() }} · ${DAY_TITLE.format(first.atZone(s.zone))}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            hours.forEach { hour ->
                val weather = hour.slot.weather
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (hour.isBest) signals.bestBand else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(
                        Fmt.clock(hour.slot.start, s.zone),
                        fontWeight = if (hour.isNow || hour.isBest) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.width(52.dp),
                    )
                    Text(weatherSymbol(weather?.weatherCode, weather?.isDay ?: true, weather?.irradiance), fontSize = 18.sp, modifier = Modifier.width(30.dp))
                    val minutes = weather?.sunshineMinutes?.roundToInt() ?: 0
                    Text(
                        if (minutes >= 5) "$minutes min zon" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = signals.sun,
                        modifier = Modifier.width(96.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    val price = hour.slot.allInPrice
                    Text(
                        price?.let(Fmt::cents) ?: "prijs volgt",
                        fontWeight = FontWeight.Bold,
                        color = if (price == null) MaterialTheme.colorScheme.onSurfaceVariant else signals.forLevel(hour.priceLevel),
                    )
                    Box(Modifier.width(78.dp), contentAlignment = Alignment.CenterEnd) {
                        when {
                            hour.isBest -> Surface(shape = CircleShape, color = signals.best) {
                                Text(
                                    "⚡ beste",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = signals.bestOn,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            hour.isNow -> Text("nu", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Sun per day and where the numbers come from                         */
/* ------------------------------------------------------------------ */

@Composable
private fun SunDaysCard(s: Snapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Zon en prijs per dag", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            s.days.forEach { day -> SunDayRow(s, day) }
        }
    }
}

@Composable
private fun SunDayRow(s: Snapshot, day: DaySummary) {
    val signals = LocalSignals.current
    val start = day.date.atStartOfDay(s.zone).toInstant()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Fmt.dayWord(start, s.now, s.zone).replaceFirstChar { it.uppercase() },
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            day.sunshineHours?.let {
                Text("☀️ ${nb(Fmt.decimal(it, 1))} zonuren", color = signals.sun, fontWeight = FontWeight.Bold)
            }
        }
        val sunrise = day.sunrise
        val sunset = day.sunset
        if (sunrise != null && sunset != null) {
            Text(
                "Zon op ${Fmt.clock(sunrise, s.zone)} · onder ${Fmt.clock(sunset, s.zone)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val cheapest = day.cheapest
        val dearest = day.dearest
        if (cheapest != null && dearest != null) {
            Text(
                "Goedkoopst ${Fmt.clock(cheapest.start, s.zone)} (${nb(Fmt.cents(cheapest.allInPrice ?: 0.0))}) · " +
                    "duurst ${Fmt.clock(dearest.start, s.zone)} (${nb(Fmt.cents(dearest.allInPrice ?: 0.0))})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("De prijzen voor deze dag komen rond 13:00 de dag ervoor.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Footer(s: Snapshot) {
    val lines = buildList {
        s.forecast?.let { add("Prijzen: ${it.priceSource.label}, inclusief energiebelasting, btw en ${Fmt.decimal(s.settings.tariffs.supplierMarkupPerKwh * 100, 0)} ct opslag.") }
        s.forecast?.priceNote?.let { add(it) }
        if (s.waitingForTomorrow && s.now.atZone(s.zone).hour < 15) add("De prijzen voor morgen komen meestal rond 13:00.")
        add("Weer en zon: Open-Meteo, voor ${s.settings.location.name}.")
    }
    Text(
        lines.joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
