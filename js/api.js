/**
 * Data sources.
 *
 * Both APIs are free, need no account and no API key, and are called
 * straight from the phone. Nothing leaves the device except the coordinates
 * of your roof and the date range you ask for.
 *
 *  - Dynamic electricity prices (NL day-ahead / EPEX): api.energyzero.nl
 *  - Weather and irradiance: api.open-meteo.com
 */

import { systemPowerKw, estimateGtiFromGhi } from './solar.js';

const PRICE_CACHE_KEY = 'laadmoment.cache.prices.v1';
const SOLAR_CACHE_KEY = 'laadmoment.cache.solar.v1';
const CACHE_TTL_MS = 30 * 60 * 1000;

export class DataError extends Error {
  constructor(message, { source, cause } = {}) {
    super(message);
    this.name = 'DataError';
    this.source = source;
    this.cause = cause;
  }
}

async function fetchJson(url, { timeoutMs = 15000, label } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    });
    if (!response.ok) {
      throw new DataError(`${label}: server antwoordde met ${response.status}`, { source: label });
    }
    return await response.json();
  } catch (err) {
    if (err instanceof DataError) throw err;
    if (err.name === 'AbortError') {
      throw new DataError(`${label}: time-out, geen antwoord binnen ${timeoutMs / 1000}s`, { source: label });
    }
    throw new DataError(`${label}: ${err.message}`, { source: label, cause: err });
  } finally {
    clearTimeout(timer);
  }
}

function startOfLocalDay(date) {
  const d = new Date(date);
  d.setHours(0, 0, 0, 0);
  return d;
}

/**
 * Day-ahead prices for today and, once published, tomorrow.
 *
 * EnergyZero returns `{ Prices: [{ price, readingDate }] }` with the price in
 * EUR/kWh. We ask for the price excluding VAT and add tax, markup and VAT
 * ourselves, so the numbers match your own contract.
 *
 * @returns {Promise<Array<{start: Date, marketPrice: number}>>}
 */
export async function fetchPrices(settings, { now = new Date(), fetchImpl } = {}) {
  const from = startOfLocalDay(now);
  const till = new Date(from);
  till.setDate(till.getDate() + 2);
  till.setMilliseconds(-1);

  const source = settings?.prices?.source ?? 'energyzero';
  const custom = (settings?.prices?.customUrl ?? '').trim();
  const url =
    source === 'custom' && custom
      ? buildCustomUrl(custom, from, till)
      : 'https://api.energyzero.nl/v1/energyprices' +
        `?fromDate=${encodeURIComponent(from.toISOString())}` +
        `&tillDate=${encodeURIComponent(till.toISOString())}` +
        '&interval=4&usageType=1&inclBtw=false';

  const json = fetchImpl ? await fetchImpl(url) : await fetchJson(url, { label: 'Stroomprijzen' });
  const rows = parsePriceResponse(json);
  if (rows.length === 0) {
    throw new DataError('Stroomprijzen: geen bruikbare prijzen in het antwoord.', { source: 'prijzen' });
  }
  return rows;
}

function buildCustomUrl(template, from, till) {
  if (template.includes('{from}') || template.includes('{till}')) {
    return template
      .replaceAll('{from}', encodeURIComponent(from.toISOString()))
      .replaceAll('{till}', encodeURIComponent(till.toISOString()));
  }
  return template;
}

/**
 * Accepts the EnergyZero shape as well as a plain array of rows, so a custom
 * endpoint (Home Assistant, your own proxy) can be plugged in.
 */
export function parsePriceResponse(json) {
  const list = Array.isArray(json)
    ? json
    : json?.Prices ?? json?.prices ?? json?.data ?? json?.results ?? [];
  if (!Array.isArray(list)) return [];

  const rows = [];
  for (const item of list) {
    const stamp =
      item?.readingDate ?? item?.start ?? item?.datetime ?? item?.from ?? item?.time ?? item?.date;
    const raw =
      item?.price ?? item?.marketPrice ?? item?.value ?? item?.priceExclVat ?? item?.energy;
    if (stamp === undefined || raw === undefined || raw === null) continue;
    const start = new Date(stamp);
    const price = Number(raw);
    if (Number.isNaN(start.getTime()) || !Number.isFinite(price)) continue;
    // Some sources publish EUR/MWh; anything above 5 EUR per kWh is a signal.
    rows.push({ start, marketPrice: Math.abs(price) > 5 ? price / 1000 : price });
  }
  rows.sort((a, b) => a.start - b.start);
  return dedupeByHour(rows);
}

function dedupeByHour(rows) {
  const seen = new Map();
  for (const row of rows) {
    const key = new Date(row.start);
    key.setMinutes(0, 0, 0);
    seen.set(key.toISOString(), { ...row, start: key });
  }
  return [...seen.values()].sort((a, b) => a.start - b.start);
}

/**
 * Hourly irradiance on each of your roof planes, plus the sun hours for the
 * day. One request per array orientation, because tilt and azimuth are
 * request-level parameters of the API.
 *
 * @returns {Promise<{hours: Array, days: Array}>}
 */
export async function fetchSolar(settings, { fetchImpl } = {}) {
  const { latitude, longitude } = settings.location;
  const arrays = settings.pv.arrays.length
    ? settings.pv.arrays
    : [{ id: 'a1', kWp: 0, tilt: 35, azimuth: 0 }];

  const responses = [];
  for (const array of arrays) {
    const url =
      'https://api.open-meteo.com/v1/forecast' +
      `?latitude=${latitude}&longitude=${longitude}` +
      '&hourly=global_tilted_irradiance,shortwave_radiation,temperature_2m,cloud_cover,sunshine_duration' +
      '&daily=sunrise,sunset,sunshine_duration' +
      `&tilt=${Math.round(clampNum(array.tilt, 0, 90))}` +
      `&azimuth=${Math.round(clampNum(array.azimuth, -180, 180))}` +
      '&forecast_days=3&timezone=auto';
    const json = fetchImpl ? await fetchImpl(url) : await fetchJson(url, { label: 'Weerdata' });
    responses.push({ array, json });
  }

  return buildSolarSeries(responses, settings);
}

function clampNum(value, min, max) {
  const n = Number(value);
  if (!Number.isFinite(n)) return min;
  return Math.min(max, Math.max(min, n));
}

/**
 * Turn the raw weather responses into hourly PV output for the whole system.
 * Exported so it can be tested with recorded fixtures.
 */
export function buildSolarSeries(responses, settings) {
  if (!responses.length) return { hours: [], days: [] };
  const base = responses[0].json;
  const times = base?.hourly?.time ?? [];
  if (!times.length) {
    throw new DataError('Weerdata: geen uurwaarden ontvangen.', { source: 'weer' });
  }
  const utcOffsetSeconds = Number(base?.utc_offset_seconds ?? 0);

  const hours = [];
  for (let i = 0; i < times.length; i += 1) {
    const start = parseApiTime(times[i], utcOffsetSeconds);
    if (!start) continue;

    const samples = [];
    for (const { array, json } of responses) {
      const hourly = json?.hourly ?? {};
      const gtiSeries = hourly.global_tilted_irradiance;
      const ghiSeries = hourly.shortwave_radiation;
      let gti = Array.isArray(gtiSeries) ? Number(gtiSeries[i]) : NaN;
      if (!Number.isFinite(gti)) {
        const ghi = Array.isArray(ghiSeries) ? Number(ghiSeries[i]) : 0;
        gti = estimateGtiFromGhi(ghi, array.tilt);
      }
      samples.push({ array, gti: Math.max(0, gti) });
    }

    const tempC = numberAt(base?.hourly?.temperature_2m, i);
    const pvKw = systemPowerKw(samples, tempC, settings.pv);
    const sunshineSeconds = numberAt(base?.hourly?.sunshine_duration, i);

    hours.push({
      start,
      pvKw,
      tempC,
      cloudCoverPct: numberAt(base?.hourly?.cloud_cover, i),
      sunshineMinutes: Number.isFinite(sunshineSeconds) ? sunshineSeconds / 60 : null,
    });
  }

  const daily = base?.daily ?? {};
  const days = (daily.time ?? []).map((day, i) => ({
    date: day,
    sunrise: daily.sunrise?.[i] ?? null,
    sunset: daily.sunset?.[i] ?? null,
    sunshineHours: Number.isFinite(Number(daily.sunshine_duration?.[i]))
      ? Number(daily.sunshine_duration[i]) / 3600
      : null,
  }));

  return { hours, days };
}

function numberAt(series, index) {
  if (!Array.isArray(series)) return null;
  const value = Number(series[index]);
  return Number.isFinite(value) ? value : null;
}

/**
 * Open-Meteo returns local wall-clock stamps without a zone when
 * `timezone=auto` is used, so the offset it reports has to be applied.
 */
export function parseApiTime(value, utcOffsetSeconds = 0) {
  if (typeof value !== 'string') return null;
  // Insist on an ISO-ish shape first: the engine's own date parser happily
  // turns arbitrary text into some date rather than failing.
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value)) return null;
  const hasZone = /(Z|[+-]\d{2}:?\d{2})$/.test(value);
  const date = hasZone ? new Date(value) : new Date(`${value}:00Z`);
  if (Number.isNaN(date.getTime())) return null;
  return hasZone ? date : new Date(date.getTime() - utcOffsetSeconds * 1000);
}

/* ------------------------------------------------------------------ *
 * Caching: the app stays usable on a phone with no signal in a garage.
 * ------------------------------------------------------------------ */

function readCache(key, storage) {
  const store = storage ?? globalThis.localStorage;
  if (!store) return null;
  try {
    const raw = store.getItem(key);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return parsed && parsed.savedAt ? parsed : null;
  } catch {
    return null;
  }
}

function writeCache(key, payload, storage) {
  const store = storage ?? globalThis.localStorage;
  if (!store) return;
  try {
    store.setItem(key, JSON.stringify({ savedAt: Date.now(), payload }));
  } catch {
    /* storage full or blocked: caching is a nicety, not a requirement */
  }
}

export function cacheAgeMs(entry, now = Date.now()) {
  return entry ? now - entry.savedAt : Infinity;
}

/**
 * Fetch everything the planner needs, falling back to the last good data
 * when the network is unavailable.
 *
 * @returns {Promise<{prices: Array, solar: object, stale: boolean, errors: Array}>}
 */
export async function loadData(settings, { now = new Date(), force = false, storage } = {}) {
  const errors = [];
  let prices = null;
  let solar = null;
  let stale = false;

  const cachedPrices = readCache(PRICE_CACHE_KEY, storage);
  const cachedSolar = readCache(SOLAR_CACHE_KEY, storage);
  const fresh = (entry) => cacheAgeMs(entry, now.getTime()) < CACHE_TTL_MS;

  if (!force && fresh(cachedPrices)) {
    prices = revivePrices(cachedPrices.payload);
  } else {
    try {
      prices = await fetchPrices(settings, { now });
      writeCache(PRICE_CACHE_KEY, prices.map((p) => ({ ...p, start: p.start.toISOString() })), storage);
    } catch (err) {
      errors.push(err);
      if (cachedPrices) {
        prices = revivePrices(cachedPrices.payload);
        stale = true;
      }
    }
  }

  if (!force && fresh(cachedSolar)) {
    solar = reviveSolar(cachedSolar.payload);
  } else {
    try {
      solar = await fetchSolar(settings);
      writeCache(
        SOLAR_CACHE_KEY,
        { ...solar, hours: solar.hours.map((h) => ({ ...h, start: h.start.toISOString() })) },
        storage,
      );
    } catch (err) {
      errors.push(err);
      if (cachedSolar) {
        solar = reviveSolar(cachedSolar.payload);
        stale = true;
      }
    }
  }

  return {
    prices: prices ?? [],
    solar: solar ?? { hours: [], days: [] },
    stale,
    errors,
    fetchedAt: new Date(now),
  };
}

function revivePrices(payload) {
  return (payload ?? []).map((p) => ({ ...p, start: new Date(p.start) }));
}

function reviveSolar(payload) {
  return {
    days: payload?.days ?? [],
    hours: (payload?.hours ?? []).map((h) => ({ ...h, start: new Date(h.start) })),
  };
}
