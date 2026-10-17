package nl.laadmoment.core

import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

fun at(text: String): Instant = LocalDateTime.parse(text).atZone(ZONE).toInstant()

/** One hour starting at local time [start], with an all-in price and sunlight in W/m2. */
fun slot(start: String, allIn: Double?, sun: Double = 0.0): HourSlot {
    val begin = at(start)
    return HourSlot(
        start = begin,
        marketPrice = allIn,
        allInPrice = allIn,
        weather = WeatherHour(begin.epochSecond, radiation = sun, sunshineMinutes = if (sun > 100) 60.0 else 0.0),
    )
}

fun clocks(hours: List<HourSlot>): List<String> = hours.map { Fmt.clock(it.start, ZONE) }

class PricingTest {
    @Test
    fun `all-in price stacks markup, energy tax and VAT onto the market price`() {
        val tariffs = Tariffs(supplierMarkupPerKwh = 0.02, energyTaxPerKwh = 0.1, vatPct = 21.0)
        assertEquals((0.08 + 0.02 + 0.1) * 1.21, Pricing.allInPrice(0.08, tariffs), 1e-9)
    }
}

class BestHoursTest {
    private val now = at("2026-10-17T10:20")

    private val day = listOf(
        slot("2026-10-17T09:00", 0.05), // already over: never chosen
        slot("2026-10-17T10:00", 0.30),
        slot("2026-10-17T11:00", 0.26, sun = 350.0),
        slot("2026-10-17T12:00", 0.24, sun = 700.0),
        slot("2026-10-17T13:00", 0.23, sun = 650.0),
        slot("2026-10-17T18:00", 0.40),
        slot("2026-10-18T03:00", 0.21),
        slot("2026-10-18T04:00", 0.22),
        slot("2026-10-18T13:00", null, sun = 700.0), // no price yet
    )

    @Test
    fun `the cheapest hours still to come are chosen, in time order`() {
        val best = BestHours.pick(day, Settings(bestHours = 3), now)
        assertEquals(listOf("13:00", "03:00", "04:00"), clocks(best))
    }

    @Test
    fun `the hour you are in still counts`() {
        val best = BestHours.pick(listOf(slot("2026-10-17T10:00", 0.10), slot("2026-10-17T11:00", 0.30)), Settings(bestHours = 1), now)
        assertEquals(listOf("10:00"), clocks(best))
    }

    @Test
    fun `with the sun counted a sunny hour can beat a cheaper dark one`() {
        val settings = Settings(bestHours = 2, ranking = Ranking.PRICE_AND_SUN)
        val best = BestHours.pick(day, settings, now)
        assertEquals(listOf("12:00", "13:00"), clocks(best))
    }

    @Test
    fun `a fully sunny hour counts as half the price`() {
        assertEquals(0.12, BestHours.score(slot("2026-10-17T12:00", 0.24, sun = 900.0), Ranking.PRICE_AND_SUN)!!, 1e-9)
        assertEquals(0.24, BestHours.score(slot("2026-10-17T12:00", 0.24, sun = 900.0), Ranking.PRICE)!!, 1e-9)
        assertEquals(-0.02, BestHours.score(slot("2026-10-17T12:00", -0.02, sun = 900.0), Ranking.PRICE_AND_SUN)!!, 1e-9)
    }

    @Test
    fun `asking for more hours than are known gives what there is`() {
        val best = BestHours.pick(day, Settings(bestHours = 12), now)
        assertEquals(7, best.size)
        assertTrue(best.all { it.hasPrice })
    }

    @Test
    fun `the number of hours stays within reason`() {
        assertEquals(1, BestHours.pick(day, Settings(bestHours = 0), now).size)
    }

    @Test
    fun `consecutive best hours make one window`() {
        val best = BestHours.pick(day, Settings(bestHours = 4), now)
        val windows = BestHours.windows(best)
        assertEquals(listOf("12:00 – 14:00", "03:00 – 05:00"), windows.map { Fmt.range(it.from, it.to, ZONE) })
        assertTrue(windows[0].sunny)
        assertFalse(windows[1].sunny)
        assertEquals(0.235, windows[0].avgPrice, 1e-9)
    }

    @Test
    fun `nothing priced means nothing chosen`() {
        assertTrue(BestHours.pick(listOf(slot("2026-10-17T12:00", null)), Settings(), now).isEmpty())
    }

    @Test
    fun `the morning time rolls to tomorrow once it has passed`() {
        assertEquals(at("2026-10-18T07:00"), nextTimeOccurrence(now, "07:00", ZONE))
        assertEquals(at("2026-10-17T18:00"), nextTimeOccurrence(now, "18:00", ZONE))
    }
}

class TimelineTest {
    @Test
    fun `prices and weather line up on the hour and unpriced hours are dropped by default`() {
        val start = at("2026-10-17T12:00").epochSecond
        val prices = listOf(PricePoint(start, 0.1))
        val weather = WeatherForecast(
            key = "",
            hours = listOf(WeatherHour(start, radiation = 500.0), WeatherHour(start + 3600, radiation = 300.0)),
            days = emptyList(),
        )
        val settings = Settings()
        val timeline = buildTimeline(prices, weather, settings)
        assertEquals(1, timeline.size)
        assertEquals(500.0, timeline[0].weather!!.irradiance, 1e-9)
        assertEquals(Pricing.allInPrice(0.1, settings.tariffs), timeline[0].allInPrice!!, 1e-9)

        val longer = buildTimeline(prices, weather, settings, until = Instant.ofEpochSecond(start + 7200))
        assertEquals(2, longer.size)
        assertFalse(longer[1].hasPrice)
    }
}
