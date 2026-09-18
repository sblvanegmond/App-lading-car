/**
 * Default settings + persistence.
 *
 * Everything the planner needs is stored in one plain object so it can be
 * serialised to localStorage and passed straight into the pure planning
 * functions (which is also what the unit tests do).
 */

export const STORAGE_KEY = 'laadmoment.settings.v1';

/** Grootegast, Groningen (NL). */
export const GROOTEGAST = {
  name: 'Grootegast',
  latitude: 53.2069,
  longitude: 6.2903,
  timezone: 'Europe/Amsterdam',
};

export const DEFAULT_SETTINGS = {
  location: { ...GROOTEGAST },

  /**
   * Solar arrays. Azimuth follows the Open-Meteo convention:
   *   0 = south, -90 = east, 90 = west, 180 = north.
   * Tilt is in degrees from horizontal (0 = flat roof, 35 = typical NL roof).
   */
  pv: {
    arrays: [
      { id: 'a1', label: 'Hoofdveld', kWp: 4.0, tilt: 35, azimuth: 0 },
    ],
    /** Performance ratio: cabling, soiling, inverter efficiency, mismatch. */
    performanceRatio: 0.86,
    /** AC clipping limit of the inverter in kW. null = no clipping. */
    inverterKw: 3.7,
    /** Temperature coefficient of the panels, per Kelvin (negative). */
    tempCoeff: -0.004,
  },

  /** Average household draw that eats into the solar surplus, in kW. */
  houseBaseLoadKw: 0.35,

  car: {
    batteryKwh: 58,
    /** Maximum AC charge power the wallbox + car combination reaches. */
    chargerKw: 11,
    /** Lowest power the car will accept (6 A single phase ≈ 1.4 kW). */
    minChargeKw: 1.4,
    currentSocPct: 30,
    targetSocPct: 80,
    /** AC charging losses (onboard charger + cable + battery), in percent. */
    chargeLossPct: 10,
  },

  /** The car has to be ready at this local time (HH:MM). */
  readyBy: '07:30',

  /** 'balans' | 'goedkoopst' | 'zon' */
  mode: 'balans',

  /**
   * Tariffs in EUR/kWh. The market price comes from the price API excluding
   * VAT; everything below is added on top to get the real all-in price.
   * Check these against your own energy contract once a year.
   */
  tariffs: {
    supplierMarkupPerKwh: 0.02,
    energyTaxPerKwh: 0.1015,
    vatPct: 21,
    /** What you are paid per kWh you feed back into the grid. */
    feedInPerKwh: 0.05,
    /**
     * Net metering ("saldering"). While it applies, every self-consumed kWh
     * is worth the full all-in price instead of the feed-in compensation,
     * so charging on your own solar saves you exactly the purchase price.
     */
    netMetering: false,
  },

  prices: {
    /** 'energyzero' or 'custom' */
    source: 'energyzero',
    /**
     * Custom endpoint returning either the EnergyZero shape or a plain
     * array of { start|readingDate|datetime, price } objects in EUR/kWh
     * excluding VAT. Handy if you already proxy prices via Home Assistant.
     */
    customUrl: '',
  },

  notifications: {
    enabled: false,
    /** Local time of the morning briefing (HH:MM). */
    time: '06:45',
  },

  /** Bumped by the app when the user last saw a briefing. */
  lastBriefingDate: null,
};

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** Recursive merge that keeps unknown keys out and arrays atomic. */
export function mergeSettings(base, override) {
  if (!isPlainObject(override)) return structuredCloneSafe(base);
  const out = structuredCloneSafe(base);
  for (const [key, value] of Object.entries(override)) {
    if (!(key in out)) continue;
    if (isPlainObject(out[key]) && isPlainObject(value)) {
      out[key] = mergeSettings(out[key], value);
    } else if (value !== undefined) {
      out[key] = structuredCloneSafe(value);
    }
  }
  return out;
}

function structuredCloneSafe(value) {
  return value === undefined ? value : JSON.parse(JSON.stringify(value));
}

export function defaultSettings() {
  return structuredCloneSafe(DEFAULT_SETTINGS);
}

export function loadSettings(storage) {
  const store = storage ?? globalThis.localStorage;
  if (!store) return defaultSettings();
  try {
    const raw = store.getItem(STORAGE_KEY);
    if (!raw) return defaultSettings();
    return mergeSettings(DEFAULT_SETTINGS, JSON.parse(raw));
  } catch (err) {
    console.warn('Kon instellingen niet lezen, val terug op standaard.', err);
    return defaultSettings();
  }
}

export function saveSettings(settings, storage) {
  const store = storage ?? globalThis.localStorage;
  if (!store) return;
  try {
    store.setItem(STORAGE_KEY, JSON.stringify(settings));
  } catch (err) {
    console.warn('Kon instellingen niet opslaan.', err);
  }
}

/** Total installed peak power over all arrays, in kWp. */
export function totalKwp(pv) {
  return (pv?.arrays ?? []).reduce((sum, a) => sum + (Number(a.kWp) || 0), 0);
}
