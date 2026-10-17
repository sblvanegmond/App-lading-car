package nl.laadmoment.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.laadmoment.core.DataLoader
import nl.laadmoment.core.Forecast
import nl.laadmoment.core.Settings
import nl.laadmoment.core.WeatherForecast
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Fetches prices and weather and keeps the last good answer on disk, so the
 * app still has something to show in a garage without signal.
 */
class ForecastRepository(context: Context, private val loader: DataLoader = DataLoader()) {
    private val file = File(context.filesDir, "forecast.json")
    private val mutex = Mutex()

    @Volatile
    private var memory: Forecast? = null

    data class Result(val forecast: Forecast?, val errors: List<String>)

    fun cached(): Forecast? {
        memory?.let { return it }
        val read = runCatching { if (file.exists()) Forecast.fromJson(file.readText()) else null }.getOrNull()
        memory = read
        return read
    }

    suspend fun load(settings: Settings, force: Boolean): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = Instant.now()
            val zone = ZoneId.systemDefault()
            val cached = cached()
            if (!force && cached != null && isFresh(cached, settings, now, zone)) {
                return@withContext Result(cached, emptyList())
            }

            val errors = mutableListOf<String>()
            val prices = runCatching { loader.loadPrices(settings, now, zone) }
                .onFailure { errors += it.message ?: "Stroomprijzen: onbekende fout" }
                .getOrNull()
            val weather = runCatching { loader.loadWeather(settings) }
                .onFailure { errors += it.message ?: "Weerdata: onbekende fout" }
                .getOrNull()

            if (prices == null && weather == null) return@withContext Result(cached, errors)

            val forecast = Forecast(
                fetchedAtEpoch = now.epochSecond,
                prices = prices?.points ?: cached?.prices.orEmpty(),
                priceSource = prices?.source ?: cached?.priceSource ?: settings.priceSource,
                priceNote = prices?.note,
                weather = weather
                    ?: cached?.weather?.takeIf { it.key == settings.weatherKey() }
                    ?: WeatherForecast(settings.weatherKey(), emptyList(), emptyList()),
                complete = errors.isEmpty(),
            )
            write(forecast)
            Result(forecast, errors)
        }
    }

    private fun isFresh(cached: Forecast, settings: Settings, now: Instant, zone: ZoneId): Boolean {
        val age = Duration.between(cached.fetchedAt, now)
        if (!cached.complete && age > Duration.ofMinutes(5)) return false
        if (age > Duration.ofMinutes(30) || age.isNegative) return false
        if (cached.weather.key != settings.weatherKey()) return false
        if (cached.fetchedAt.atZone(zone).toLocalDate() != now.atZone(zone).toLocalDate()) return false
        // Tomorrow's prices come out in the early afternoon: look again sooner.
        val waiting = DataLoader.tomorrowDue(now, zone) && !DataLoader.coversTomorrow(cached.prices, now, zone)
        return !(waiting && age > Duration.ofMinutes(10))
    }

    private fun write(forecast: Forecast) {
        memory = forecast
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(forecast.toJson())
            if (!tmp.renameTo(file)) {
                file.writeText(forecast.toJson())
                tmp.delete()
            }
        }
    }
}
