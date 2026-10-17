package nl.laadmoment.core

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourcesTest {

    @Test
    fun `reads the EnergyZero response shape`() {
        val body = """{"Prices":[{"price":0.0812,"readingDate":"2026-10-16T22:00:00Z"},{"price":0.0654,"readingDate":"2026-10-16T23:00:00Z"}],"intervalType":4}"""
        val points = EnergyZero.parse(body)
        assertEquals(2, points.size)
        assertEquals(at("2026-10-17T00:00").epochSecond, points[0].startEpoch)
        assertEquals(0.0654, points[1].marketPrice, 1e-9)
    }

    @Test
    fun `quarter-hour prices are averaged into the hour`() {
        val body = """{"Prices":[
            {"price":0.10,"readingDate":"2026-10-17T10:00:00Z"},
            {"price":0.20,"readingDate":"2026-10-17T10:15:00Z"},
            {"price":0.30,"readingDate":"2026-10-17T10:30:00Z"},
            {"price":0.40,"readingDate":"2026-10-17T10:45:00Z"}]}"""
        val points = EnergyZero.parse(body)
        assertEquals(1, points.size)
        assertEquals(0.25, points[0].marketPrice, 1e-9)
    }

    @Test
    fun `negative prices survive and broken rows are dropped`() {
        val body = """{"Prices":[{"price":-0.02,"readingDate":"2026-10-17T11:00:00Z"},{"price":null,"readingDate":"2026-10-17T12:00:00Z"},{"price":0.1,"readingDate":"gisteren"}]}"""
        val points = EnergyZero.parse(body)
        assertEquals(1, points.size)
        assertEquals(-0.02, points[0].marketPrice, 1e-9)
    }

    @Test
    fun `reads Energy-Charts and converts euros per megawatt-hour`() {
        val t = at("2026-10-17T12:00").epochSecond
        val body = """{"license_info":"CC BY 4.0","unix_seconds":[$t,${t + 900},${t + 1800},${t + 2700},${t + 3600}],"price":[80.0,90.0,100.0,110.0,-5.0],"unit":"EUR / MWh"}"""
        val points = EnergyCharts.parse(body)
        assertEquals(2, points.size)
        assertEquals(0.095, points[0].marketPrice, 1e-9)
        assertEquals(-0.005, points[1].marketPrice, 1e-9)
    }

    @Test
    fun `the price request covers today and tomorrow without VAT`() {
        val loader = DataLoader { error("no network") }
        val (from, till) = loader.priceWindow(at("2026-10-17T15:20"), ZONE)
        assertEquals(at("2026-10-17T00:00"), from)
        assertEquals(at("2026-10-19T00:00"), till)
        val url = EnergyZero.url(from, till)
        assertContains(url, "inclBtw=false")
        assertContains(url, "fromDate=2026-10-16T22%3A00%3A00Z")
        assertContains(url, "tillDate=2026-10-18T21%3A59%3A59.999Z")
        assertContains(EnergyCharts.url(from, till, ZONE), "bzn=NL&start=2026-10-17&end=2026-10-19")
    }

    @Test
    fun `the weather request carries the roof and the place`() {
        val url = OpenMeteo.url(Location(latitude = 53.2069, longitude = 6.2903), RoofPlane(tilt = 35.4, azimuth = -90.0))
        assertContains(url, "latitude=53.2069&longitude=6.2903")
        assertContains(url, "tilt=35&azimuth=-90")
        assertContains(url, "timeformat=unixtime")
    }

    private fun meteo(gti: List<Double?>, start: Long): String {
        val times = gti.indices.map { start + it * 3600L }
        val values = gti.joinToString(",") { it?.toString() ?: "null" }
        val ghi = gti.joinToString(",") { ((it ?: 0.0) * 0.9).toString() }
        val zeros = gti.joinToString(",") { "0" }
        val codes = gti.joinToString(",") { "1" }
        val days = start - 12 * 3600 // local midnight is not the point here
        return """{"utc_offset_seconds":7200,"hourly":{"time":[${times.joinToString(",")}],
            "global_tilted_irradiance":[$values],"shortwave_radiation":[$ghi],
            "temperature_2m":[${gti.joinToString(",") { "14.5" }}],"cloud_cover":[$zeros],
            "sunshine_duration":[${gti.joinToString(",") { "1800" }}],"weather_code":[$codes],"is_day":[${gti.joinToString(",") { "1" }}]},
            "daily":{"time":[$days],"sunrise":[${days + 8 * 3600}],"sunset":[${days + 19 * 3600}],"sunshine_duration":[18000]}}"""
    }

    @Test
    fun `irradiance stamped at the end of the hour belongs to that hour`() {
        val start = at("2026-10-17T12:00").epochSecond
        val forecast = OpenMeteo.parse(listOf(meteo(listOf(100.0, 600.0, 300.0), start)), listOf(RoofPlane()), "k")
        // 12:00-13:00 is described by the value stamped 13:00.
        assertEquals(600.0, forecast.hours[0].gti[0], 1e-9)
        assertEquals(300.0, forecast.hours[1].gti[0], 1e-9)
        // The last hour has nothing after it and keeps its own value.
        assertEquals(300.0, forecast.hours[2].gti[0], 1e-9)
        assertEquals(30.0, forecast.hours[0].sunshineMinutes!!, 1e-9)
        assertEquals(14.5, forecast.hours[0].tempC!!, 1e-9)
        assertEquals(1, forecast.hours[0].weatherCode)
        assertEquals(5.0, forecast.days[0].sunshineHours!!, 1e-9)
    }

    @Test
    fun `two roof planes each keep their own irradiance`() {
        val start = at("2026-10-17T12:00").epochSecond
        val east = meteo(listOf(100.0, 200.0), start)
        val west = meteo(listOf(300.0, 400.0), start)
        val forecast = OpenMeteo.parse(listOf(east, west), listOf(RoofPlane(azimuth = -90.0), RoofPlane(azimuth = 90.0)), "k")
        assertEquals(listOf(200.0, 400.0), forecast.hours[0].gti)
    }

    @Test
    fun `missing tilted irradiance falls back to the flat value`() {
        val start = at("2026-10-17T12:00").epochSecond
        val forecast = OpenMeteo.parse(listOf(meteo(listOf(null, null), start)), listOf(RoofPlane()), "k")
        assertEquals(0.0, forecast.hours[0].gti[0], 1e-9)
        assertTrue(forecast.hours.all { it.gti[0] >= 0 })
    }

    @Test
    fun `a response without hours is refused with a readable message`() {
        val error = assertFailsWith<DataException> { OpenMeteo.parse(listOf("""{"hourly":{}}"""), listOf(RoofPlane()), "k") }
        assertContains(error.message!!, "Weerdata")
    }

    private fun energyZeroBody(fromLocal: String, hours: Int): String {
        val start = at(fromLocal).epochSecond
        val rows = (0 until hours).joinToString(",") {
            """{"price":0.1,"readingDate":"${java.time.Instant.ofEpochSecond(start + it * 3600L)}"}"""
        }
        return """{"Prices":[$rows]}"""
    }

    private fun energyChartsBody(fromLocal: String, hours: Int): String {
        val start = at(fromLocal).epochSecond
        val times = (0 until hours).joinToString(",") { (start + it * 3600L).toString() }
        val prices = (0 until hours).joinToString(",") { "200.0" }
        return """{"unix_seconds":[$times],"price":[$prices],"unit":"EUR / MWh"}"""
    }

    @Test
    fun `when the chosen source fails the other one steps in`() {
        val loader = DataLoader { url ->
            if (url.contains("energyzero")) throw java.io.IOException("time-out")
            energyChartsBody("2026-10-17T00:00", 24)
        }
        val prices = loader.loadPrices(Settings(), at("2026-10-17T09:00"), ZONE)
        assertEquals(PriceSource.ENERGY_CHARTS, prices.source)
        assertEquals(24, prices.points.size)
        assertContains(prices.note!!, "Energy-Charts")
    }

    @Test
    fun `in the morning one source is enough`() {
        var calls = 0
        val loader = DataLoader { calls++; energyZeroBody("2026-10-17T00:00", 24) }
        val prices = loader.loadPrices(Settings(), at("2026-10-17T09:00"), ZONE)
        assertEquals(1, calls)
        assertNull(prices.note)
    }

    @Test
    fun `tomorrow's prices are filled in from the backup when the first source lags`() {
        val loader = DataLoader { url ->
            if (url.contains("energyzero")) energyZeroBody("2026-10-17T00:00", 24) else energyChartsBody("2026-10-17T00:00", 48)
        }
        val prices = loader.loadPrices(Settings(), at("2026-10-17T15:00"), ZONE)
        assertEquals(PriceSource.ENERGYZERO, prices.source)
        assertEquals(48, prices.points.size)
        // Today stays as the first source had it.
        assertEquals(0.1, prices.points.first().marketPrice, 1e-9)
        assertEquals(0.2, prices.points.last().marketPrice, 1e-9)
    }

    @Test
    fun `both sources down is one clear error`() {
        val loader = DataLoader { throw java.io.IOException("geen verbinding") }
        val error = assertFailsWith<DataException> { loader.loadPrices(Settings(), at("2026-10-17T09:00"), ZONE) }
        assertContains(error.message!!, "EnergyZero")
        assertContains(error.message!!, "Energy-Charts")
    }

    @Test
    fun `settings and forecasts survive a round trip through JSON`() {
        val settings = Settings(car = CarSettings(batteryKwh = 77.0), mode = Mode.SOLAR)
        assertEquals(settings, Settings.fromJson(settings.toJson()))
        assertEquals(Settings(), Settings.fromJson("""{"onbekend":1,"car":{"batteryKwh":"kapot"}}""").copy(car = CarSettings()))
        assertEquals(Settings(), Settings.fromJson("geen json"))

        val forecast = Forecast(1L, listOf(PricePoint(3600, 0.1)), PriceSource.ENERGYZERO, null, WeatherForecast("k", emptyList(), emptyList()))
        assertEquals(forecast, Forecast.fromJson(forecast.toJson()))
    }
}
