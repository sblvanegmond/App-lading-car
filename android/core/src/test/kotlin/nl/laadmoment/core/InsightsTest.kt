package nl.laadmoment.core

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun forecastOf(now: java.time.Instant, prices: List<Pair<String, Double>>, sun: Map<String, Double> = emptyMap()): Forecast {
    val settings = Settings()
    val weather = (prices.map { it.first } + sun.keys).distinct().map { start ->
        WeatherHour(at(start).epochSecond, radiation = sun[start] ?: 0.0, weatherCode = 0, isDay = true)
    }
    // Shift the market price so that the all-in price is exactly the given number.
    val vat = 1 + settings.tariffs.vatPct / 100
    val offset = settings.tariffs.supplierMarkupPerKwh + settings.tariffs.energyTaxPerKwh
    return Forecast(
        fetchedAtEpoch = now.epochSecond,
        prices = prices.map { (start, allIn) -> PricePoint(at(start).epochSecond, allIn / vat - offset) },
        priceSource = PriceSource.ENERGYZERO,
        weather = WeatherForecast(settings.weatherKey(), weather, emptyList()),
    )
}

class InsightsTest {
    private val now = at("2026-10-17T10:20")

    private val day = listOf(
        slot("2026-10-17T09:00", 0.40), // already over
        slot("2026-10-17T10:00", 0.30),
        slot("2026-10-17T11:00", 0.26, sun = 300.0),
        slot("2026-10-17T12:00", 0.20, sun = 650.0),
        slot("2026-10-17T13:00", 0.22, sun = 600.0),
        slot("2026-10-17T18:00", 0.38),
        slot("2026-10-17T19:00", 0.36),
        slot("2026-10-17T20:00", null),
    )

    @Test
    fun `hours are judged from the current hour onwards`() {
        val insights = Insights.upcoming(day, emptyList(), now)
        assertEquals(at("2026-10-17T10:00"), insights.first().slot.start)
        assertTrue(insights.first().isNow)
        assertEquals(1, insights.count { it.isNow })
    }

    @Test
    fun `the cheapest quarter is green, the dearest quarter red`() {
        val insights = Insights.upcoming(day, emptyList(), now).associateBy { Fmt.clock(it.slot.start, ZONE) }
        assertEquals(PriceLevel.CHEAP, insights.getValue("12:00").priceLevel)
        assertEquals(PriceLevel.EXPENSIVE, insights.getValue("18:00").priceLevel)
        assertEquals(PriceLevel.NORMAL, insights.getValue("10:00").priceLevel)
        assertEquals(PriceLevel.UNKNOWN, insights.getValue("20:00").priceLevel)
    }

    @Test
    fun `a flat day is not painted half red`() {
        val flat = (10..15).map { slot("2026-10-17T$it:00", 0.25 + it / 10_000.0) }
        assertTrue(Insights.upcoming(flat, emptyList(), now).all { it.priceLevel == PriceLevel.NORMAL })
    }

    @Test
    fun `the best hours are marked`() {
        val best = BestHours.pick(day, Settings(bestHours = 2), now)
        val marked = Insights.upcoming(day, best, now).filter { it.isBest }.map { Fmt.clock(it.slot.start, ZONE) }
        assertEquals(listOf("12:00", "13:00"), marked)
    }

    @Test
    fun `sun levels follow the sunlight`() {
        assertEquals(SunLevel.NONE, Insights.sunLevel(0.0))
        assertEquals(SunLevel.LITTLE, Insights.sunLevel(100.0))
        assertEquals(SunLevel.SOME, Insights.sunLevel(300.0))
        assertEquals(SunLevel.LOTS, Insights.sunLevel(700.0))
    }

    @Test
    fun `a clear hour without usable sun does not show a full sun`() {
        assertEquals("☀️", weatherSymbol(0, isDay = true, irradiance = 500.0))
        assertEquals("🌅", weatherSymbol(0, isDay = true, irradiance = 3.0))
        assertEquals("☁️", weatherSymbol(3, isDay = true, irradiance = 3.0))
        assertEquals("🌙", weatherSymbol(0, isDay = false, irradiance = 0.0))
    }

    @Test
    fun `day summaries find the cheapest and the dearest hour`() {
        val summary = Insights.days(day, null, ZONE).single()
        assertEquals(0.20, summary.cheapest!!.allInPrice!!, 1e-9)
        assertEquals(0.40, summary.dearest!!.allInPrice!!, 1e-9)
        assertEquals(7, summary.pricedHours)
    }
}

class TextTest {
    private val now = at("2026-10-17T10:20")

    @Test
    fun `numbers read the Dutch way`() {
        assertEquals("19,6 ct", Fmt.cents(0.196))
        assertEquals("0,0", Fmt.decimal(-0.01))
    }

    @Test
    fun `day words stay right across midnight`() {
        assertEquals("vandaag", Fmt.dayWord(at("2026-10-17T23:59"), now, ZONE))
        assertEquals("morgen", Fmt.dayWord(at("2026-10-18T00:01"), now, ZONE))
        assertEquals("overmorgen", Fmt.dayWord(at("2026-10-19T12:00"), now, ZONE))
        assertEquals("dinsdag", Fmt.dayWord(at("2026-10-20T12:00"), now, ZONE))
    }

    @Test
    fun `the notification names the windows and their price`() {
        val forecast = forecastOf(
            now,
            listOf("2026-10-17T12:00" to 0.20, "2026-10-17T13:00" to 0.22, "2026-10-17T18:00" to 0.40, "2026-10-18T03:00" to 0.21),
            sun = mapOf("2026-10-17T12:00" to 650.0),
        )
        val snapshot = Snapshot.of(Settings(bestHours = 3), forecast, now, ZONE)
        assertEquals("Laden: vandaag 12:00 – 14:00", Briefing.title(snapshot))
        val body = Briefing.body(snapshot)
        assertContains(body, "12:00 – 14:00 · gem. 21,0 ct ☀️")
        assertContains(body, "morgen 03:00 – 04:00 · gem. 21,0 ct")
    }

    @Test
    fun `no prices gives an honest message`() {
        val snapshot = Snapshot.of(Settings(), null, now, ZONE)
        assertEquals("Laadmoment", Briefing.title(snapshot))
        assertContains(Briefing.body(snapshot), "Nog geen stroomprijzen")
    }

    @Test
    fun `the advice for right now points to the next best hour`() {
        val forecast = forecastOf(now, listOf("2026-10-17T10:00" to 0.35, "2026-10-17T11:00" to 0.30, "2026-10-17T13:00" to 0.18))
        val advice = assertNotNull(Briefing.now(Snapshot.of(Settings(bestHours = 1), forecast, now, ZONE)))
        assertFalse(advice.good)
        assertContains(advice.text, "vandaag 13:00")
    }

    @Test
    fun `the advice says go when this is one of the best hours`() {
        val forecast = forecastOf(now, listOf("2026-10-17T10:00" to 0.10, "2026-10-17T11:00" to 0.30), sun = mapOf("2026-10-17T10:00" to 500.0))
        val advice = assertNotNull(Briefing.now(Snapshot.of(Settings(bestHours = 1), forecast, now, ZONE)))
        assertTrue(advice.good)
        assertContains(advice.text, "beste uren")
    }
}

class SnapshotTest {
    @Test
    fun `a snapshot shows tomorrow's sun before tomorrow's prices are out`() {
        val now = at("2026-10-17T09:00")
        val prices = (0 until 24).map { PricePoint(at("2026-10-17T00:00").epochSecond + it * 3600L, 0.1 + it / 100.0) }
        val hours = (0 until 48).map { i ->
            val start = at("2026-10-17T00:00").epochSecond + i * 3600L
            val sun = if (i % 24 in 10..15) 600.0 else 0.0
            WeatherHour(start, radiation = sun, sunshineMinutes = if (sun > 0) 60.0 else 0.0, weatherCode = 0, isDay = i % 24 in 8..18)
        }
        val settings = Settings()
        val forecast = Forecast(now.epochSecond, prices, PriceSource.ENERGYZERO, null, WeatherForecast(settings.weatherKey(), hours, emptyList()))
        val snapshot = Snapshot.of(settings, forecast, now, ZONE)

        assertEquals(at("2026-10-17T00:00"), snapshot.timeline.first().start)
        assertEquals(at("2026-10-18T23:00"), snapshot.timeline.last().start)
        assertTrue(snapshot.waitingForTomorrow)
        assertEquals(at("2026-10-17T09:00"), snapshot.insights.first().slot.start)
        assertEquals(5, snapshot.best.size)
        assertTrue(snapshot.best.all { it.hasPrice && !it.start.isBefore(at("2026-10-17T09:00")) })
        assertEquals(listOf("09:00 – 14:00"), snapshot.windows.map { Fmt.range(it.from, it.to, ZONE) })
        assertEquals(2, snapshot.days.size)
        assertEquals(0, snapshot.days[1].pricedHours)
    }

    @Test
    fun `a forecast for another place is not used for the sun`() {
        val now = at("2026-10-17T09:00")
        val start = at("2026-10-17T12:00").epochSecond
        val forecast = Forecast(
            now.epochSecond,
            listOf(PricePoint(start, 0.1)),
            PriceSource.ENERGYZERO,
            null,
            WeatherForecast("ergens anders", listOf(WeatherHour(start, radiation = 800.0)), emptyList()),
        )
        val snapshot = Snapshot.of(Settings(), forecast, now, ZONE)
        assertEquals(null, snapshot.timeline.single().weather)
    }
}
