package nl.laadmoment.core

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The raw ingredients of a plan, as they come from the two services. Times
 * are stored as epoch seconds so the lot can be cached as plain JSON.
 */

/** Day-ahead market price for one hour, EUR/kWh excluding VAT. */
@Serializable
data class PricePoint(
    val startEpoch: Long,
    val marketPrice: Double,
) {
    val start: Instant get() = Instant.ofEpochSecond(startEpoch)
}

@Serializable
data class WeatherHour(
    val startEpoch: Long,
    /** Mean irradiance on each roof plane over this hour, W/m2, in settings order. */
    val gti: List<Double>,
    /** Mean irradiance on a flat surface over this hour, W/m2. */
    val ghi: Double? = null,
    val tempC: Double? = null,
    val cloudCoverPct: Double? = null,
    val sunshineMinutes: Double? = null,
    /** WMO weather interpretation code. */
    val weatherCode: Int? = null,
    val isDay: Boolean? = null,
) {
    val start: Instant get() = Instant.ofEpochSecond(startEpoch)

    /** The brightest of what is known: the panels' own plane or the flat sky. */
    val irradiance: Double
        get() = maxOf(ghi ?: 0.0, gti.maxOrNull() ?: 0.0)
}

@Serializable
data class SunDay(
    /** Local date, ISO format. */
    val date: String,
    val sunriseEpoch: Long? = null,
    val sunsetEpoch: Long? = null,
    val sunshineHours: Double? = null,
)

@Serializable
data class WeatherForecast(
    /** [Settings.weatherKey] of the settings this forecast was made for. */
    val key: String,
    val hours: List<WeatherHour>,
    val days: List<SunDay>,
)

/**
 * Everything fetched in one go, plus where the prices came from. This is what
 * the app keeps on disk so it still works in a garage without signal.
 */
@Serializable
data class Forecast(
    val fetchedAtEpoch: Long,
    val prices: List<PricePoint>,
    val priceSource: PriceSource,
    val priceNote: String? = null,
    val weather: WeatherForecast,
    /** False when one of the two services failed and older data filled the gap. */
    val complete: Boolean = true,
) {
    val fetchedAt: Instant get() = Instant.ofEpochSecond(fetchedAtEpoch)

    fun toJson(): String = SettingsJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String?): Forecast? {
            if (text.isNullOrBlank()) return null
            return runCatching { SettingsJson.decodeFromString(serializer(), text) }.getOrNull()
        }
    }
}

class DataException(val source: String, detail: String, cause: Throwable? = null) :
    Exception("$source: $detail", cause)
