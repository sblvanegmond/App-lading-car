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
            val sunny = w.hours.count { (it.sunshineMinutes ?: 0.0) > 30 }
            println("Open-Meteo: ${w.hours.size} uren, $sunny zonnig, ${w.days.size} dagen")
        }
    } catch (e: Exception) {
        failures++
        println("Open-Meteo: MISLUKT - ${e.message}")
        null
    }

    val prices = runCatching { loader.loadPrices(settings, now, zone) }.getOrNull()
    if (prices != null && weather != null) {
        val snapshot = Snapshot.of(settings, Forecast(now.epochSecond, prices.points, prices.source, prices.note, weather), now, zone)
        println()
        println(Briefing.title(snapshot))
        println(Briefing.body(snapshot))
        snapshot.insights.forEach { h ->
            val w = h.slot.weather
            val sun = w?.sunshineMinutes?.takeIf { it >= 5 }?.let { "${it.toInt()} min zon" } ?: ""
            println(
                "  ${Fmt.dayWord(h.slot.start, now, zone).padEnd(8)} ${Fmt.clock(h.slot.start, zone)}  " +
                    "${weatherSymbol(w?.weatherCode, w?.isDay ?: true, w?.irradiance)} ${sun.padEnd(11)}" +
                    "${(h.slot.allInPrice?.let(Fmt::cents) ?: "?").padStart(8)}  ${h.priceLevel.label.padEnd(15)}" +
                    if (h.isBest) "  <- beste uur" else "",
            )
        }
    }
    if (failures > 0) System.exit(1)
}
