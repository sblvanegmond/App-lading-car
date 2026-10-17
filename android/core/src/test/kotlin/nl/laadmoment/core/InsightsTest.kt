package nl.laadmoment.core

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InsightsTest {
    private val now = at("2026-10-17T10:20")

    private val day = listOf(
        slot("2026-10-17T09:00", 0.40), // already over
        slot("2026-10-17T10:00", 0.30),
        slot("2026-10-17T11:00", 0.26, surplusKw = 1.5, irradiance = 300.0),
        slot("2026-10-17T12:00", 0.20, surplusKw = 3.0, irradiance = 650.0),
        slot("2026-10-17T13:00", 0.22, surplusKw = 3.0, irradiance = 600.0),
        slot("2026-10-17T18:00", 0.38),
        slot("2026-10-17T19:00", 0.36),
        slot("2026-10-17T20:00", null),
    )

    @Test
    fun `hours are judged from the current hour onwards`() {
        val insights = Insights.upcoming(day, null, Settings(), now)
        assertEquals(at("2026-10-17T10:00"), insights.first().slot.start)
        assertTrue(insights.first().isNow)
        assertEquals(1, insights.count { it.isNow })
    }

    @Test
    fun `the cheapest quarter is green, the dearest quarter red`() {
        val insights = Insights.upcoming(day, null, Settings(), now).associateBy { Fmt.clock(it.slot.start, ZONE) }
        assertEquals(PriceLevel.CHEAP, insights.getValue("12:00").priceLevel)
        assertEquals(PriceLevel.EXPENSIVE, insights.getValue("18:00").priceLevel)
        assertEquals(PriceLevel.NORMAL, insights.getValue("10:00").priceLevel)
        assertEquals(PriceLevel.UNKNOWN, insights.getValue("20:00").priceLevel)
    }

    @Test
    fun `a flat day is not painted half red`() {
        val flat = (10..15).map { slot("2026-10-17T$it:00", 0.25 + it / 10_000.0) }
        assertTrue(Insights.upcoming(flat, null, Settings(), now).all { it.priceLevel == PriceLevel.NORMAL })
    }

    @Test
    fun `sun levels follow the irradiance`() {
        assertEquals(SunLevel.NONE, Insights.sunLevel(0.0))
        assertEquals(SunLevel.LITTLE, Insights.sunLevel(100.0))
        assertEquals(SunLevel.SOME, Insights.sunLevel(300.0))
        assertEquals(SunLevel.LOTS, Insights.sunLevel(700.0))
    }

    @Test
    fun `own sun lowers what an hour of charging costs`() {
        val settings = settingsNeeding(10.0, chargerKw = 10.0)
        val sunny = Insights.chargePrice(slot("2026-10-17T12:00", 0.20, surplusKw = 4.0), settings)!!
        assertEquals((6 * 0.20 + 4 * 0.05) / 10, sunny, 1e-9)
    }

    @Test
    fun `the planned hours are marked and explained`() {
        val settings = settingsNeeding(20.0, chargerKw = 10.0)
        val timeline = day.drop(1)
        val plan = Planner.plan(timeline, settings, now, ZONE)
        val insights = Insights.upcoming(timeline, plan, settings, now)
        val best = insights.filter { it.isBest }.map { Fmt.clock(it.slot.start, ZONE) }
        assertEquals(listOf("12:00", "13:00"), best)
        val reasons = Insights.reasons(plan.blocks.first(), insights)
        assertTrue(reasons.any { it.contains("zon") })
        assertTrue(reasons.any { it.contains("goedkoop") })
    }

    @Test
    fun `day summaries add up the sun and find the extremes`() {
        val summary = Insights.days(day, null, ZONE).single()
        assertEquals(7.5, summary.solarKwh, 1e-9)
        assertEquals(0.20, summary.cheapest!!.allInPrice!!, 1e-9)
        assertEquals(0.40, summary.dearest!!.allInPrice!!, 1e-9)
        assertEquals(7, summary.pricedHours)
    }
}

class TextTest {
    private val now = at("2026-10-17T10:20")

    @Test
    fun `numbers read the Dutch way`() {
        assertEquals("€ 5,10", Fmt.euro(5.1))
        assertEquals("-€ 1,25", Fmt.euro(-1.25))
        assertEquals("19,6 ct", Fmt.cents(0.196))
        assertEquals("0,0", Fmt.decimal(-0.01))
        assertEquals("1 uur 30 min", Fmt.hours(1.5))
        assertEquals("45 min", Fmt.hours(0.75))
    }

    @Test
    fun `day words stay right across midnight`() {
        assertEquals("vandaag", Fmt.dayWord(at("2026-10-17T23:59"), now, ZONE))
        assertEquals("morgen", Fmt.dayWord(at("2026-10-18T00:01"), now, ZONE))
        assertEquals("overmorgen", Fmt.dayWord(at("2026-10-19T12:00"), now, ZONE))
        assertEquals("dinsdag", Fmt.dayWord(at("2026-10-20T12:00"), now, ZONE))
    }

    @Test
    fun `the notification names the window, the energy and the cost`() {
        val timeline = listOf(slot("2026-10-17T12:00", 0.20, surplusKw = 3.0), slot("2026-10-17T18:00", 0.40))
        val plan = Planner.plan(timeline, settingsNeeding(10.0, chargerKw = 10.0), now, ZONE)
        assertEquals("Laden vandaag 12:00 – 13:00", Briefing.title(plan, now, ZONE))
        val body = Briefing.body(plan)
        assertContains(body, "10,0 kWh voor € 1,55")
        assertContains(body, "30% eigen zon")
    }

    @Test
    fun `a full battery says so instead of pretending`() {
        val plan = Planner.plan(listOf(slot("2026-10-17T12:00", 0.2)), settingsNeeding(0.0), now, ZONE)
        assertContains(Briefing.body(plan), "niet nodig")
        assertEquals("Laadmoment", Briefing.title(plan, now, ZONE))
    }

    @Test
    fun `the advice for right now points to the better hour`() {
        val timeline = listOf(slot("2026-10-17T10:00", 0.35), slot("2026-10-17T11:00", 0.30), slot("2026-10-17T13:00", 0.18))
        val settings = settingsNeeding(5.0, chargerKw = 10.0)
        val plan = Planner.plan(timeline, settings, now, ZONE)
        val advice = assertNotNull(Briefing.now(Insights.upcoming(timeline, plan, settings, now), plan, now, ZONE))
        assertFalse(advice.good)
        assertContains(advice.text, "vandaag 13:00")
    }

    @Test
    fun `the advice says go when this hour is in the plan`() {
        val timeline = listOf(slot("2026-10-17T10:00", 0.10, surplusKw = 3.0, irradiance = 500.0), slot("2026-10-17T11:00", 0.30))
        val settings = settingsNeeding(5.0, chargerKw = 10.0)
        val plan = Planner.plan(timeline, settings, now, ZONE)
        val advice = assertNotNull(Briefing.now(Insights.upcoming(timeline, plan, settings, now), plan, now, ZONE))
        assertTrue(advice.good)
        assertContains(advice.text, "goed moment")
    }
}

class SnapshotTest {
    @Test
    fun `a snapshot shows tomorrow's sun before tomorrow's prices are out`() {
        val now = at("2026-10-17T09:00")
        val prices = (0 until 24).map { PricePoint(at("2026-10-17T00:00").epochSecond + it * 3600L, 0.1 + it / 100.0) }
        val hours = (0 until 48).map { i ->
            val start = at("2026-10-17T00:00").epochSecond + i * 3600L
            val local = i % 24
            val sun = if (local in 10..15) 600.0 else 0.0
            WeatherHour(start, listOf(sun), sun, 15.0, 0.0, 0.0, 0, local in 8..18)
        }
        val settings = Settings()
        val forecast = Forecast(now.epochSecond, prices, PriceSource.ENERGYZERO, null, WeatherForecast(settings.weatherKey(), hours, emptyList()))
        val snapshot = Snapshot.of(settings, forecast, now, ZONE)

        assertEquals(at("2026-10-17T00:00"), snapshot.timeline.first().start)
        assertEquals(at("2026-10-18T23:00"), snapshot.timeline.last().start)
        assertTrue(snapshot.waitingForTomorrow)
        assertEquals(at("2026-10-17T09:00"), snapshot.insights.first().slot.start)
        val plan = assertNotNull(snapshot.plan)
        // No deadline: the plan stays within the hours that have a price.
        assertTrue(plan.slots.all { it.slot.hasPrice })
        assertEquals(2, snapshot.days.size)
        assertTrue(snapshot.days[1].solarKwh > 5)
        assertEquals(0, snapshot.days[1].pricedHours)
    }

    @Test
    fun `a forecast for another roof is not used for the sun`() {
        val now = at("2026-10-17T09:00")
        val start = at("2026-10-17T12:00").epochSecond
        val forecast = Forecast(
            now.epochSecond,
            listOf(PricePoint(start, 0.1)),
            PriceSource.ENERGYZERO,
            null,
            WeatherForecast("ergens anders", listOf(WeatherHour(start, listOf(800.0), 800.0)), emptyList()),
        )
        val snapshot = Snapshot.of(Settings(), forecast, now, ZONE)
        assertEquals(0.0, snapshot.timeline.single().pvKw)
    }
}
