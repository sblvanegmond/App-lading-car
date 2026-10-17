@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package nl.laadmoment.app.ui

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
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.laadmoment.core.Briefing
import nl.laadmoment.core.DaySummary
import nl.laadmoment.core.Fmt
import nl.laadmoment.core.HourInsight
import nl.laadmoment.core.Insights
import nl.laadmoment.core.Mode
import nl.laadmoment.core.Settings
import nl.laadmoment.core.Snapshot
import nl.laadmoment.core.energyNeed
import nl.laadmoment.core.weatherSymbol
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.math.max
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
                item { HeroCard(s, state.loading) }
                if (s.insights.isNotEmpty()) item { StripCard(s) }
                item { NeedCard(s, onUpdate) }
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

private fun subtitle(state: UiState): String {
    val s = state.snapshot
    val place = s.settings.location.name.ifBlank { "Jouw locatie" }
    val fetched = s.forecast?.fetchedAt
    return when {
        state.loading && fetched == null -> "$place · ophalen…"
        fetched == null -> place
        else -> "$place · bijgewerkt ${Fmt.dayWord(fetched, s.now, s.zone).takeIf { it != "vandaag" }?.plus(" ") ?: ""}${Fmt.clock(fetched, s.zone)}"
    }
}

/* ------------------------------------------------------------------ */
/* The answer                                                          */
/* ------------------------------------------------------------------ */

@Composable
private fun HeroCard(s: Snapshot, loading: Boolean) {
    val signals = LocalSignals.current
    val plan = s.plan
    val block = plan?.blocks?.firstOrNull()
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(Modifier.background(Brush.verticalGradient(listOf(signals.bestBand, Color.Transparent)))) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    "⚡  BESTE MOMENT OM TE LADEN",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = signals.best,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(10.dp))
                when {
                    block != null -> {
                        Text(
                            Fmt.dayWord(block.from, s.now, s.zone).replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            Fmt.range(block.from, block.to, s.zone),
                            fontSize = 40.sp,
                            lineHeight = 44.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        val reasons = Insights.reasons(block, s.insights)
                        if (reasons.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                reasons.forEach { reason ->
                                    Pill(reason, if (reason.contains("zon")) signals.sunBand else signals.bestBand)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "${Fmt.kwh(block.kwh)} in ${Fmt.hours(block.hours)} · ${Fmt.euro(block.cost)} · ${Fmt.cents(block.avgPricePerKwh)}/kWh",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val extras = buildList {
                            if (block.solarSharePct >= 1) add("${block.solarSharePct.roundToInt()}% uit eigen zon")
                            val saving = plan?.comparison?.savingsVsImmediate ?: 0.0
                            if (saving > 0.05) add("${Fmt.euro(saving)} goedkoper dan nu meteen laden")
                        }
                        if (extras.isNotEmpty()) {
                            Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        plan?.blocks.orEmpty().drop(1).take(3).forEach { more ->
                            Text(
                                "Daarna ook ${Briefing.window(more, s.now, s.zone)} · ${Fmt.kwh(more.kwh)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    plan != null && plan.need.grossKwh <= 1e-6 -> {
                        Text("Accu is vol genoeg", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("Je accustand zit al op je doel. Schuif hieronder als je meer wilt laden.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    plan != null -> {
                        Text("Geen laadmoment", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    }
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Prijzen en weer ophalen…", style = MaterialTheme.typography.titleMedium)
                    }
                    else -> {
                        Text("Nog geen gegevens", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("Trek het scherm omlaag om prijzen en weer op te halen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                plan?.warnings?.forEach { warning ->
                    Spacer(Modifier.height(8.dp))
                    Text("⚠️ $warning", style = MaterialTheme.typography.bodyMedium, color = signals.warning)
                }

                val advice = Briefing.now(s.insights, plan, s.now, s.zone)
                if (advice != null) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape)
                                .background(if (advice.good) signals.best else signals.forLevel(s.insights.first { it.isNow }.priceLevel)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(advice.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, background: Color, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = background, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ErrorCard(errors: List<String>, hasData: Boolean) {
    val signals = LocalSignals.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("⚠️ Niet alles kon worden opgehaald", fontWeight = FontWeight.Bold, color = signals.warning)
            errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (hasData) Text("Je ziet de laatst bekende gegevens.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/* ------------------------------------------------------------------ */
/* The hour strip                                                      */
/* ------------------------------------------------------------------ */

@Composable
private fun StripCard(s: Snapshot) {
    val hours = s.insights
    val prices = hours.mapNotNull { it.slot.allInPrice }
    val low = prices.minOrNull() ?: 0.0
    val high = prices.maxOrNull() ?: 1.0
    val pvMax = max(0.5, hours.maxOf { it.slot.pvKw })
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Komende uren", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("ct/kWh · zon in kW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        pvMax = pvMax,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Legend(Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun HourColumn(s: Snapshot, hour: HourInsight, joinLeft: Boolean, joinRight: Boolean, low: Double, high: Double, pvMax: Double) {
    val signals = LocalSignals.current
    val local = hour.slot.start.atZone(s.zone)
    val divider = MaterialTheme.colorScheme.outline
    val band = when {
        !hour.isBest -> RoundedCornerShape(0.dp)
        else -> RoundedCornerShape(
            topStart = if (joinLeft) 0.dp else 12.dp,
            bottomStart = if (joinLeft) 0.dp else 12.dp,
            topEnd = if (joinRight) 0.dp else 12.dp,
            bottomEnd = if (joinRight) 0.dp else 12.dp,
        )
    }
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
        Text(weatherSymbol(hour.slot.weather?.weatherCode, hour.slot.weather?.isDay ?: true), fontSize = 17.sp)
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
            val pv = hour.slot.pvKw
            if (pv > 0.02) {
                Box(
                    Modifier
                        .width(20.dp)
                        .fillMaxHeight((pv / pvMax).toFloat().coerceIn(0.06f, 1f))
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
        LegendItem(signals.best, "⚡ laden")
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
/* How much to charge, until when, and how                             */
/* ------------------------------------------------------------------ */

@Composable
private fun NeedCard(s: Snapshot, onUpdate: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    val car = s.settings.car
    var dragging by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    val saved = car.currentSocPct.toFloat().coerceIn(0f, 100f)
    val range = dragging ?: saved..car.targetSocPct.toFloat().coerceIn(saved, 100f)
    val need = energyNeed(car)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hoeveel moet erbij?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${range.start.roundToInt()}% → ${range.endInclusive.roundToInt()}%", style = MaterialTheme.typography.titleMedium)
            }
            RangeSlider(
                value = range,
                onValueChange = { value ->
                    dragging = value
                    val from = (value.start / 5).roundToInt() * 5.0
                    val to = (value.endInclusive / 5).roundToInt() * 5.0
                    onUpdate { it.copy(car = it.car.copy(currentSocPct = from, targetSocPct = to)) }
                },
                onValueChangeFinished = { dragging = null },
                valueRange = 0f..100f,
                steps = 19,
            )
            val chargeHours = if (car.chargerKw > 0) need.grossKwh / car.chargerKw else 0.0
            Text(
                if (need.grossKwh <= 1e-6) "Niets te laden."
                else "${Fmt.kwh(need.grossKwh)} uit het stopcontact · ongeveer ${Fmt.hours(chargeHours)} op ${Fmt.kw(car.chargerKw)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            Text("Klaar om", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !s.settings.readyByEnabled,
                    onClick = { onUpdate { it.copy(readyByEnabled = false) } },
                    label = { Text("Geen haast") },
                )
                FilterChip(
                    selected = s.settings.readyByEnabled,
                    onClick = {
                        if (s.settings.readyByEnabled) {
                            pickTime(context, s.settings.readyBy) { time -> onUpdate { it.copy(readyBy = time) } }
                        } else {
                            onUpdate { it.copy(readyByEnabled = true) }
                        }
                    },
                    label = { Text(if (s.settings.readyByEnabled) "Om ${s.settings.readyBy} ✎" else "Om ${s.settings.readyBy}") },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Strategie", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { mode ->
                    FilterChip(
                        selected = s.settings.mode == mode,
                        onClick = { onUpdate { it.copy(mode = mode) } },
                        label = { Text(mode.label) },
                    )
                }
            }
            Text(s.settings.mode.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        Fmt.clock(hour.slot.start, s.zone),
                        fontWeight = if (hour.isNow || hour.isBest) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.width(52.dp),
                    )
                    Text(weatherSymbol(weather?.weatherCode, weather?.isDay ?: true), fontSize = 18.sp, modifier = Modifier.width(30.dp))
                    Text(
                        if (hour.slot.pvKw >= 0.05) Fmt.kw(hour.slot.pvKw) else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = signals.sun,
                        modifier = Modifier.width(64.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        val price = hour.slot.allInPrice
                        Text(
                            price?.let(Fmt::cents) ?: "prijs volgt",
                            fontWeight = FontWeight.Bold,
                            color = if (price == null) MaterialTheme.colorScheme.onSurfaceVariant else signals.forLevel(hour.priceLevel),
                        )
                        val withSun = hour.chargePricePerKwh
                        if (price != null && withSun != null && withSun < price - 0.005) {
                            Text("met zon ${Fmt.cents(withSun)}", style = MaterialTheme.typography.labelSmall, color = signals.sun)
                        }
                    }
                    Box(Modifier.width(78.dp), contentAlignment = Alignment.CenterEnd) {
                        when {
                            hour.isBest -> Surface(shape = CircleShape, color = signals.best) {
                                Text(
                                    "⚡ laden",
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
            Text("Zon per dag", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
            Text("☀️ ${Fmt.kwh(day.solarKwh)}", color = signals.sun, fontWeight = FontWeight.Bold)
        }
        val facts = buildList {
            day.sunshineHours?.let { add("${Fmt.decimal(it, 1)} zonuren") }
            if (day.sunrise != null && day.sunset != null) add("zon ${Fmt.range(day.sunrise!!, day.sunset!!, s.zone)}")
        }
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val cheapest = day.cheapest
        val dearest = day.dearest
        if (cheapest != null && dearest != null) {
            Text(
                "Goedkoopst ${Fmt.clock(cheapest.start, s.zone)} (${Fmt.cents(cheapest.allInPrice ?: 0.0)}) · duurst ${Fmt.clock(dearest.start, s.zone)} (${Fmt.cents(dearest.allInPrice ?: 0.0)})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Prijzen voor deze dag komen rond 13:00 de dag ervoor.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Footer(s: Snapshot) {
    val lines = buildList {
        s.forecast?.let { add("Prijzen: ${it.priceSource.label}, all-in met jouw opslag, energiebelasting en btw.") }
        s.forecast?.priceNote?.let { add(it) }
        if (s.waitingForTomorrow && s.now.atZone(s.zone).hour < 15) add("De prijzen voor morgen komen meestal rond 13:00.")
        add("Weer en zoninstraling: Open-Meteo. Zonopbrengst berekend voor ${Fmt.decimal(s.settings.pv.totalKwp, 1)} kWp.")
    }
    Text(
        lines.joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
