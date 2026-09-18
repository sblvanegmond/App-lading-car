/**
 * Photovoltaic yield model.
 *
 * Irradiance on the tilted plane (GTI) comes from the weather API, so the
 * only thing left to do here is convert W/m2 into AC kilowatts for a
 * specific system: peak power, system losses, cell temperature and the
 * inverter ceiling.
 */

/** Standard test conditions irradiance, W/m2. */
const STC = 1000;
/** Rough NOCT-style cell heating: +25 K at 800 W/m2. */
const CELL_HEATING_K_PER_WM2 = 25 / 800;

/**
 * DC power of a single array for a given plane-of-array irradiance.
 *
 * @param {number} gtiWm2 irradiance on the tilted plane, W/m2
 * @param {number} airTempC ambient temperature, degrees C
 * @param {object} array { kWp }
 * @param {object} pv settings.pv (performanceRatio, tempCoeff)
 * @returns {number} kW
 */
export function arrayPowerKw(gtiWm2, airTempC, array, pv) {
  const gti = Math.max(0, Number(gtiWm2) || 0);
  if (gti <= 0) return 0;
  const kWp = Math.max(0, Number(array?.kWp) || 0);
  if (kWp <= 0) return 0;

  const pr = clamp(Number(pv?.performanceRatio) || 0.86, 0.3, 1);
  const tempCoeff = Number.isFinite(pv?.tempCoeff) ? pv.tempCoeff : -0.004;

  const air = Number.isFinite(airTempC) ? airTempC : 15;
  const cellTemp = air + gti * CELL_HEATING_K_PER_WM2;
  // Panels lose output when hot and gain a little when cold.
  const tempFactor = clamp(1 + tempCoeff * (cellTemp - 25), 0.6, 1.15);

  return (gti / STC) * kWp * pr * tempFactor;
}

/**
 * AC power of the whole system for one hour.
 *
 * @param {Array<{array: object, gti: number}>} samples one entry per array
 * @param {number} airTempC
 * @param {object} pv settings.pv
 * @returns {number} kW after inverter clipping
 */
export function systemPowerKw(samples, airTempC, pv) {
  let dc = 0;
  for (const sample of samples ?? []) {
    dc += arrayPowerKw(sample.gti, airTempC, sample.array, pv);
  }
  const limit = Number(pv?.inverterKw);
  if (Number.isFinite(limit) && limit > 0) return Math.min(dc, limit);
  return dc;
}

/**
 * Fallback transposition when the weather API does not return plane-of-array
 * irradiance: a plain isotropic sky model on top of global horizontal
 * irradiance. Less accurate than real GTI, good enough to keep the app
 * usable.
 *
 * @param {number} ghi global horizontal irradiance, W/m2
 * @param {number} tiltDeg panel tilt
 * @returns {number} estimated irradiance on the tilted plane, W/m2
 */
export function estimateGtiFromGhi(ghi, tiltDeg) {
  const g = Math.max(0, Number(ghi) || 0);
  if (g <= 0) return 0;
  const tilt = clamp(Number(tiltDeg) || 0, 0, 90);
  // A 30-40 degree south-facing roof collects roughly 10-15% more than a
  // horizontal surface over a full day at this latitude.
  const gain = 1 + 0.15 * Math.sin((tilt * Math.PI) / 180);
  return g * gain;
}

/** Solar energy left over after the house has taken its share, in kW. */
export function surplusKw(pvKw, houseBaseLoadKw) {
  return Math.max(0, (Number(pvKw) || 0) - (Number(houseBaseLoadKw) || 0));
}

export function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}
