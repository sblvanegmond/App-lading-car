package nl.laadmoment.core

/** Turning a day-ahead market price into what you actually pay. */
object Pricing {
    /** EUR/kWh including markup, energy tax and VAT. */
    fun allInPrice(marketPricePerKwh: Double, tariffs: Tariffs): Double {
        val vat = 1 + tariffs.vatPct / 100
        return (marketPricePerKwh + tariffs.supplierMarkupPerKwh + tariffs.energyTaxPerKwh) * vat
    }
}
