package nl.laadmoment.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

private const val HOUR_SECONDS = 3600L

/** One hour on the shared axis of price and sun. */
data class HourSlot(
    val start: Instant,
    /** EUR/kWh excluding VAT, null when the price is not published yet. */
    val marketPrice: Double?,
    /** What you pay per kWh, null without a market price. */
    val allInPrice: Double?,
    val weather: WeatherHour?,
) {
    val end: Instant get() = start.plusSeconds(HOUR_SECONDS)
    val hasPrice: Boolean get() = allInPrice != null
}

/**
 * Merge prices and weather into one hourly timeline.
 *
 * The weather reaches further than the prices. By default everything after
 * the last known price is dropped; pass [until] to keep those hours (without
 * a price) up to that moment, so tomorrow's sun shows before its prices do.
 */
fun buildTimeline(
    prices: List<PricePoint>,
    weather: WeatherForecast?,
    settings: Settings,
    until: Instant? = null,
): List<HourSlot> {
    val priceBy = prices.associateBy { it.startEpoch.floorDiv(HOUR_SECONDS) * HOUR_SECONDS }
    val weatherBy = weather?.hours.orEmpty().associateBy { it.startEpoch.floorDiv(HOUR_SECONDS) * HOUR_SECONDS }
    val lastPriced = priceBy.keys.maxOrNull()
    return (priceBy.keys + weatherBy.keys).sorted()
        .filter { key ->
            if (until != null) key < until.epochSecond || key <= (lastPriced ?: Long.MIN_VALUE)
            else lastPriced == null || key <= lastPriced
        }
        .map { key ->
            val market = priceBy[key]?.marketPrice
            HourSlot(
                start = Instant.ofEpochSecond(key),
                marketPrice = market,
                allInPrice = market?.let { Pricing.allInPrice(it, settings.tariffs) },
                weather = weatherBy[key],
            )
        }
}

/** Consecutive best hours, read as one window: "13:00 – 16:00". */
data class Window(
    val hours: List<HourSlot>,
) {
    val from: Instant get() = hours.first().start
    val to: Instant get() = hours.last().end
    val length: Int get() = hours.size
    val avgPrice: Double get() = hours.mapNotNull { it.allInPrice }.average()
    val sunny: Boolean get() = hours.any { Insights.sunLevel(it.weather?.irradiance ?: 0.0) >= SunLevel.SOME }
}

object BestHours {

    /** Strong sun on a flat surface; from here on an hour counts as fully sunny. */
    private const val FULL_SUN_WM2 = 700.0

    /** How much cheaper a fully sunny hour counts under [Ranking.PRICE_AND_SUN]. */
    private const val SUN_DISCOUNT = 0.5

    /** 0 in the dark, 1 in strong sun. */
    fun sunFraction(slot: HourSlot): Double = ((slot.weather?.irradiance ?: 0.0) / FULL_SUN_WM2).coerceIn(0.0, 1.0)

    /** Lower is better; null for an hour without a price. */
    fun score(slot: HourSlot, ranking: Ranking): Double? {
        val price = slot.allInPrice ?: return null
        return when (ranking) {
            Ranking.PRICE -> price
            // A negative price only gets better with sun, never worse.
            Ranking.PRICE_AND_SUN -> if (price <= 0) price else price * (1 - SUN_DISCOUNT * sunFraction(slot))
        }
    }

    /**
     * The best [Settings.bestHours] of the hours still to come, in time order.
     * The hour you are in counts too: you can still plug in now.
     */
    fun pick(timeline: List<HourSlot>, settings: Settings, now: Instant): List<HourSlot> {
        val count = settings.bestHours.coerceIn(Settings.MIN_BEST_HOURS, Settings.MAX_BEST_HOURS)
        return timeline
            .filter { it.end.isAfter(now) && it.hasPrice }
            .sortedWith(compareBy<HourSlot> { score(it, settings.ranking) }.thenBy { it.start })
            .take(count)
            .sortedBy { it.start }
    }

    fun windows(best: List<HourSlot>): List<Window> {
        val windows = mutableListOf<MutableList<HourSlot>>()
        for (hour in best.sortedBy { it.start }) {
            val last = windows.lastOrNull()
            if (last != null && last.last().end == hour.start) last += hour else windows += mutableListOf(hour)
        }
        return windows.map { Window(it) }
    }
}

/** The next time the clock reads [hhmm], strictly after [from]. */
fun nextTimeOccurrence(from: Instant, hhmm: String, zone: ZoneId): Instant {
    val time = parseClock(hhmm) ?: LocalTime.of(7, 0)
    var candidate = from.atZone(zone).toLocalDate().atTime(time).atZone(zone)
    if (!candidate.toInstant().isAfter(from)) candidate = candidate.toLocalDate().plusDays(1).atTime(time).atZone(zone)
    return candidate.toInstant()
}

fun parseClock(hhmm: String): LocalTime? {
    val parts = hhmm.trim().split(':', '.')
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    if (h !in 0..23 || m !in 0..59) return null
    return LocalTime.of(h, m)
}
