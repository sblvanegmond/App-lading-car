package nl.laadmoment.core

import java.time.Instant
import java.time.ZoneId

/**
 * `./gradlew :core:liveCheck` — asks the real services and prints what the
 * app would show. Run by the build workflow so a change in an API's answer
 * shows up in the log before it shows up on the phone.
 */
fun main() {
    val zone = ZoneId.of("Europe/Amsterdam")
    val now = Instant.now()
    val settings = Settings()
    val loader = DataLoader()
    var failures = 0

    for (source in PriceSource.entries) {
        try {
            val points = loader.fetchPrices(source, now, zone)
            val first = points.firstOrNull()
            val last = points.lastOrNull()
            println(
                "${source.label}: ${points.size} uren" +
                    (if (first != null && last != null) ", ${first.start} t/m ${last.start}" else "") +
                    ", gemiddeld ${Fmt.cents(points.map { it.marketPrice }.average())} excl. btw",
            )
        } catch (e: Exception) {
            failures++
            println("${source.label}: MISLUKT - ${e.message}")
        }
    }

    val weather = try {
        loader.loadWeather(settings).also { w ->
            val sunny = w.hours.count { it.irradiance > 150 }
            println("Open-Meteo: ${w.hours.size} uren, $sunny met zon, ${w.days.size} dagen")
        }
    } catch (e: Exception) {
        failures++
        println("Open-Meteo: MISLUKT - ${e.message}")
        null
    }

    val prices = runCatching { loader.loadPrices(settings, now, zone) }.getOrNull()
    if (prices != null) {
        val until = now.atZone(zone).toLocalDate().plusDays(2).atStartOfDay(zone).toInstant()
        val timeline = buildTimeline(prices.points, weather, settings, until)
        val plan = Planner.plan(timeline, settings, now, zone)
        println()
        println(Briefing.title(plan, now, zone))
        println(Briefing.body(plan))
        Insights.upcoming(timeline, plan, settings, now).forEach { h ->
            val w = h.slot.weather
            println(
                "  ${Fmt.dayWord(h.slot.start, now, zone).padEnd(8)} ${Fmt.clock(h.slot.start, zone)}  " +
                    "${weatherSymbol(w?.weatherCode, w?.isDay ?: true, w?.irradiance)} ${Fmt.kw(h.slot.pvKw).padStart(7)}  " +
                    "${(h.slot.allInPrice?.let(Fmt::cents) ?: "?").padStart(8)}  ${h.priceLevel.label.padEnd(15)}" +
                    if (h.isBest) "  <- laden" else "",
            )
        }
    }
    if (failures > 0) System.exit(1)
}
