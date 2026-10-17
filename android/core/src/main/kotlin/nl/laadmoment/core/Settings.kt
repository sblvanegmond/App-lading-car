package nl.laadmoment.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the planner needs to know about the house, the car and the
 * contract. Stored on the phone as one JSON document; every field has a
 * default so an older saved copy still loads after an update adds a field.
 */
@Serializable
data class Settings(
    val location: Location = Location(),
    val pv: PvSettings = PvSettings(),
    /** Average household draw that eats into the solar surplus, in kW. */
    val houseBaseLoadKw: Double = 0.35,
    val car: CarSettings = CarSettings(),
    /** When false the planner simply looks for the best hours in every known price. */
    val readyByEnabled: Boolean = false,
    /** Local time (HH:MM) the car has to be ready, used when [readyByEnabled]. */
    val readyBy: String = "07:30",
    val mode: Mode = Mode.BALANCED,
    val tariffs: Tariffs = Tariffs(),
    val priceSource: PriceSource = PriceSource.ENERGYZERO,
    val notifications: NotificationSettings = NotificationSettings(),
) {
    fun toJson(): String = SettingsJson.encodeToString(serializer(), this)

    /**
     * Changes to these fields change what the weather service has to be asked,
     * so a cached forecast made for another roof or place is useless.
     */
    fun weatherKey(): String = buildString {
        append("%.4f,%.4f".format(java.util.Locale.ROOT, location.latitude, location.longitude))
        for (plane in pv.arrays) {
            append('|').append(plane.tilt.toInt()).append(',').append(plane.azimuth.toInt())
        }
    }

    companion object {
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

/** Grootegast, Groningen. */
@Serializable
data class Location(
    val name: String = "Grootegast",
    val latitude: Double = 53.2069,
    val longitude: Double = 6.2903,
)

/**
 * One roof plane. Azimuth follows the Open-Meteo convention:
 * 0 = south, -90 = east, 90 = west, 180 = north.
 */
@Serializable
data class RoofPlane(
    val label: String = "Dak",
    val kWp: Double = 4.0,
    val tilt: Double = 35.0,
    val azimuth: Double = 0.0,
)

@Serializable
data class PvSettings(
    val arrays: List<RoofPlane> = listOf(RoofPlane(label = "Hoofdveld")),
    /** Cabling, soiling, inverter efficiency and mismatch, as one factor. */
    val performanceRatio: Double = 0.86,
    /** AC limit of the inverter in kW; 0 means it does not clip. */
    val inverterKw: Double = 3.7,
    /** Temperature coefficient of the panels, per kelvin. */
    val tempCoeff: Double = -0.004,
) {
    val totalKwp: Double get() = arrays.sumOf { it.kWp.coerceAtLeast(0.0) }
}

@Serializable
data class CarSettings(
    val batteryKwh: Double = 58.0,
    /** What wallbox and car reach together. */
    val chargerKw: Double = 11.0,
    /** Below this the car refuses to charge; 6 A on one phase is about 1.4 kW. */
    val minChargeKw: Double = 1.4,
    val currentSocPct: Double = 40.0,
    val targetSocPct: Double = 80.0,
    /** AC charging losses: onboard charger, cable and battery. */
    val chargeLossPct: Double = 10.0,
)

/**
 * Amounts in EUR/kWh excluding VAT, the way they appear on a contract.
 * The energy tax is the 2026 rate for the first bracket.
 */
@Serializable
data class Tariffs(
    val supplierMarkupPerKwh: Double = 0.02,
    val energyTaxPerKwh: Double = 0.0916,
    val vatPct: Double = 21.0,
    /** What you are paid for a kWh you feed back into the grid. */
    val feedInPerKwh: Double = 0.05,
    /**
     * Net metering ("saldering"). While it applies, a self-consumed kWh is
     * worth the full purchase price and sun gives no extra advantage.
     */
    val netMetering: Boolean = false,
)

@Serializable
enum class Mode(val label: String, val hint: String) {
    CHEAPEST("Goedkoopst", "Kijkt alleen naar de stroomprijs en laadt op vol vermogen."),
    BALANCED("Balans", "Weegt stroomprijs en eigen zon tegen elkaar af. Aanrader."),
    SOLAR("Max zon", "Laadt op het overschot van je panelen en vult alleen aan als het moet."),
}

@Serializable
enum class PriceSource(val label: String) {
    ENERGYZERO("EnergyZero"),
    ENERGY_CHARTS("Energy-Charts (EPEX)"),
}

@Serializable
data class NotificationSettings(
    /** A short summary of the day's best charging hours every morning. */
    val morningEnabled: Boolean = false,
    val morningTime: String = "07:00",
    /** A nudge shortly before the best window starts. */
    val windowReminder: Boolean = false,
)
