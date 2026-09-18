process.env.TZ = 'Europe/Amsterdam';

import test from 'node:test';
import assert from 'node:assert/strict';

import { defaultSettings, mergeSettings } from '../js/config.js';
import { buildTimeline, planCharging } from '../js/planner.js';
import { briefingNotification, briefingText, dayWord } from '../js/briefing.js';
import { insideWindow } from '../tools/notify.js';
import { generateVapidKeys } from '../tools/generate-vapid.js';
import { loadData } from '../js/api.js';

function settingsFor(overrides = {}) {
  return mergeSettings(
    mergeSettings(defaultSettings(), {
      car: {
        batteryKwh: 50,
        chargerKw: 10,
        currentSocPct: 20,
        targetSocPct: 60,
        chargeLossPct: 0,
      },
      houseBaseLoadKw: 0,
      readyBy: '07:00',
      tariffs: { supplierMarkupPerKwh: 0, energyTaxPerKwh: 0, vatPct: 0, feedInPerKwh: 0 },
    }),
    overrides,
  );
}

function priceRows(from, prices) {
  return prices.map((marketPrice, i) => ({
    start: new Date(from.getTime() + i * 3600000),
    marketPrice,
  }));
}

/* ---------------------------- wording ---------------------------- */

test('the notification names the window, the energy and the cost', () => {
  const settings = settingsFor();
  const now = new Date('2026-01-15T22:00:00+01:00');
  const prices = priceRows(now, [0.5, 0.1, 0.1, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5]);
  const plan = planCharging({ timeline: buildTimeline(prices, [], settings), settings, now });

  const { title, body, empty } = briefingNotification(plan, { now });
  assert.equal(empty, false);
  assert.match(title, /^Laden vandaag 23:00–01:00$/);
  assert.match(body, /20,0 kWh voor €\s2,00/);
  assert.match(body, /10,0 ct\/kWh/);
  assert.match(body, /goedkoper dan nu laden/);
});

test('a plan that needs nothing says so instead of pretending', () => {
  const settings = settingsFor({ car: { currentSocPct: 80, targetSocPct: 80 } });
  const now = new Date('2026-01-15T22:00:00+01:00');
  const plan = planCharging({
    timeline: buildTimeline(priceRows(now, [0.1, 0.1]), [], settings),
    settings,
    now,
  });

  const { body, empty } = briefingNotification(plan, { now });
  assert.equal(empty, true);
  assert.match(body, /niet nodig/i);
});

test('a missing plan does not throw', () => {
  const { title, body, empty } = briefingNotification(null, { now: new Date() });
  assert.equal(empty, true);
  assert.equal(title, 'Laadmoment');
  assert.ok(body.length > 0);
});

test('the solar share only appears when there is sun in the plan', () => {
  const settings = settingsFor();
  const now = new Date('2026-06-15T08:00:00+02:00');
  const prices = priceRows(now, [0.2, 0.2, 0.2, 0.2, 0.2, 0.2]);
  const solar = [0, 0, 10, 10, 0, 0].map((pvKw, i) => ({
    start: new Date(now.getTime() + i * 3600000),
    pvKw,
    tempC: 20,
  }));

  const sunny = planCharging({ timeline: buildTimeline(prices, solar, settings), settings, now });
  assert.match(briefingNotification(sunny, { now }).body, /% eigen zon/);

  const dark = planCharging({ timeline: buildTimeline(prices, [], settings), settings, now });
  assert.ok(!briefingNotification(dark, { now }).body.includes('eigen zon'));
});

test('the long text lists every block and repeats the warnings', () => {
  // 40 kWh is needed but only three hours of prices exist, so it cannot fit.
  const settings = settingsFor({ car: { targetSocPct: 100 } });
  const now = new Date('2026-01-15T22:00:00+01:00');
  const prices = priceRows(now, [0.5, 0.1, 0.1]);
  const plan = planCharging({ timeline: buildTimeline(prices, [], settings), settings, now });

  const text = briefingText(plan, settings, { now });
  assert.match(text, /^Laadplan voor Grootegast:/);
  assert.match(text, /Totaal .* kWh voor €/);
  assert.match(text, /Let op: /);
  assert.equal(text.split('\n').filter((l) => l.startsWith('•')).length, plan.blocks.length);
});

test('day words stay readable across the date boundary', () => {
  const now = new Date('2026-01-15T22:00:00+01:00');
  assert.equal(dayWord(new Date('2026-01-15T23:00:00+01:00'), now), 'vandaag');
  assert.equal(dayWord(new Date('2026-01-16T01:00:00+01:00'), now), 'morgen');
  assert.equal(dayWord(new Date('2026-01-17T01:00:00+01:00'), now), 'overmorgen');
});

/* ---------------------------- send window ---------------------------- */

test('the notifier only sends in the hour after the chosen time', () => {
  const at = (iso) => new Date(iso);
  assert.equal(insideWindow(at('2026-01-15T06:45:00+01:00'), '06:45'), true);
  assert.equal(insideWindow(at('2026-01-15T07:30:00+01:00'), '06:45'), true);
  assert.equal(insideWindow(at('2026-01-15T07:45:00+01:00'), '06:45'), false);
  assert.equal(insideWindow(at('2026-01-15T06:44:00+01:00'), '06:45'), false);
  assert.equal(insideWindow(at('2026-01-15T22:00:00+01:00'), '06:45'), false);
});

test('a broken time falls back instead of sending at random', () => {
  assert.equal(insideWindow(new Date('2026-01-15T06:50:00+01:00'), 'onzin'), true);
  assert.equal(insideWindow(new Date('2026-01-15T09:00:00+01:00'), undefined), false);
});

/* ---------------------------- VAPID keys ---------------------------- */

test('generated VAPID keys have the shape push services require', () => {
  const { publicKey, privateKey } = generateVapidKeys();
  const pub = Buffer.from(publicKey, 'base64url');
  const priv = Buffer.from(privateKey, 'base64url');
  assert.equal(pub.length, 65);
  assert.equal(pub[0], 4);
  assert.equal(priv.length, 32);
  assert.notEqual(publicKey, generateVapidKeys().publicKey);
});

/* ---------------------------- whole pipeline ---------------------------- */

test('the notifier pipeline turns two API responses into one sentence', async () => {
  const settings = settingsFor({ readyBy: '07:00', notifications: { time: '06:45' } });
  const now = new Date('2026-06-15T05:00:00+02:00');

  const midnight = new Date('2026-06-15T00:00:00+02:00');
  const prices = [];
  for (let i = 0; i < 48; i += 1) {
    prices.push({
      price: i % 24 === 3 ? 0.01 : 0.2, // one very cheap hour, at 03:00
      readingDate: new Date(midnight.getTime() + i * 3600000).toISOString(),
    });
  }

  const times = [];
  const gti = [];
  for (let i = 0; i < 48; i += 1) {
    const t = new Date(midnight.getTime() + i * 3600000);
    const local = new Date(t.getTime() + 2 * 3600000);
    times.push(local.toISOString().slice(0, 16));
    gti.push(local.getUTCHours() >= 10 && local.getUTCHours() <= 15 ? 700 : 0);
  }

  const original = globalThis.fetch;
  const seen = [];
  globalThis.fetch = async (url) => {
    seen.push(String(url));
    const body = String(url).includes('energyzero')
      ? { Prices: prices }
      : {
          utc_offset_seconds: 7200,
          hourly: {
            time: times,
            global_tilted_irradiance: gti,
            temperature_2m: times.map(() => 18),
            cloud_cover: times.map(() => 20),
            sunshine_duration: times.map(() => 0),
          },
          daily: { time: ['2026-06-15'], sunrise: ['2026-06-15T05:22'], sunset: ['2026-06-15T22:03'], sunshine_duration: [36000] },
        };
    return { ok: true, status: 200, json: async () => body };
  };

  try {
    const data = await loadData(settings, { now, force: true });
    assert.equal(data.errors.length, 0, `onverwachte fouten: ${data.errors.map((e) => e.message)}`);
    assert.ok(data.prices.length >= 24);

    const timeline = buildTimeline(data.prices, data.solar.hours, settings);
    const plan = planCharging({ timeline, settings, now });
    const { title, body, empty } = briefingNotification(plan, { now });

    assert.equal(empty, false);
    assert.match(title, /^Laden vandaag 05:00–/);
    assert.match(body, /kWh voor €/);
    assert.equal(seen.filter((u) => u.includes('energyzero')).length, 1);
    assert.equal(seen.filter((u) => u.includes('open-meteo')).length, settings.pv.arrays.length);
  } finally {
    globalThis.fetch = original;
  }
});
