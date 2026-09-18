/**
 * Turning a raw day-ahead market price into the price you actually pay,
 * and valuing a kWh of your own solar.
 */

/**
 * All-in consumer price for one kWh.
 *
 * @param {number} marketPricePerKwh EPEX/day-ahead price excluding VAT, EUR/kWh.
 * @param {object} tariffs settings.tariffs
 * @returns {number} EUR/kWh including markup, energy tax and VAT.
 */
export function allInPrice(marketPricePerKwh, tariffs) {
  const market = Number(marketPricePerKwh) || 0;
  const markup = Number(tariffs?.supplierMarkupPerKwh) || 0;
  const tax = Number(tariffs?.energyTaxPerKwh) || 0;
  const vat = 1 + (Number(tariffs?.vatPct) || 0) / 100;
  return (market + markup + tax) * vat;
}

/**
 * What one kWh of your own solar is worth when you put it in the car
 * instead of on the grid.
 *
 * Under net metering ("saldering") a fed-in kWh cancels out a bought kWh, so
 * self-consuming costs you the full purchase price and there is no solar
 * bonus. Without net metering you only lose the feed-in compensation, which
 * is what makes charging on your own sun attractive.
 */
export function solarOpportunityCost(allIn, tariffs) {
  if (tariffs?.netMetering) return allIn;
  const feedIn = Number(tariffs?.feedInPerKwh) || 0;
  return Math.max(0, feedIn);
}

/** EUR/kWh -> ct/kWh, rounded to one decimal. */
export function toCents(eurPerKwh) {
  return Math.round(eurPerKwh * 1000) / 10;
}

export function formatEuro(amount) {
  const value = Number.isFinite(amount) ? amount : 0;
  return new Intl.NumberFormat('nl-NL', {
    style: 'currency',
    currency: 'EUR',
  }).format(value);
}

export function formatCents(eurPerKwh) {
  const value = Number.isFinite(eurPerKwh) ? eurPerKwh : 0;
  return `${new Intl.NumberFormat('nl-NL', {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  }).format(value * 100)} ct`;
}
