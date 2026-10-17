package nl.laadmoment.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The at-a-glance layer: per hour a verdict on the price and the sun, and
 * whether it is one of the best hours. This is what the colours on screen
 * and in the widget are made of.
 */
enum class PriceLevel(val label: String) {
    CHEAP("goedkoop"),
    NORMAL("gemiddeld"),
    EXPENSIVE("duur"),
    UNKNOWN("nog niet bekend"),
}

enum class SunLevel(val label: String) {
    NONE("geen zon"),
    LITTLE("een beetje zon"),
    SOME("zon"),
    LOTS("volop zon"),
}

data class HourInsight(
    val slot: HourSlot,
    val priceLevel: PriceLevel,
    val sunLevel: SunLevel,
    /** One of the hours to charge in. */
    val isBest: Boolean,
    val isNow: Boolean,
)

data class DaySummary(
    val date: LocalDate,
    val sunshineHours: Double?,
    val sunrise: Instant?,
    val sunset: Instant?,
    val cheapest: HourSlot?,
    val dearest: HourSlot?,
    val pricedHours: Int,
)

object Insights {

    /** From the current hour onwards. */
    fun upcoming(timeline: List<HourSlot>, best: List<HourSlot>, now: Instant): List<HourInsight> {
        val upcoming = timeline.filter { it.end.isAfter(now) }
        val prices = upcoming.mapNotNull { it.allInPrice }.sorted()
        val low = quantile(prices, 0.25)
        val high = quantile(prices, 0.75)
        // A flat day is not "half cheap, half expensive"; colour it neutral.
        val flat = prices.isEmpty() || prices.last() - prices.first() < 0.01
        val bestStarts = best.map { it.start }.toSet()
        return upcoming.map { slot ->
            val price = slot.allInPrice
            HourInsight(
                slot = slot,
                priceLevel = when {
                    price == null -> PriceLevel.UNKNOWN
                    flat -> PriceLevel.NORMAL
                    price <= low -> PriceLevel.CHEAP
                    price >= high -> PriceLevel.EXPENSIVE
                    else -> PriceLevel.NORMAL
                },
                sunLevel = sunLevel(slot.weather?.irradiance ?: 0.0),
                isBest = slot.start in bestStarts,
                isNow = !slot.start.isAfter(now) && slot.end.isAfter(now),
            )
        }
    }

    /** Sunlight in W/m2 to words. */
    fun sunLevel(irradiance: Double): SunLevel = when {
        irradiance < 25 -> SunLevel.NONE
        irradiance < 150 -> SunLevel.LITTLE
        irradiance < 400 -> SunLevel.SOME
        else -> SunLevel.LOTS
    }

    fun days(timeline: List<HourSlot>, weather: WeatherForecast?, zone: ZoneId): List<DaySummary> {
        val byDay = timeline.groupBy { it.start.atZone(zone).toLocalDate() }
        return byDay.keys.sorted().map { date ->
            val priced = byDay.getValue(date).filter { it.hasPrice }
            val sun = weather?.days?.firstOrNull { it.date == date.toString() }
            DaySummary(
                date = date,
                sunshineHours = sun?.sunshineHours,
                sunrise = sun?.sunriseEpoch?.let(Instant::ofEpochSecond),
                sunset = sun?.sunsetEpoch?.let(Instant::ofEpochSecond),
                cheapest = priced.minByOrNull { it.allInPrice!! },
                dearest = priced.maxByOrNull { it.allInPrice!! },
                pricedHours = priced.size,
            )
        }
    }

    /** Linear interpolation between closest ranks, on an already sorted list. */
    fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val pos = (sorted.size - 1) * q.coerceIn(0.0, 1.0)
        val lower = floor(pos).toInt()
        val upper = min(sorted.lastIndex, lower + 1)
        return sorted[lower] + (sorted[upper] - sorted[lower]) * max(0.0, pos - lower)
    }
}

/**
 * A weather code from Open-Meteo (WMO) as one symbol. A clear hour in which
 * the sun is too low to matter shows the sun on the horizon, so a sunny
 * symbol never promises sun that is not there.
 */
fun weatherSymbol(code: Int?, isDay: Boolean, irradiance: Double? = null): String = when {
    isDay && irradiance != null && irradiance < 15 && (code ?: 1) in 0..2 -> "🌅"
    else -> symbolFor(code, isDay)
}

private fun symbolFor(code: Int?, isDay: Boolean): String = when (code) {
    0 -> if (isDay) "☀️" else "🌙"
    1 -> if (isDay) "🌤️" else "🌙"
    2 -> if (isDay) "⛅" else "☁️"
    3 -> "☁️"
    45, 48 -> "🌫️"
    in 51..57 -> "🌦️"
    in 61..67, in 80..82 -> "🌧️"
    in 71..77, 85, 86 -> "🌨️"
    in 95..99 -> "⛈️"
    else -> if (isDay) "🌤️" else "🌙"
}
