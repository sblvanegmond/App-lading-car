process.env.TZ = 'Europe/Amsterdam';

import test from 'node:test';
import assert from 'node:assert/strict';

import { parsePriceResponse, parseApiTime, buildSolarSeries, fetchPrices, fetchSolar } from '../js/api.js';
import { defaultSettings, mergeSettings } from '../js/config.js';
import { buildIcs, icsStamp } from '../js/ics.js';

/* ---------------------------- price parsing ---------------------------- */

test('reads the EnergyZero response shape', () => {
  const rows = parsePriceResponse({
    average: 0.1,
    Prices: [
      { price: 0.0812, readingDate: '2026-09-18T00:00:00Z' },
      { price: 0.0654, readingDate: '2026-09-18T01:00:00Z' },
    ],
  });
  assert.equal(rows.length, 2);
  assert.equal(rows[0].marketPrice, 0.0812);
  assert.equal(rows[0].start.toISOString(), '2026-09-18T00:00:00.000Z');
});

test('reads a plain array from a custom endpoint', () => {
  const rows = parsePriceResponse([
    { start: '2026-09-18T02:00:00Z', value: 0.05 },
    { datetime: '2026-09-18T03:00:00Z', price: 0.06 },
  ]);
  assert.equal(rows.length, 2);
  assert.equal(rows[1].marketPrice, 0.06);
});

test('converts a source that publishes euros per megawatt-hour', () => {
  const rows = parsePriceResponse([{ start: '2026-09-18T02:00:00Z', price: 84.5 }]);
  assert.equal(rows[0].marketPrice, 0.0845);
});

test('keeps negative prices, which really do occur on sunny days', () => {
  const rows = parsePriceResponse([{ start: '2026-09-18T13:00:00Z', price: -0.012 }]);
  assert.equal(rows[0].marketPrice, -0.012);
});

test('drops malformed rows instead of poisoning the plan', () => {
  const rows = parsePriceResponse([
    { start: 'niet-een-datum', price: 0.1 },
    { start: '2026-09-18T02:00:00Z', price: 'kapot' },
    { start: '2026-09-18T03:00:00Z', price: 0.07 },
    null,
  ]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].marketPrice, 0.07);
});

test('duplicate hours collapse to one row', () => {
  const rows = parsePriceResponse([
    { start: '2026-09-18T02:00:00Z', price: 0.05 },
    { start: '2026-09-18T02:30:00Z', price: 0.09 },
  ]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].marketPrice, 0.09);
});

test('an empty response is reported rather than silently accepted', async () => {
  const settings = defaultSettings();
  await assert.rejects(
    () => fetchPrices(settings, { fetchImpl: async () => ({ Prices: [] }) }),
    /geen bruikbare prijzen/i,
  );
});

test('the price request covers today and tomorrow and asks for prices without VAT', async () => {
  const settings = defaultSettings();
  let seen = null;
  await fetchPrices(settings, {
    now: new Date('2026-09-18T10:00:00+02:00'),
    fetchImpl: async (url) => {
      seen = url;
      return { Prices: [{ price: 0.05, readingDate: '2026-09-18T00:00:00Z' }] };
    },
  });
  assert.match(seen, /api\.energyzero\.nl/);
  assert.match(seen, /inclBtw=false/);
  assert.match(seen, /interval=4/);
  const from = decodeURIComponent(seen.match(/fromDate=([^&]+)/)[1]);
  const till = decodeURIComponent(seen.match(/tillDate=([^&]+)/)[1]);
  assert.equal(new Date(from).toISOString(), '2026-09-17T22:00:00.000Z'); // local midnight
  assert.ok(new Date(till) - new Date(from) > 47 * 3600000);
});

test('a custom endpoint gets its placeholders filled in', async () => {
  const settings = mergeSettings(defaultSettings(), {
    prices: { source: 'custom', customUrl: 'https://thuis.local/prijzen?a={from}&b={till}' },
  });
  let seen = null;
  await fetchPrices(settings, {
    now: new Date('2026-09-18T10:00:00+02:00'),
    fetchImpl: async (url) => {
      seen = url;
      return [{ start: '2026-09-18T00:00:00Z', price: 0.05 }];
    },
  });
  assert.match(seen, /^https:\/\/thuis\.local\/prijzen\?a=2026-09-17T22/);
  assert.ok(!seen.includes('{from}'));
});

/* ---------------------------- time parsing ---------------------------- */

test('local stamps without a zone are corrected with the reported offset', () => {
  assert.equal(parseApiTime('2026-09-18T07:00', 7200).toISOString(), '2026-09-18T05:00:00.000Z');
  assert.equal(parseApiTime('2026-01-18T07:00', 3600).toISOString(), '2026-01-18T06:00:00.000Z');
});

test('stamps that carry a zone are left alone', () => {
  assert.equal(parseApiTime('2026-09-18T05:00:00Z', 7200).toISOString(), '2026-09-18T05:00:00.000Z');
});

test('nonsense stamps become null instead of Invalid Date', () => {
  assert.equal(parseApiTime('welke dag dan ook', 0), null);
  assert.equal(parseApiTime(undefined, 0), null);
});

/* ---------------------------- solar series ---------------------------- */

function weatherResponse({ gti = [0, 400, 800], ghi = [0, 350, 700] } = {}) {
  return {
    utc_offset_seconds: 7200,
    hourly: {
      time: ['2026-06-15T10:00', '2026-06-15T11:00', '2026-06-15T12:00'],
      global_tilted_irradiance: gti,
      shortwave_radiation: ghi,
      temperature_2m: [18, 20, 22],
      cloud_cover: [80, 40, 5],
      sunshine_duration: [0, 1800, 3600],
    },
    daily: {
      time: ['2026-06-15'],
      sunrise: ['2026-06-15T05:22'],
      sunset: ['2026-06-15T22:03'],
      sunshine_duration: [36000],
    },
  };
}

test('builds hourly PV output from tilted irradiance', () => {
  const settings = defaultSettings();
  const { hours, days } = buildSolarSeries(
    [{ array: settings.pv.arrays[0], json: weatherResponse() }],
    settings,
  );
  assert.equal(hours.length, 3);
  assert.equal(hours[0].pvKw, 0);
  assert.ok(hours[2].pvKw > hours[1].pvKw);
  assert.ok(hours[2].pvKw <= settings.pv.inverterKw);
  assert.equal(hours[1].sunshineMinutes, 30);
  assert.equal(hours[0].start.toISOString(), '2026-06-15T08:00:00.000Z');
  assert.equal(days[0].sunshineHours, 10);
});

test('two roof planes add up', () => {
  const settings = mergeSettings(defaultSettings(), {
    pv: { inverterKw: null, arrays: [{ id: 'a1', kWp: 3, tilt: 35, azimuth: -90 }] },
  });
  const one = buildSolarSeries([{ array: settings.pv.arrays[0], json: weatherResponse() }], settings);
  const two = buildSolarSeries(
    [
      { array: settings.pv.arrays[0], json: weatherResponse() },
      { array: { kWp: 3, tilt: 35, azimuth: 90 }, json: weatherResponse({ gti: [0, 200, 300] }) },
    ],
    settings,
  );
  assert.ok(two.hours[2].pvKw > one.hours[2].pvKw);
});

test('falls back to horizontal irradiance when the tilted value is missing', () => {
  const settings = mergeSettings(defaultSettings(), { pv: { inverterKw: null } });
  const json = weatherResponse();
  delete json.hourly.global_tilted_irradiance;
  const { hours } = buildSolarSeries([{ array: settings.pv.arrays[0], json }], settings);
  assert.ok(hours[2].pvKw > 0, 'zonder GTI moet de schatting uit GHI komen');
});

test('a response without hours is rejected with a readable message', () => {
  assert.throws(
    () => buildSolarSeries([{ array: { kWp: 4, tilt: 35, azimuth: 0 }, json: { hourly: {} } }], defaultSettings()),
    /geen uurwaarden/i,
  );
});

test('the weather request carries the roof orientation and the location', async () => {
  const settings = mergeSettings(defaultSettings(), {
    pv: { arrays: [{ id: 'a1', kWp: 4, tilt: 40, azimuth: -90 }] },
  });
  const urls = [];
  await fetchSolar(settings, {
    fetchImpl: async (url) => {
      urls.push(url);
      return weatherResponse();
    },
  });
  assert.equal(urls.length, 1);
  assert.match(urls[0], /latitude=53\.2069/);
  assert.match(urls[0], /longitude=6\.2903/);
  assert.match(urls[0], /tilt=40/);
  assert.match(urls[0], /azimuth=-90/);
  assert.match(urls[0], /global_tilted_irradiance/);
});

test('one request goes out per roof plane', async () => {
  const settings = mergeSettings(defaultSettings(), {
    pv: {
      arrays: [
        { id: 'a1', kWp: 3, tilt: 35, azimuth: -90 },
        { id: 'a2', kWp: 3, tilt: 35, azimuth: 90 },
      ],
    },
  });
  const urls = [];
  await fetchSolar(settings, {
    fetchImpl: async (url) => {
      urls.push(url);
      return weatherResponse();
    },
  });
  assert.equal(urls.length, 2);
  assert.match(urls[0], /azimuth=-90/);
  assert.match(urls[1], /azimuth=90/);
});

/* ---------------------------- calendar ---------------------------- */

test('the calendar file is valid iCalendar with an alarm per window', () => {
  const plan = {
    blocks: [
      {
        from: new Date('2026-09-18T02:00:00Z'),
        to: new Date('2026-09-18T05:00:00Z'),
        kwh: 11.2,
        cost: 1.34,
        solarSharePct: 0,
        avgPricePerKwh: 0.12,
      },
      {
        from: new Date('2026-09-18T12:00:00Z'),
        to: new Date('2026-09-18T14:00:00Z'),
        kwh: 7,
        cost: 0.2,
        solarSharePct: 92,
        avgPricePerKwh: 0.03,
      },
    ],
  };
  const ics = buildIcs(plan);
  assert.match(ics, /^BEGIN:VCALENDAR\r\n/);
  assert.match(ics, /END:VCALENDAR\r\n$/);
  assert.equal(ics.match(/BEGIN:VEVENT/g).length, 2);
  assert.equal(ics.match(/BEGIN:VALARM/g).length, 2);
  assert.match(ics, /DTSTART:20260918T020000Z/);
  assert.match(ics, /DTEND:20260918T140000Z/);
  // Commas inside the summary have to be escaped or calendars mis-parse it.
  assert.match(ics, /SUMMARY:Auto laden \(11\\,2 kWh/);
  for (const line of ics.split('\r\n')) {
    assert.ok(line.length <= 75, `regel te lang voor iCalendar: ${line}`);
  }
});

test('a plan without windows still produces a well-formed empty calendar', () => {
  const ics = buildIcs({ blocks: [] });
  assert.match(ics, /BEGIN:VCALENDAR/);
  assert.ok(!ics.includes('BEGIN:VEVENT'));
});

test('timestamps are written in UTC basic format', () => {
  assert.equal(icsStamp(new Date('2026-09-18T04:05:06Z')), '20260918T040506Z');
});
