package nl.laadmoment.core

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Turning a market price into what you actually pay, and irradiance into
 * kilowatts on your own roof. Pure functions, mirrored from the web app so
 * both give the same answer.
 */
object Pricing {
    /** EUR/kWh including markup, energy tax and VAT. */
    fun allInPrice(marketPricePerKwh: Double, tariffs: Tariffs): Double {
        val vat = 1 + tariffs.vatPct / 100
        return (marketPricePerKwh + tariffs.supplierMarkupPerKwh + tariffs.energyTaxPerKwh) * vat
    }

    /**
     * What a kWh of your own sun costs you when it goes into the car instead
     * of onto the grid. Under net metering that is the full purchase price;
     * without it you only miss the feed-in compensation.
     */
    fun solarOpportunityCost(allIn: Double, tariffs: Tariffs): Double =
        if (tariffs.netMetering) allIn else max(0.0, tariffs.feedInPerKwh)
}

object Solar {
    private const val STC_WM2 = 1000.0

    /** Rough NOCT-style cell heating: +25 K at 800 W/m2. */
    private const val CELL_HEATING_K_PER_WM2 = 25.0 / 800.0

    /** AC-side power of one plane before the inverter limit, in kW. */
    fun planePowerKw(gtiWm2: Double, airTempC: Double?, plane: RoofPlane, pv: PvSettings): Double {
        val gti = max(0.0, gtiWm2)
        val kWp = max(0.0, plane.kWp)
        if (gti <= 0 || kWp <= 0) return 0.0
        val pr = pv.performanceRatio.coerceIn(0.3, 1.0)
        val air = airTempC ?: 15.0
        val cellTemp = air + gti * CELL_HEATING_K_PER_WM2
        // Panels lose output when hot and gain a little when cold.
        val tempFactor = (1 + pv.tempCoeff * (cellTemp - 25)).coerceIn(0.6, 1.15)
        return gti / STC_WM2 * kWp * pr * tempFactor
    }

    /** The whole system for one hour, clipped by the inverter. */
    fun systemPowerKw(gtiPerPlane: List<Double>, airTempC: Double?, pv: PvSettings): Double {
        var total = 0.0
        pv.arrays.forEachIndexed { i, plane ->
            total += planePowerKw(gtiPerPlane.getOrElse(i) { 0.0 }, airTempC, plane, pv)
        }
        return if (pv.inverterKw > 0) min(total, pv.inverterKw) else total
    }

    /**
     * Fallback when the weather service has no irradiance for the tilted
     * plane: an isotropic guess on top of horizontal irradiance.
     */
    fun estimateGtiFromGhi(ghi: Double, tiltDeg: Double): Double {
        if (ghi <= 0) return 0.0
        val tilt = tiltDeg.coerceIn(0.0, 90.0)
        return ghi * (1 + 0.15 * sin(tilt * PI / 180))
    }

    /** What is left for the car after the house has taken its share. */
    fun surplusKw(pvKw: Double, houseBaseLoadKw: Double): Double = max(0.0, pvKw - max(0.0, houseBaseLoadKw))
}
