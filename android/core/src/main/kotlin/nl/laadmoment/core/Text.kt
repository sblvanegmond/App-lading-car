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
}

/**
 * The words around the best hours: the headline on screen, the widget and
 * the notifications all come from here so they never disagree.
 */
object Briefing {

    /** "vandaag 13:00 – 16:00" */
    fun window(window: Window, now: Instant, zone: ZoneId): String =
        "${Fmt.dayWord(window.from, now, zone)} ${Fmt.range(window.from, window.to, zone)}"

    /** "13:00 – 16:00 · gem. 27,1 ct ☀️", with the day when it is not today. */
    fun windowLine(window: Window, now: Instant, zone: ZoneId): String {
        val day = Fmt.dayWord(window.from, now, zone)
        val time = Fmt.range(window.from, window.to, zone)
        val sun = if (window.sunny) " ☀️" else ""
        return "${if (day == "vandaag") "" else "$day "}$time · gem. ${Fmt.cents(window.avgPrice)}$sun"
    }

    /** "Laden: vandaag 13:00 – 16:00" */
    fun title(snapshot: Snapshot): String {
        val first = snapshot.windows.firstOrNull() ?: return "Laadmoment"
        return "Laden: ${window(first, snapshot.now, snapshot.zone)}"
    }

    /** The other windows and their prices, one line for a notification. */
    fun body(snapshot: Snapshot): String {
        if (snapshot.windows.isEmpty()) return "Nog geen stroomprijzen binnen. Open de app om het opnieuw te proberen."
        val lines = snapshot.windows.take(3).map { windowLine(it, snapshot.now, snapshot.zone) }
        val more = snapshot.windows.size - 3
        return (lines + if (more > 0) listOf("en nog $more") else emptyList()).joinToString(" · ")
    }

    data class Advice(val text: String, val good: Boolean)

    /** One sentence about the hour you are in right now. */
    fun now(snapshot: Snapshot): Advice? {
        val current = snapshot.insights.firstOrNull { it.isNow } ?: return null
        if (current.isBest) {
            val sun = if (current.sunLevel >= SunLevel.SOME) " De zon schijnt." else ""
            return Advice("Nu is een van je beste uren om te laden.$sun", true)
        }
        val price = current.slot.allInPrice?.let { " (${Fmt.cents(it)})" } ?: ""
        val next = snapshot.windows.firstOrNull { it.from.isAfter(snapshot.now) }
            ?: return Advice("Nu ${current.priceLevel.label}$price.", current.priceLevel == PriceLevel.CHEAP)
        val at = "${Fmt.dayWord(next.from, snapshot.now, snapshot.zone)} ${Fmt.clock(next.from, snapshot.zone)}"
        return Advice("Nu ${current.priceLevel.label}$price. Het volgende beste uur is $at.", false)
    }
}
