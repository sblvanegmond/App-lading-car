package nl.laadmoment.core

import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

fun at(text: String): Instant = LocalDateTime.parse(text).atZone(ZONE).toInstant()

/** One hour starting at local time [start], with an all-in price and solar surplus. */
fun slot(start: String, allIn: Double?, surplusKw: Double = 0.0, irradiance: Double = 0.0): HourSlot {
    val begin = at(start)
    return HourSlot(
        start = begin,
        marketPrice = allIn,
        allInPrice = allIn,
        pvKw = surplusKw,
        surplusKw = surplusKw,
        weather = WeatherHour(begin.epochSecond, gti = listOf(irradiance), ghi = irradiance),
    )
}

/** A battery that needs exactly [kwh] from the wall: no losses, 100 kWh pack. */
fun settingsNeeding(kwh: Double, chargerKw: Double = 11.0, mode: Mode = Mode.BALANCED, readyBy: String? = null) = Settings(
    car = CarSettings(
        batteryKwh = 100.0,
        chargerKw = chargerKw,
        minChargeKw = 1.4,
        currentSocPct = 20.0,
        targetSocPct = 20.0 + kwh,
        chargeLossPct = 0.0,
    ),
    mode = mode,
    readyByEnabled = readyBy != null,
    readyBy = readyBy ?: "07:30",
    tariffs = Tariffs(feedInPerKwh = 0.05, netMetering = false),
)

fun hours(plan: Plan): List<String> = plan.slots.map { Fmt.clock(it.slot.start, ZONE) }

class PricingAndSolarTest {
    @Test
    fun `all-in price stacks markup, energy tax and VAT onto the market price`() {
        val tariffs = Tariffs(supplierMarkupPerKwh = 0.02, energyTaxPerKwh = 0.1, vatPct = 21.0)
        assertEquals((0.08 + 0.02 + 0.1) * 1.21, Pricing.allInPrice(0.08, tariffs), 1e-9)
    }

    @Test
    fun `net metering makes own sun worth the full purchase price`() {
        assertEquals(0.3, Pricing.solarOpportunityCost(0.3, Tariffs(netMetering = true)), 1e-9)
        assertEquals(0.05, Pricing.solarOpportunityCost(0.3, Tariffs(netMetering = false, feedInPerKwh = 0.05)), 1e-9)
    }

    @Test
    fun `panel output scales with irradiance and is zero in the dark`() {
        val pv = PvSettings(arrays = listOf(RoofPlane(kWp = 4.0)), inverterKw = 0.0)
        assertEquals(0.0, Solar.systemPowerKw(listOf(0.0), 15.0, pv), 1e-9)
        val half = Solar.systemPowerKw(listOf(500.0), 15.0, pv)
        val full = Solar.systemPowerKw(listOf(1000.0), 15.0, pv)
        assertTrue(half > 1.0 && full > half)
    }

    @Test
    fun `hot panels make less than cold panels`() {
        val pv = PvSettings(inverterKw = 0.0)
        assertTrue(Solar.systemPowerKw(listOf(800.0), 35.0, pv) < Solar.systemPowerKw(listOf(800.0), 5.0, pv))
    }

    @Test
    fun `the inverter clips the combined output of all planes`() {
        val pv = PvSettings(arrays = listOf(RoofPlane(kWp = 4.0), RoofPlane(kWp = 4.0)), inverterKw = 5.0)
        assertEquals(5.0, Solar.systemPowerKw(listOf(1000.0, 1000.0), 10.0, pv), 1e-9)
    }

    @Test
    fun `the house takes its share before the car gets any`() {
        assertEquals(1.65, Solar.surplusKw(2.0, 0.35), 1e-9)
        assertEquals(0.0, Solar.surplusKw(0.2, 0.35), 1e-9)
    }
}

class PlannerTest {
    private val now = at("2026-10-17T10:00")

    @Test
    fun `charging losses raise the energy that has to be bought`() {
        val need = energyNeed(CarSettings(batteryKwh = 60.0, currentSocPct = 40.0, targetSocPct = 80.0, chargeLossPct = 10.0))
        assertEquals(24.0, need.netKwh, 1e-9)
        assertEquals(24.0 / 0.9, need.grossKwh, 1e-9)
    }

    @Test
    fun `a full battery needs nothing and gets no plan`() {
        val settings = settingsNeeding(0.0)
        val plan = Planner.plan(listOf(slot("2026-10-17T11:00", 0.2)), settings, now, ZONE)
        assertTrue(plan.slots.isEmpty())
        assertEquals(0.0, plan.totals.cost)
        assertTrue(plan.feasible)
    }

    @Test
    fun `the ready-by time rolls to tomorrow once it has passed`() {
        assertEquals(at("2026-10-18T07:30"), nextTimeOccurrence(now, "07:30", ZONE))
        assertEquals(at("2026-10-17T18:00"), nextTimeOccurrence(now, "18:00", ZONE))
    }

    @Test
    fun `cheapest mode picks the lowest prices and respects the deadline`() {
        val timeline = listOf(
            slot("2026-10-17T22:00", 0.30),
            slot("2026-10-17T23:00", 0.20),
            slot("2026-10-18T02:00", 0.18),
            slot("2026-10-18T03:00", 0.25),
            slot("2026-10-18T09:00", 0.05), // after 07:30, must not be used
        )
        val plan = Planner.plan(timeline, settingsNeeding(20.0, chargerKw = 10.0, mode = Mode.CHEAPEST, readyBy = "07:30"), now, ZONE)
        assertEquals(listOf("23:00", "02:00"), hours(plan))
        assertEquals(20.0, plan.totals.kwh, 1e-9)
        assertEquals(10 * 0.20 + 10 * 0.18, plan.totals.cost, 1e-9)
    }

    @Test
    fun `without a deadline every priced hour can be chosen`() {
        val timeline = listOf(
            slot("2026-10-17T22:00", 0.30),
            slot("2026-10-18T09:00", 0.05),
        )
        val plan = Planner.plan(timeline, settingsNeeding(5.0, chargerKw = 10.0), now, ZONE)
        assertEquals(listOf("09:00"), hours(plan))
        assertEquals(at("2026-10-18T10:00"), plan.windowEnd)
    }

    @Test
    fun `balanced mode prefers a sunny hour over a slightly cheaper dark one`() {
        val timeline = listOf(
            slot("2026-10-17T13:00", 0.24, surplusKw = 3.0, irradiance = 600.0),
            slot("2026-10-17T23:00", 0.22),
        )
        val plan = Planner.plan(timeline, settingsNeeding(10.0, chargerKw = 10.0), now, ZONE)
        assertEquals(listOf("13:00"), hours(plan))
        assertEquals(3.0, plan.totals.solarKwh, 1e-9)
        assertEquals(7 * 0.24 + 3 * 0.05, plan.totals.cost, 1e-9)
    }

    @Test
    fun `with net metering that sunny hour loses its advantage`() {
        val timeline = listOf(
            slot("2026-10-17T13:00", 0.24, surplusKw = 3.0),
            slot("2026-10-17T23:00", 0.22),
        )
        val settings = settingsNeeding(10.0, chargerKw = 10.0).let { it.copy(tariffs = it.tariffs.copy(netMetering = true)) }
        assertEquals(listOf("23:00"), hours(Planner.plan(timeline, settings, now, ZONE)))
    }

    @Test
    fun `solar mode charges on the surplus and tops up from the grid when it falls short`() {
        val timeline = listOf(
            slot("2026-10-17T12:00", 0.25, surplusKw = 3.0),
            slot("2026-10-17T13:00", 0.26, surplusKw = 2.0),
            slot("2026-10-17T23:00", 0.20),
        )
        val plan = Planner.plan(timeline, settingsNeeding(10.0, chargerKw = 11.0, mode = Mode.SOLAR), now, ZONE)
        assertEquals(10.0, plan.totals.kwh, 1e-6)
        assertEquals(5.0, plan.totals.solarKwh, 1e-6)
        assertTrue(plan.warnings.any { it.contains("Te weinig zon") })
        assertTrue(plan.slots.any { it.slot.start == at("2026-10-17T23:00") })
    }

    @Test
    fun `solar mode ignores a trickle the car would refuse`() {
        val timeline = listOf(slot("2026-10-17T12:00", 0.25, surplusKw = 0.8))
        val plan = Planner.plan(timeline, settingsNeeding(2.0, mode = Mode.SOLAR), now, ZONE)
        assertTrue(plan.slots.none { it.source == ChargeSource.SOLAR })
    }

    @Test
    fun `a partial last hour is charged pro rata`() {
        val timeline = listOf(slot("2026-10-17T20:00", 0.2), slot("2026-10-17T21:00", 0.3))
        val plan = Planner.plan(timeline, settingsNeeding(15.0, chargerKw = 10.0), now, ZONE)
        val last = plan.slots.single { it.slot.start == at("2026-10-17T21:00") }
        assertTrue(last.partial)
        assertEquals(5.0, last.kwh, 1e-9)
        assertEquals(Duration.ofMinutes(30), Duration.between(last.from, last.to))
    }

    @Test
    fun `a part hour next to a full hour sits at its end so the window is one piece`() {
        val timeline = listOf(slot("2026-10-17T20:00", 0.3), slot("2026-10-17T21:00", 0.2))
        val plan = Planner.plan(timeline, settingsNeeding(15.0, chargerKw = 10.0), now, ZONE)
        assertEquals(1, plan.blocks.size)
        assertEquals("20:30 – 22:00", Fmt.range(plan.blocks[0].from, plan.blocks[0].to, ZONE))
    }

    @Test
    fun `consecutive hours merge into one window, separate ones stay apart`() {
        val timeline = listOf(
            slot("2026-10-17T12:00", 0.10),
            slot("2026-10-17T13:00", 0.11),
            slot("2026-10-17T14:00", 0.40),
            slot("2026-10-17T15:00", 0.12),
        )
        val plan = Planner.plan(timeline, settingsNeeding(30.0, chargerKw = 10.0), now, ZONE)
        assertEquals(2, plan.blocks.size)
        assertEquals("12:00 – 14:00", Fmt.range(plan.blocks[0].from, plan.blocks[0].to, ZONE))
        assertEquals("15:00 – 16:00", Fmt.range(plan.blocks[1].from, plan.blocks[1].to, ZONE))
    }

    @Test
    fun `the plan never costs more than charging right away`() {
        val timeline = (0 until 24).map { h ->
            slot(LocalDateTime.of(2026, 10, 17, 10, 0).plusHours(h.toLong()).toString(), 0.15 + (h * 7 % 11) / 100.0, surplusKw = if (h in 2..5) 2.0 else 0.0)
        }
        val plan = Planner.plan(timeline, settingsNeeding(35.0), now, ZONE)
        val comparison = assertNotNull(plan.comparison)
        assertTrue(plan.totals.cost <= comparison.immediateCost + 1e-9)
    }

    @Test
    fun `the current hour only counts from now`() {
        val half = at("2026-10-17T10:30")
        val timeline = listOf(slot("2026-10-17T10:00", 0.10), slot("2026-10-17T11:00", 0.30))
        val plan = Planner.plan(timeline, settingsNeeding(10.0, chargerKw = 10.0), half, ZONE)
        val first = plan.slots.first()
        assertEquals(half, first.from)
        assertEquals(5.0, first.kwh, 1e-9)
    }

    @Test
    fun `hours without a price are skipped`() {
        val timeline = listOf(slot("2026-10-17T12:00", null, surplusKw = 3.0), slot("2026-10-17T13:00", 0.25))
        val plan = Planner.plan(timeline, settingsNeeding(5.0, chargerKw = 10.0, readyBy = "20:00"), now, ZONE)
        assertEquals(listOf("13:00"), hours(plan))
    }

    @Test
    fun `no usable hours gives a warning instead of a crash`() {
        val plan = Planner.plan(emptyList(), settingsNeeding(5.0), now, ZONE)
        assertFalse(plan.feasible)
        assertTrue(plan.warnings.isNotEmpty())
    }

    @Test
    fun `a deadline too close to deliver the charge rolls to the next day`() {
        val settings = settingsNeeding(40.0, chargerKw = 10.0, readyBy = "11:00")
        val timeline = (0 until 30).map { slot(LocalDateTime.of(2026, 10, 17, 10, 0).plusHours(it.toLong()).toString(), 0.2) }
        val plan = Planner.plan(timeline, settings, now, ZONE)
        assertTrue(plan.deadlineRolled)
        assertEquals(at("2026-10-18T11:00"), plan.windowEnd)
    }

    @Test
    fun `not enough hours says how far you get`() {
        val timeline = listOf(slot("2026-10-17T12:00", 0.2))
        val plan = Planner.plan(timeline, settingsNeeding(20.0, chargerKw = 10.0), now, ZONE)
        assertFalse(plan.feasible)
        assertEquals(10.0, plan.shortfallKwh, 1e-9)
        assertEquals(30.0, plan.reachableSocPct, 1e-9)
    }
}

class TimelineTest {
    @Test
    fun `prices and weather line up on the hour and unpriced hours are dropped by default`() {
        val start = at("2026-10-17T12:00").epochSecond
        val prices = listOf(PricePoint(start, 0.1))
        val weather = WeatherForecast(
            key = "",
            hours = listOf(WeatherHour(start, listOf(800.0), 700.0, 15.0), WeatherHour(start + 3600, listOf(500.0), 400.0, 15.0)),
            days = emptyList(),
        )
        val settings = Settings()
        val timeline = buildTimeline(prices, weather, settings)
        assertEquals(1, timeline.size)
        assertTrue(timeline[0].pvKw > 2.0)
        assertEquals(Pricing.allInPrice(0.1, settings.tariffs), timeline[0].allInPrice!!, 1e-9)

        val longer = buildTimeline(prices, weather, settings, until = Instant.ofEpochSecond(start + 7200))
        assertEquals(2, longer.size)
        assertFalse(longer[1].hasPrice)
    }
}
