process.env.TZ = 'Europe/Amsterdam';

import test from 'node:test';
import assert from 'node:assert/strict';

import { defaultSettings, mergeSettings } from '../js/config.js';
import { allInPrice, solarOpportunityCost } from '../js/pricing.js';
import { arrayPowerKw, systemPowerKw, surplusKw, estimateGtiFromGhi } from '../js/solar.js';
import {
  buildTimeline,
  planCharging,
  energyNeed,
  nextTimeOccurrence,
  mergeBlocks,
  solarSummary,
} from '../js/planner.js';

/** Settings tuned for predictable arithmetic in the tests. */
function testSettings(overrides = {}) {
  return mergeSettings(
    mergeSettings(defaultSettings(), {
      car: {
        batteryKwh: 50,
        chargerKw: 10,
        minChargeKw: 1.4,
        currentSocPct: 20,
        targetSocPct: 60,
        chargeLossPct: 0,
      },
      houseBaseLoadKw: 0,
      readyBy: '07:00',
      tariffs: {
        supplierMarkupPerKwh: 0,
        energyTaxPerKwh: 0,
        vatPct: 0,
        feedInPerKwh: 0,
        netMetering: false,
      },
    }),
    overrides,
  );
}

/** One row per hour, starting at `from`, with the given market prices. */
function priceRows(from, prices) {
  return prices.map((price, i) => ({
    start: new Date(from.getTime() + i * 3600000),
    marketPrice: price,
  }));
}

function solarRows(from, kws) {
  return kws.map((pvKw, i) => ({
    start: new Date(from.getTime() + i * 3600000),
    pvKw,
    tempC: 15,
  }));
}

/* ---------------------------- pricing ---------------------------- */

test('all-in price stacks markup, energy tax and VAT onto the market price', () => {
  const price = allInPrice(0.1, {
    supplierMarkupPerKwh: 0.02,
    energyTaxPerKwh: 0.1,
    vatPct: 21,
  });
  assert.equal(Math.round(price * 10000) / 10000, 0.2662);
});

test('a negative market price stays negative after tax when it is deep enough', () => {
  const price = allInPrice(-0.3, { supplierMarkupPerKwh: 0.02, energyTaxPerKwh: 0.1, vatPct: 21 });
  assert.ok(price < 0, `verwacht negatieve prijs, kreeg ${price}`);
});

test('net metering makes self-consumed solar worth the full purchase price', () => {
  assert.equal(solarOpportunityCost(0.3, { netMetering: true, feedInPerKwh: 0.05 }), 0.3);
  assert.equal(solarOpportunityCost(0.3, { netMetering: false, feedInPerKwh: 0.05 }), 0.05);
});

/* ---------------------------- solar ---------------------------- */

test('PV output scales with irradiance and is zero in the dark', () => {
  const pv = { performanceRatio: 0.85, tempCoeff: -0.004, inverterKw: null };
  const array = { kWp: 4 };
  assert.equal(arrayPowerKw(0, 10, array, pv), 0);
  const half = arrayPowerKw(500, 25, array, pv);
  const full = arrayPowerKw(1000, 25, array, pv);
  assert.ok(full > half && half > 0);
  // At 25 degrees cell temperature the model is linear in irradiance.
  assert.ok(Math.abs(full / half - 2) < 0.25);
});

test('hot panels produce less than cold panels at the same irradiance', () => {
  const pv = { performanceRatio: 0.85, tempCoeff: -0.004, inverterKw: null };
  const cold = arrayPowerKw(800, 2, { kWp: 4 }, pv);
  const hot = arrayPowerKw(800, 32, { kWp: 4 }, pv);
  assert.ok(cold > hot, `koud ${cold} zou meer moeten zijn dan warm ${hot}`);
});

test('the inverter clips the combined output of all arrays', () => {
  const pv = { performanceRatio: 1, tempCoeff: 0, inverterKw: 3.7 };
  const samples = [
    { array: { kWp: 4 }, gti: 1000 },
    { array: { kWp: 4 }, gti: 1000 },
  ];
  assert.equal(systemPowerKw(samples, 25, pv), 3.7);
});

test('household load is taken off the top before the car gets anything', () => {
  assert.equal(surplusKw(3, 0.4), 2.6);
  assert.equal(surplusKw(0.2, 0.4), 0);
});

test('the tilt fallback gives a south roof more than a flat surface', () => {
  assert.ok(estimateGtiFromGhi(500, 35) > estimateGtiFromGhi(500, 0));
  assert.equal(estimateGtiFromGhi(0, 35), 0);
});

/* ---------------------------- energy need ---------------------------- */

test('charging losses increase the energy that has to be bought', () => {
  const need = energyNeed({ batteryKwh: 60, currentSocPct: 20, targetSocPct: 80, chargeLossPct: 10 });
  assert.equal(need.netKwh, 36);
  assert.equal(Math.round(need.grossKwh * 100) / 100, 40);
});

test('a full battery needs nothing', () => {
  const need = energyNeed({ batteryKwh: 60, currentSocPct: 90, targetSocPct: 80, chargeLossPct: 10 });
  assert.equal(need.grossKwh, 0);
});

/* ---------------------------- deadline ---------------------------- */

test('the deadline rolls over to tomorrow when the time has already passed', () => {
  const evening = new Date('2026-01-15T21:00:00+01:00');
  const deadline = nextTimeOccurrence(evening, '07:00');
  assert.equal(deadline.getHours(), 7);
  assert.equal(deadline.getDate(), 16);
});

test('a deadline later today stays today', () => {
  const morning = new Date('2026-01-15T05:00:00+01:00');
  const deadline = nextTimeOccurrence(morning, '07:00');
  assert.equal(deadline.getDate(), 15);
});

/* ---------------------------- planning ---------------------------- */

test('cheapest mode picks the lowest priced hours and respects the deadline', () => {
  const settings = testSettings({ mode: 'goedkoopst' });
  const now = new Date('2026-01-15T22:00:00+01:00');
  const prices = priceRows(now, [0.4, 0.05, 0.05, 0.3, 0.02, 0.5, 0.5, 0.5]);
  const timeline = buildTimeline(prices, [], settings);
  const plan = planCharging({ timeline, settings, now });

  // 20 kWh at 10 kW = two hours.
  assert.equal(plan.feasible, true);
  assert.equal(Math.round(plan.totals.kwh), 20);
  const hours = plan.slots.map((s) => s.from.getHours()).sort((a, b) => a - b);
  assert.deepEqual(hours, [2, 23]); // 0.02 at 02:00 and 0.05 at 23:00
  assert.ok(plan.windowEnd <= new Date('2026-01-16T07:00:00+01:00'));
});

test('hours after the deadline are never used', () => {
  // 20 kWh is needed but the fixed window only fits 15 kWh at 10 kW.
  const settings = testSettings({ mode: 'goedkoopst' });
  const now = new Date('2026-01-15T22:00:00+01:00');
  const deadline = new Date('2026-01-15T23:30:00+01:00');
  // The cheapest hours of all sit at 02:00 and 03:00, well past the deadline.
  const prices = priceRows(now, [0.4, 0.3, 0.2, 0.3, 0.01, 0.01]);
  const timeline = buildTimeline(prices, [], settings);
  const plan = planCharging({ timeline, settings, now, deadline });

  for (const slot of plan.slots) {
    assert.ok(slot.to <= plan.windowEnd, `${slot.to.toISOString()} ligt na de deadline`);
  }
  assert.equal(plan.feasible, false);
  assert.ok(plan.reachableSocPct < settings.car.targetSocPct);
  assert.ok(plan.warnings.length > 0);
});

test('balanced mode prefers a sunny hour over a slightly cheaper dark hour', () => {
  const settings = testSettings({ mode: 'balans', readyBy: '20:00' });
  const now = new Date('2026-06-15T08:00:00+02:00');
  // 09:00 is dark and cheap, 10:00 is sunny and a bit dearer.
  const prices = priceRows(now, [0.2, 0.09, 0.1, 0.2]);
  const solar = solarRows(now, [0, 0, 10, 0]);
  const timeline = buildTimeline(prices, solar, settings);

  const settingsOneHour = mergeSettings(settings, { car: { targetSocPct: 40 } }); // 10 kWh = 1 hour
  const plan = planCharging({ timeline, settings: settingsOneHour, now });

  assert.equal(plan.slots.length, 1);
  assert.equal(plan.slots[0].from.getHours(), 10);
  assert.ok(plan.totals.solarSharePct > 99);
  assert.ok(plan.totals.cost < 0.01, 'gratis eigen zon zou bijna niets mogen kosten');
});

test('with net metering the same solar hour loses its advantage', () => {
  const settings = testSettings({
    mode: 'balans',
    readyBy: '20:00',
    car: { targetSocPct: 40 },
    tariffs: { netMetering: true },
  });
  const now = new Date('2026-06-15T08:00:00+02:00');
  const prices = priceRows(now, [0.2, 0.09, 0.1, 0.2]);
  const solar = solarRows(now, [0, 0, 10, 0]);
  const plan = planCharging({ timeline: buildTimeline(prices, solar, settings), settings, now });

  assert.equal(plan.slots[0].from.getHours(), 9, 'zonder zonvoordeel wint het goedkoopste uur');
});

test('solar mode charges on the surplus and tops up from the grid when the sun falls short', () => {
  const settings = testSettings({ mode: 'zon', readyBy: '22:00', car: { targetSocPct: 60 } });
  const now = new Date('2026-06-15T09:00:00+02:00');
  const prices = priceRows(now, [0.3, 0.3, 0.05, 0.3, 0.3, 0.3]);
  const solar = solarRows(now, [4, 5, 0, 0, 0, 0]);
  const plan = planCharging({ timeline: buildTimeline(prices, solar, settings), settings, now });

  // 20 kWh needed, 9 kWh available from the sun.
  assert.ok(plan.totals.solarKwh > 8.9 && plan.totals.solarKwh < 9.1);
  assert.equal(Math.round(plan.totals.kwh), 20);
  assert.ok(plan.feasible, 'bijladen uit het net moet het doel alsnog halen');
  assert.ok(plan.warnings.some((w) => w.includes('aan')), 'er hoort een waarschuwing over bijladen te staan');
  assert.equal(Math.round(plan.totals.gridKwh), 11);
  // The bulk of the grid top-up lands in the cheap 11:00 hour.
  const biggestGridSlot = plan.slots.reduce((best, s) => (s.gridKwh > best.gridKwh ? s : best));
  assert.equal(biggestGridSlot.from.getHours(), 11);
  // Solar counted once: 9 kWh free plus 10 kWh at 5 ct plus 1 kWh at 30 ct.
  assert.equal(Math.round(plan.totals.cost * 100) / 100, 0.8);
});

test('solar mode ignores a trickle the car would refuse', () => {
  const settings = testSettings({ mode: 'zon', readyBy: '22:00', car: { targetSocPct: 30 } });
  const now = new Date('2026-06-15T09:00:00+02:00');
  const prices = priceRows(now, [0.3, 0.3, 0.3, 0.3]);
  const solar = solarRows(now, [0.8, 0.9, 5, 0]); // below minChargeKw until 11:00
  const plan = planCharging({ timeline: buildTimeline(prices, solar, settings), settings, now });

  const solarHours = plan.slots.filter((s) => s.source === 'zon').map((s) => s.from.getHours());
  assert.deepEqual(solarHours, [11]);
});

test('a partial final hour is charged pro rata, not rounded up', () => {
  const settings = testSettings({ mode: 'goedkoopst', car: { targetSocPct: 50 } }); // 15 kWh
  const now = new Date('2026-01-15T22:00:00+01:00');
  const prices = priceRows(now, [0.1, 0.2, 0.3, 0.4, 0.5]);
  const plan = planCharging({ timeline: buildTimeline(prices, [], settings), settings, now });

  assert.equal(Math.round(plan.totals.kwh * 100) / 100, 15);
  const partial = plan.slots.find((s) => s.partial);
  assert.ok(partial, 'er hoort een deels benut uur te zijn');
  assert.equal(Math.round(partial.kwh * 100) / 100, 5);
  assert.equal(Math.round(plan.totals.cost * 1000) / 1000, 0.1 * 10 + 0.2 * 5);
});

test('consecutive hours are merged into one window', () => {
  const settings = testSettings({ mode: 'goedkoopst', car: { targetSocPct: 60 } }); // 20 kWh
  const now = new Date('2026-01-15T22:00:00+01:00');
  const prices = priceRows(now, [0.5, 0.1, 0.1, 0.5, 0.5]);
  const plan = planCharging({ timeline: buildTimeline(prices, [], settings), settings, now });

  assert.equal(plan.blocks.length, 1);
  assert.equal(plan.blocks[0].from.getHours(), 23);
  assert.equal(plan.blocks[0].to.getHours(), 1);
});

test('the plan never costs more than charging right away', () => {
  const settings = testSettings({ mode: 'balans', car: { targetSocPct: 60 } });
  const now = new Date('2026-01-15T22:00:00+01:00');
  const prices = priceRows(now, [0.6, 0.5, 0.05, 0.05, 0.4, 0.4, 0.4, 0.4]);
  const plan = planCharging({ timeline: buildTimeline(prices, [], settings), settings, now });

  assert.ok(plan.comparison.savingsVsImmediate > 0);
  assert.ok(plan.totals.cost <= plan.comparison.immediateCost + 1e-9);
});

test('an already charged battery produces no plan and no cost', () => {
  const settings = testSettings({ car: { currentSocPct: 80, targetSocPct: 80 } });
  const now = new Date('2026-01-15T22:00:00+01:00');
  const plan = planCharging({
    timeline: buildTimeline(priceRows(now, [0.1, 0.1]), [], settings),
    settings,
    now,
  });
  assert.equal(plan.slots.length, 0);
  assert.equal(plan.totals.cost, 0);
  assert.equal(plan.feasible, true);
});

test('missing price data does not crash the planner', () => {
  const settings = testSettings();
  const now = new Date('2026-01-15T22:00:00+01:00');
  const plan = planCharging({ timeline: buildTimeline([], [], settings), settings, now });
  assert.equal(plan.feasible, false);
  assert.equal(plan.slots.length, 0);
  assert.ok(plan.warnings.length > 0);
});

test('hours without a price are skipped unless solar mode can use them', () => {
  const settings = testSettings({ mode: 'goedkoopst', car: { targetSocPct: 30 } });
  const now = new Date('2026-01-15T22:00:00+01:00');
  const timeline = buildTimeline(priceRows(now, [0.1]), solarRows(now, [0, 5, 5]), settings);
  const plan = planCharging({ timeline, settings, now });
  assert.ok(plan.slots.every((s) => s.hasPrice));
});

/* ---------------------------- helpers ---------------------------- */

test('timeline merges price and solar rows on the hour', () => {
  const settings = testSettings();
  const now = new Date('2026-06-15T10:00:00+02:00');
  const timeline = buildTimeline(
    [{ start: new Date('2026-06-15T10:00:00+02:00'), marketPrice: 0.1 }],
    [{ start: new Date('2026-06-15T10:30:00+02:00'), pvKw: 3, tempC: 20 }],
    settings,
  );
  assert.equal(timeline.length, 1);
  assert.equal(timeline[0].pvKw, 3);
  assert.equal(timeline[0].hasPrice, true);
  assert.ok(now);
});

test('solar summary adds the day up and finds the peak', () => {
  const settings = testSettings();
  const now = new Date('2026-06-15T08:00:00+02:00');
  const timeline = buildTimeline([], solarRows(now, [1, 3, 2]), settings);
  const [day] = solarSummary(timeline);
  assert.equal(day.pvKwh, 6);
  assert.equal(day.peakKw, 3);
  assert.equal(day.peakAt.getHours(), 9);
});

test('mergeBlocks keeps non-adjacent windows apart', () => {
  const base = (h) => ({
    from: new Date(`2026-01-15T0${h}:00:00+01:00`),
    to: new Date(`2026-01-15T0${h + 1}:00:00+01:00`),
    kwh: 10,
    solarKwh: 0,
    gridKwh: 10,
    cost: 1,
  });
  const blocks = mergeBlocks([base(1), base(2), base(5)]);
  assert.equal(blocks.length, 2);
  assert.equal(blocks[0].kwh, 20);
  assert.equal(blocks[1].kwh, 10);
});

test('a deadline too close to deliver the charge rolls to the next day', () => {
  const settings = testSettings({ mode: 'goedkoopst', readyBy: '07:30' });
  // 20 kWh at 10 kW needs two hours; only half an hour is left today.
  const now = new Date('2026-01-15T07:00:00+01:00');
  const prices = priceRows(now, new Array(30).fill(0.2));
  const plan = planCharging({ timeline: buildTimeline(prices, [], settings), settings, now });

  assert.equal(plan.deadlineRolled, true);
  assert.equal(plan.windowEnd.getDate(), 16);
  assert.equal(plan.feasible, true);
  assert.ok(plan.warnings.some((w) => w.includes('07:30')));
});

test('a deadline that still fits is left alone', () => {
  const settings = testSettings({ mode: 'goedkoopst', readyBy: '07:30' });
  const now = new Date('2026-01-15T03:00:00+01:00');
  const prices = priceRows(now, new Array(10).fill(0.2));
  const plan = planCharging({ timeline: buildTimeline(prices, [], settings), settings, now });

  assert.equal(plan.deadlineRolled, false);
  assert.equal(plan.windowEnd.getDate(), 15);
});

test('an explicit deadline is never moved', () => {
  const settings = testSettings({ mode: 'goedkoopst' });
  const now = new Date('2026-01-15T07:00:00+01:00');
  const deadline = new Date('2026-01-15T07:30:00+01:00');
  const prices = priceRows(now, new Array(10).fill(0.2));
  const plan = planCharging({ timeline: buildTimeline(prices, [], settings), settings, now, deadline });

  assert.equal(plan.deadlineRolled, false);
  assert.equal(plan.windowEnd.getTime(), deadline.getTime());
  assert.equal(plan.feasible, false);
});

test('the timeline stops where the price data stops', () => {
  const settings = testSettings();
  const now = new Date('2026-06-15T00:00:00+02:00');
  // Prices for three hours, but a weather forecast covering six.
  const timeline = buildTimeline(
    priceRows(now, [0.1, 0.1, 0.1]),
    solarRows(now, [0, 1, 2, 3, 4, 5]),
    settings,
  );
  assert.equal(timeline.length, 3);
  assert.ok(timeline.every((s) => s.hasPrice));
});

test('without any prices the solar hours are still shown', () => {
  const settings = testSettings();
  const now = new Date('2026-06-15T00:00:00+02:00');
  const timeline = buildTimeline([], solarRows(now, [0, 1, 2]), settings);
  assert.equal(timeline.length, 3);
});
