package nl.laadmoment.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The few choices the app needs. Stored on the phone as one JSON document;
 * every field has a default, and fields an older version wrote are ignored.
 */
@Serializable
data class Settings(
    /** How many hours to highlight as the best ones to charge in. */
    val bestHours: Int = 5,
    val ranking: Ranking = Ranking.PRICE,
    val location: Location = Location(),
    val tariffs: Tariffs = Tariffs(),
    val priceSource: PriceSource = PriceSource.ENERGYZERO,
    val notifications: NotificationSettings = NotificationSettings(),
) {
    fun toJson(): String = SettingsJson.encodeToString(serializer(), this)

    /** A cached forecast made for another place is useless. */
    fun weatherKey(): String = "%.4f,%.4f".format(java.util.Locale.ROOT, location.latitude, location.longitude)

    companion object {
        const val MIN_BEST_HOURS = 1
        const val MAX_BEST_HOURS = 16

        fun fromJson(text: String?): Settings {
            if (text.isNullOrBlank()) return Settings()
            return runCatching { SettingsJson.decodeFromString(serializer(), text) }
                .getOrDefault(Settings())
        }
    }
}

val SettingsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
enum class Ranking(val label: String, val hint: String) {
    PRICE("Laagste prijs", "De goedkoopste uren, los van het weer."),
    PRICE_AND_SUN(
        "Prijs én zon",
        "Een uur met volle zon telt als half zo duur, omdat je dan deels met je eigen zonnestroom laadt.",
    ),
}

/** Grootegast, Groningen. */
@Serializable
data class Location(
    val name: String = "Grootegast",
    val latitude: Double = 53.2069,
    val longitude: Double = 6.2903,
)

/**
 * What turns a market price into what you pay, in EUR/kWh excluding VAT.
 * The energy tax is the 2026 rate. These only shift every price by the same
 * amount, so they never change which hours are best.
 */
@Serializable
data class Tariffs(
    val supplierMarkupPerKwh: Double = 0.02,
    val energyTaxPerKwh: Double = 0.0916,
    val vatPct: Double = 21.0,
)

@Serializable
enum class PriceSource(val label: String) {
    ENERGYZERO("EnergyZero"),
    ENERGY_CHARTS("Energy-Charts (EPEX)"),
}

@Serializable
data class NotificationSettings(
    /** Every morning: the best hours of the day. */
    val morningEnabled: Boolean = false,
    val morningTime: String = "07:00",
    /** A nudge a quarter of an hour before a block of best hours starts. */
    val windowReminder: Boolean = false,
)
