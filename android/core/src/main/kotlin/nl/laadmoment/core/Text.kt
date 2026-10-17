package nl.laadmoment.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** Dutch number and time formatting, the same on the phone and in tests. */
object Fmt {
    val NL: Locale = Locale.forLanguageTag("nl-NL")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    fun decimal(value: Double, digits: Int = 1): String {
        val v = if (value.isFinite()) value else 0.0
        // Avoid "-0,0" for values that round to zero.
        val clean = if (abs(v) < 0.5 * Math.pow(10.0, -digits.toDouble())) 0.0 else v
        return String.format(NL, "%.${digits}f", clean)
    }

    fun cents(eurPerKwh: Double): String = "${decimal(eurPerKwh * 100, 1)} ct"

    fun euro(amount: Double): String {
        val text = decimal(abs(amount), 2)
        return if (amount < -0.005) "-€ $text" else "€ $text"
    }

    fun kwh(value: Double): String = "${decimal(value, 1)} kWh"

    fun kw(value: Double): String = "${decimal(value, 1)} kW"

    fun clock(instant: Instant, zone: ZoneId): String = CLOCK.format(instant.atZone(zone))

    fun range(from: Instant, to: Instant, zone: ZoneId): String = "${clock(from, zone)} – ${clock(to, zone)}"

    /** "vandaag", "morgen", "overmorgen" or the weekday. */
    fun dayWord(instant: Instant, now: Instant, zone: ZoneId): String {
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), instant.atZone(zone).toLocalDate())
        return when (days) {
            0L -> "vandaag"
            1L -> "morgen"
            2L -> "overmorgen"
            else -> instant.atZone(zone).dayOfWeek.getDisplayName(TextStyle.FULL, NL)
        }
    }

    fun hours(value: Double): String {
        val minutes = Math.round(value * 60)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h uur"
            else -> "$h uur $m min"
        }
    }
}

/**
 * The words around a plan: the headline on screen, the widget, and the
 * morning notification all come from here so they never disagree.
 */
object Briefing {

    fun window(block: ChargeBlock, now: Instant, zone: ZoneId): String =
        "${Fmt.dayWord(block.from, now, zone)} ${Fmt.range(block.from, block.to, zone)}"

    /** "Laden vandaag 12:00 – 15:00" */
    fun title(plan: Plan?, now: Instant, zone: ZoneId): String {
        val first = plan?.blocks?.firstOrNull() ?: return "Laadmoment"
        return "Laden ${window(first, now, zone)}"
    }

    /** "26,0 kWh voor € 5,10 · 19,6 ct/kWh · 24% eigen zon" */
    fun body(plan: Plan?): String {
        if (plan == null) return "Nog geen gegevens. Open de app om te verversen."
        if (plan.blocks.isEmpty()) {
            return if (plan.need.grossKwh <= 1e-6) "De accu zit al op je doel, laden is niet nodig."
            else plan.warnings.firstOrNull() ?: "Geen bruikbaar laadmoment gevonden."
        }
        val parts = mutableListOf(
            "${Fmt.kwh(plan.totals.kwh)} voor ${Fmt.euro(plan.totals.cost)}",
            "${Fmt.cents(plan.totals.avgPricePerKwh)}/kWh",
        )
        if (plan.totals.solarSharePct >= 1) parts += "${plan.totals.solarSharePct.toInt()}% eigen zon"
        val saving = plan.comparison?.savingsVsImmediate ?: 0.0
        if (saving > 0.05) parts += "${Fmt.euro(saving)} goedkoper dan nu"
        if (plan.blocks.size > 1) parts += "${plan.blocks.size} blokken"
        return parts.joinToString(" · ")
    }

    data class Advice(val text: String, val good: Boolean)

    /** One sentence about the hour you are in right now. */
    fun now(insights: List<HourInsight>, plan: Plan?, now: Instant, zone: ZoneId): Advice? {
        val current = insights.firstOrNull { it.isNow } ?: return null
        if (plan != null && plan.need.grossKwh <= 1e-6) return Advice("Je accu zit op je doel. Niets te doen.", true)
        if (current.isBest) {
            val sun = if (current.sunLevel >= SunLevel.SOME) " De zon helpt mee." else ""
            return Advice("Nu is een goed moment om te laden.$sun", true)
        }
        val price = current.slot.allInPrice?.let { " (${Fmt.cents(it)})" } ?: ""
        val next = plan?.blocks?.firstOrNull { it.from.isAfter(now) }
            ?: return Advice("Nu ${current.priceLevel.label}$price.", current.priceLevel == PriceLevel.CHEAP)
        val at = "${Fmt.dayWord(next.from, now, zone)} ${Fmt.clock(next.from, zone)}"
        return if (current.priceLevel == PriceLevel.CHEAP) {
            Advice("Nu goedkoop$price, maar $at is nog beter.", false)
        } else {
            Advice("Nu ${current.priceLevel.label}$price. Wacht liever tot $at.", false)
        }
    }
}
