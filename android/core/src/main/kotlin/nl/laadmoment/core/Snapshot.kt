package nl.laadmoment.core

import java.time.Instant
import java.time.ZoneId

/**
 * Everything one screen needs, worked out from the settings and the last
 * fetched data. The app, the widget and the notifications all build the
 * same snapshot, so they always tell the same story.
 */
data class Snapshot(
    val settings: Settings,
    val forecast: Forecast?,
    val now: Instant,
    val zone: ZoneId,
    val timeline: List<HourSlot>,
    val plan: Plan?,
    val insights: List<HourInsight>,
    val days: List<DaySummary>,
) {
    val hasPrices: Boolean get() = timeline.any { it.hasPrice }

    /** True before tomorrow's prices are out, so the screen can say when they come. */
    val waitingForTomorrow: Boolean
        get() = forecast != null && !DataLoader.coversTomorrow(forecast.prices, now, zone)

    companion object {
        fun of(settings: Settings, forecast: Forecast?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Snapshot {
            val today = now.atZone(zone).toLocalDate()
            val startOfToday = today.atStartOfDay(zone).toInstant()
            // Keep tomorrow's weather even before its prices are published.
            val until = today.plusDays(2).atStartOfDay(zone).toInstant()
            val weather = forecast?.weather?.takeIf { it.key == settings.weatherKey() }
            val timeline = buildTimeline(forecast?.prices.orEmpty(), weather, settings, until)
                .filter { !it.start.isBefore(startOfToday) }
            val plan = if (timeline.any { it.hasPrice }) Planner.plan(timeline, settings, now, zone) else null
            return Snapshot(
                settings = settings,
                forecast = forecast,
                now = now,
                zone = zone,
                timeline = timeline,
                plan = plan,
                insights = Insights.upcoming(timeline, plan, settings, now),
                days = Insights.days(timeline, weather, zone),
            )
        }
    }
}
