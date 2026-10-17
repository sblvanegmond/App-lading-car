package nl.laadmoment.core

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The raw ingredients, as they come from the services. Times are stored as
 * epoch seconds so the lot can be cached as plain JSON.
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
    /** Mean sunlight on a flat surface over this hour, W/m2. */
    val radiation: Double? = null,
    /** Minutes of sunshine in this hour, 0 to 60. */
    val sunshineMinutes: Double? = null,
    val cloudCoverPct: Double? = null,
    val tempC: Double? = null,
    /** WMO weather interpretation code. */
    val weatherCode: Int? = null,
    val isDay: Boolean? = null,
) {
    val start: Instant get() = Instant.ofEpochSecond(startEpoch)

    val irradiance: Double get() = radiation ?: 0.0
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
    /** [Settings.weatherKey] of the place this forecast is for. */
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
    /** False when one of the services failed and older data filled the gap. */
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
