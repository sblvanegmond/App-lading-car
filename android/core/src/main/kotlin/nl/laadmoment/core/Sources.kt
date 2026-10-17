package nl.laadmoment.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The three free services the app talks to. None of them needs an account
 * or a key; the only thing that leaves the phone is the location of the
 * roof and the date range.
 *
 *  - EnergyZero: Dutch day-ahead prices (primary).
 *  - Energy-Charts (Fraunhofer ISE): the same EPEX prices, as a backup.
 *  - Open-Meteo: irradiance on the roof planes, temperature, clouds.
 */
fun interface HttpGet {
    fun get(url: String): String
}

object UrlConnectionHttp : HttpGet {
    override fun get(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Laadmoment/1.0 (Android)")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw DataException("server", "antwoordde met $code")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

private val json = Json { ignoreUnknownKeys = true }

private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

private fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

/**
 * Averages any finer resolution into whole hours. Since October 2025 the
 * day-ahead market trades in quarters of an hour; an hourly view keeps the
 * screen readable, and the average is what an hour of charging costs.
 */
internal fun toHourly(rows: List<Pair<Instant, Double>>): List<PricePoint> =
    rows.groupBy { it.first.epochSecond.floorDiv(3600L) * 3600L }
        .map { (hour, items) -> PricePoint(hour, items.map { it.second }.average()) }
        .sortedBy { it.startEpoch }

/** Some sources publish EUR/MWh; anything above 5 EUR per kWh gives that away. */
private fun perKwh(value: Double) = if (abs(value) > 5) value / 1000 else value

private fun parseInstant(text: String): Instant? =
    runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()

object EnergyZero {
    fun url(from: Instant, till: Instant): String =
        "https://api.energyzero.nl/v1/energyprices" +
            "?fromDate=${encode(from.toString())}" +
            "&tillDate=${encode(till.minusMillis(1).toString())}" +
            "&interval=4&usageType=1&inclBtw=false"

    /** `{ "Prices": [ { "price": 0.0912, "readingDate": "2026-10-16T22:00:00Z" } ] }` */
    fun parse(body: String): List<PricePoint> {
        val root = json.parseToJsonElement(body)
        val list = root.asArray()
            ?: root.asObject()?.let { it["Prices"] ?: it["prices"] ?: it["data"] }.asArray()
            ?: return emptyList()
        val rows = list.mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val stamp = (obj["readingDate"] ?: obj["start"] ?: obj["from"]) as? JsonPrimitive
            val start = stamp?.contentOrNull?.let(::parseInstant) ?: return@mapNotNull null
            val price = (obj["price"] ?: obj["value"]).asDouble() ?: return@mapNotNull null
            start to perKwh(price)
        }
        return toHourly(rows)
    }
}

object EnergyCharts {
    fun url(from: Instant, till: Instant, zone: ZoneId): String {
        val start = from.atZone(zone).toLocalDate()
        val end = till.atZone(zone).toLocalDate()
        return "https://api.energy-charts.info/price?bzn=NL&start=$start&end=$end"
    }

    /** `{ "unix_seconds": [...], "price": [...], "unit": "EUR / MWh" }` */
    fun parse(body: String): List<PricePoint> {
        val root = json.parseToJsonElement(body).asObject() ?: return emptyList()
        val times = root["unix_seconds"].asArray() ?: return emptyList()
        val prices = root["price"].asArray() ?: return emptyList()
        val unit = (root["unit"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val perMwh = unit.contains("MWh", ignoreCase = true)
        val rows = times.indices.mapNotNull { i ->
            val t = times[i].asLong() ?: return@mapNotNull null
            val p = prices.getOrNull(i).asDouble() ?: return@mapNotNull null
            Instant.ofEpochSecond(t) to if (perMwh) p / 1000 else perKwh(p)
        }
        return toHourly(rows)
    }
}

object OpenMeteo {
    fun url(location: Location, plane: RoofPlane): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${"%.4f".format(Locale.ROOT, location.latitude)}" +
            "&longitude=${"%.4f".format(Locale.ROOT, location.longitude)}" +
            "&hourly=global_tilted_irradiance,shortwave_radiation,temperature_2m,cloud_cover," +
            "sunshine_duration,weather_code,is_day" +
            "&daily=sunrise,sunset,sunshine_duration" +
            "&tilt=${plane.tilt.coerceIn(0.0, 90.0).roundToInt()}" +
            "&azimuth=${plane.azimuth.coerceIn(-180.0, 180.0).roundToInt()}" +
            "&forecast_days=3&timezone=auto&timeformat=unixtime"

    /**
     * One response per roof plane, all for the same place. Radiation and
     * sunshine are reported as the mean (or sum) over the hour *before* the
     * time stamp, so the hour 12:00-13:00 takes the value stamped 13:00.
     * Temperature, clouds and the weather code are instantaneous.
     */
    fun parse(bodies: List<String>, planes: List<RoofPlane>, key: String): WeatherForecast {
        require(bodies.isNotEmpty()) { "geen weerdata" }
        val roots = bodies.map { json.parseToJsonElement(it).asObject() ?: throw DataException("Weerdata", "onleesbaar antwoord") }
        val base = roots.first()
        val hourly = base["hourly"].asObject() ?: throw DataException("Weerdata", "geen uurwaarden ontvangen")
        val times = hourly["time"].asArray()?.mapNotNull { it.asLong() }.orEmpty()
        if (times.isEmpty()) throw DataException("Weerdata", "geen uurwaarden ontvangen")
        val indexOf = times.withIndex().associate { (i, t) -> t to i }
        val offset = base["utc_offset_seconds"].asLong() ?: 0L

        fun series(root: JsonObject, name: String): JsonArray? = root["hourly"].asObject()?.get(name).asArray()

        fun precedingHour(root: JsonObject, name: String, start: Long, ownIndex: Int): Double? {
            val values = series(root, name) ?: return null
            val next = indexOf[start + 3600]
            return values.getOrNull(next ?: ownIndex).asDouble()
        }

        val hours = times.mapIndexed { i, start ->
            val ghi = precedingHour(base, "shortwave_radiation", start, i)
            val gti = roots.mapIndexed { p, root ->
                val value = precedingHour(root, "global_tilted_irradiance", start, i)
                val tilt = planes.getOrNull(p)?.tilt ?: 35.0
                (value ?: Solar.estimateGtiFromGhi(ghi ?: 0.0, tilt)).coerceAtLeast(0.0)
            }
            WeatherHour(
                startEpoch = start,
                gti = gti,
                ghi = ghi?.coerceAtLeast(0.0),
                tempC = series(base, "temperature_2m")?.getOrNull(i).asDouble(),
                cloudCoverPct = series(base, "cloud_cover")?.getOrNull(i).asDouble(),
                sunshineMinutes = precedingHour(base, "sunshine_duration", start, i)?.div(60),
                weatherCode = series(base, "weather_code")?.getOrNull(i).asDouble()?.toInt(),
                isDay = series(base, "is_day")?.getOrNull(i).asDouble()?.let { it >= 0.5 },
            )
        }

        val daily = base["daily"].asObject()
        val dayTimes = daily?.get("time").asArray().orEmpty()
        val days = dayTimes.mapIndexedNotNull { i, element ->
            val midnight = element.asLong() ?: return@mapIndexedNotNull null
            SunDay(
                date = Instant.ofEpochSecond(midnight + offset).atOffset(ZoneOffset.UTC).toLocalDate().toString(),
                sunriseEpoch = daily?.get("sunrise").asArray()?.getOrNull(i).asLong(),
                sunsetEpoch = daily?.get("sunset").asArray()?.getOrNull(i).asLong(),
                sunshineHours = daily?.get("sunshine_duration").asArray()?.getOrNull(i).asDouble()?.div(3600),
            )
        }
        return WeatherForecast(key = key, hours = hours, days = days)
    }
}

/** Fetches prices and weather; blocking, so call it off the main thread. */
class DataLoader(private val http: HttpGet = UrlConnectionHttp) {

    data class Prices(val points: List<PricePoint>, val source: PriceSource, val note: String?)

    /** Today from midnight up to (not including) the day after tomorrow, local time. */
    fun priceWindow(now: Instant, zone: ZoneId): Pair<Instant, Instant> {
        val today = now.atZone(zone).toLocalDate()
        return today.atStartOfDay(zone).toInstant() to today.plusDays(2).atStartOfDay(zone).toInstant()
    }

    fun fetchPrices(source: PriceSource, now: Instant, zone: ZoneId): List<PricePoint> {
        val (from, till) = priceWindow(now, zone)
        val points = when (source) {
            PriceSource.ENERGYZERO -> EnergyZero.parse(http.get(EnergyZero.url(from, till)))
            PriceSource.ENERGY_CHARTS -> EnergyCharts.parse(http.get(EnergyCharts.url(from, till, zone)))
        }
        return points.filter { it.start >= from && it.start < till }
    }

    /**
     * The chosen source first. The other one steps in when the first fails,
     * and also when tomorrow's prices are due but the first does not have
     * them yet: the two services do not publish at exactly the same minute.
     */
    fun loadPrices(settings: Settings, now: Instant, zone: ZoneId): Prices {
        val order = listOf(settings.priceSource) + PriceSource.entries.filter { it != settings.priceSource }
        val problems = mutableListOf<String>()
        var result: Prices? = null
        for (source in order) {
            try {
                val points = fetchPrices(source, now, zone)
                if (points.isEmpty()) throw DataException(source.label, "geen prijzen in het antwoord")
                val current = result
                if (current == null) {
                    val note = if (problems.isEmpty()) null else "${settings.priceSource.label} gaf geen prijzen, dus ${source.label} gebruikt."
                    result = Prices(points, source, note)
                    if (!tomorrowDue(now, zone) || coversTomorrow(points, now, zone)) break
                } else {
                    if (coversTomorrow(points, now, zone)) {
                        val known = current.points.map { it.startEpoch }.toSet()
                        val merged = (current.points + points.filter { it.startEpoch !in known }).sortedBy { it.startEpoch }
                        result = current.copy(points = merged, note = "Prijzen voor morgen aangevuld via ${source.label}.")
                    }
                    break
                }
            } catch (e: Exception) {
                problems += "${source.label}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return result ?: throw DataException("Stroomprijzen", problems.joinToString("; "))
    }

    fun loadWeather(settings: Settings): WeatherForecast {
        val planes = settings.pv.arrays.ifEmpty { listOf(RoofPlane(kWp = 0.0)) }
        return try {
            val bodies = planes.map { http.get(OpenMeteo.url(settings.location, it)) }
            OpenMeteo.parse(bodies, planes, settings.weatherKey())
        } catch (e: DataException) {
            throw e
        } catch (e: Exception) {
            throw DataException("Weerdata", e.message ?: e.javaClass.simpleName, e)
        }
    }

    companion object {
        /** EPEX publishes around 12:45; give the services until 13:30. */
        fun tomorrowDue(now: Instant, zone: ZoneId): Boolean =
            now.atZone(zone).toLocalTime() >= LocalTime.of(13, 30)

        fun coversTomorrow(points: List<PricePoint>, now: Instant, zone: ZoneId): Boolean {
            val tomorrowEvening = now.atZone(zone).toLocalDate().plusDays(1).atTime(20, 0).atZone(zone).toInstant()
            return points.any { it.startEpoch == tomorrowEvening.epochSecond }
        }
    }
}
