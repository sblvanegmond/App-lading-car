/**
 * The charge planner.
 *
 * It takes an hourly timeline (market price + expected solar surplus per
 * hour), the car and tariff settings, and works out which hours to charge in
 * so the car reaches its target state of charge before the deadline at the
 * lowest real cost.
 *
 * Everything in this module is pure: same input, same output. That keeps it
 * testable without a browser, an API key or a network connection.
 */

import { allInPrice, solarOpportunityCost } from './pricing.js';
import { surplusKw } from './solar.js';

const HOUR_MS = 3600000;

export const MODES = {
  goedkoopst: {
    id: 'goedkoopst',
    label: 'Goedkoopst',
    hint: 'Kijkt alleen naar de stroomprijs en laadt op vol vermogen.',
  },
  balans: {
    id: 'balans',
    label: 'Balans',
    hint: 'Weegt prijs en eigen zonopbrengst tegen elkaar af. Aanrader.',
  },
  zon: {
    id: 'zon',
    label: 'Max zon',
    hint: 'Laadt zoveel mogelijk op je eigen overschot, vult zo nodig aan.',
  },
};

/**
 * Merge prices and solar forecast into one hourly timeline.
 *
 * @param {Array<{start: Date|string, marketPrice: number}>} priceRows
 * @param {Array<{start: Date|string, pvKw: number, tempC?: number, cloudCoverPct?: number, sunshineMinutes?: number}>} solarRows
 * @param {object} settings
 * @returns {Array<object>} hourly slots sorted by time
 */
export function buildTimeline(priceRows = [], solarRows = [], settings) {
  const solarByKey = new Map();
  for (const row of solarRows) {
    solarByKey.set(keyOf(row.start), row);
  }
  const priceByKey = new Map();
  for (const row of priceRows) {
    priceByKey.set(keyOf(row.start), row);
  }

  const keys = new Set([...priceByKey.keys(), ...solarByKey.keys()]);
  const slots = [];
  for (const key of keys) {
    const price = priceByKey.get(key);
    const solar = solarByKey.get(key);
    // Without a price we cannot judge the grid part of the hour, so such an
    // hour can only be used by the solar-only mode.
    const start = new Date(key);
    const marketPrice = price ? Number(price.marketPrice) : null;
    const pvKw = solar ? Math.max(0, Number(solar.pvKw) || 0) : 0;
    const allIn = marketPrice === null ? null : allInPrice(marketPrice, settings.tariffs);
    slots.push({
      start,
      end: new Date(start.getTime() + HOUR_MS),
      marketPrice,
      allInPrice: allIn,
      hasPrice: marketPrice !== null && Number.isFinite(marketPrice),
      pvKw,
      surplusKw: surplusKw(pvKw, settings.houseBaseLoadKw),
      tempC: solar?.tempC ?? null,
      cloudCoverPct: solar?.cloudCoverPct ?? null,
      sunshineMinutes: solar?.sunshineMinutes ?? null,
    });
  }
  slots.sort((a, b) => a.start - b.start);

  // The weather forecast reaches further than the price data. Hours beyond
  // the last known price cannot be judged or planned, so they are dropped
  // rather than shown as empty rows.
  const lastPriced = [...slots].reverse().find((s) => s.hasPrice);
  if (lastPriced) {
    return slots.filter((s) => s.start <= lastPriced.start);
  }
  return slots;
}

function keyOf(value) {
  const date = value instanceof Date ? value : new Date(value);
  // Snap to the top of the hour so both sources line up.
  date.setUTCMinutes(0, 0, 0);
  return date.toISOString();
}

/**
 * Energy that has to go into the battery, corrected for charging losses.
 * @returns {{netKwh: number, grossKwh: number, socGapPct: number}}
 */
export function energyNeed(car) {
  const capacity = Math.max(0, Number(car?.batteryKwh) || 0);
  const current = clampPct(car?.currentSocPct);
  const target = clampPct(car?.targetSocPct);
  const socGapPct = Math.max(0, target - current);
  const netKwh = (capacity * socGapPct) / 100;
  const loss = Math.min(40, Math.max(0, Number(car?.chargeLossPct) || 0));
  const grossKwh = loss >= 100 ? netKwh : netKwh / (1 - loss / 100);
  return { netKwh, grossKwh, socGapPct };
}

function clampPct(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return 0;
  return Math.min(100, Math.max(0, n));
}

/**
 * The next moment the clock hits `hhmm`, at or after `from`.
 * Uses the local timezone of the device, which is what the driver means.
 */
export function nextTimeOccurrence(from, hhmm) {
  const [h, m] = String(hhmm ?? '07:00').split(':').map((n) => parseInt(n, 10));
  const hours = Number.isFinite(h) ? h : 7;
  const minutes = Number.isFinite(m) ? m : 0;
  const candidate = new Date(from);
  candidate.setHours(hours, minutes, 0, 0);
  if (candidate <= from) candidate.setDate(candidate.getDate() + 1);
  return candidate;
}

/**
 * Score one hour: how much it costs to charge in it, per kWh.
 *
 * @returns {object} slot enriched with what a full hour of charging would do
 */
function evaluateSlot(slot, settings, windowStart, windowEnd, mode) {
  const from = new Date(Math.max(slot.start.getTime(), windowStart.getTime()));
  const to = new Date(Math.min(slot.end.getTime(), windowEnd.getTime()));
  const hours = Math.max(0, (to - from) / HOUR_MS);
  if (hours <= 0) return null;

  const chargerKw = Math.max(0, Number(settings.car.chargerKw) || 0);
  const minChargeKw = Math.max(0, Number(settings.car.minChargeKw) || 0);
  const solarKwAvailable = slot.surplusKw;

  let powerKw = chargerKw;
  let solarOnly = false;
  if (mode === 'zon') {
    powerKw = Math.min(chargerKw, solarKwAvailable);
    solarOnly = true;
    if (powerKw < minChargeKw) return null; // car will not accept this trickle
  }
  if (powerKw <= 0) return null;
  if (!slot.hasPrice && !solarOnly) return null;

  const maxKwh = powerKw * hours;
  const solarKwh = Math.min(solarKwAvailable * hours, maxKwh);
  const gridKwh = Math.max(0, maxKwh - solarKwh);

  const allIn = slot.hasPrice ? slot.allInPrice : null;
  const solarValue = solarOpportunityCost(allIn ?? 0, settings.tariffs);
  // A grid kWh in an hour without a known price cannot be costed; in solar
  // mode such an hour is only ever used for its solar part.
  const gridCost = allIn === null ? 0 : gridKwh * allIn;
  const cost = gridCost + solarKwh * solarValue;
  const effectivePrice = maxKwh > 0 ? cost / maxKwh : Infinity;

  let score;
  if (mode === 'goedkoopst') score = allIn ?? Infinity;
  else score = effectivePrice;

  return {
    ...slot,
    from,
    to,
    hours,
    powerKw,
    maxKwh,
    solarKwh,
    gridKwh,
    cost,
    effectivePrice,
    score,
    solarShare: maxKwh > 0 ? solarKwh / maxKwh : 0,
  };
}

/** Take a fraction of an evaluated slot, scaling energy and cost with it. */
function scaleSlot(slot, kwh) {
  const factor = slot.maxKwh > 0 ? Math.min(1, kwh / slot.maxKwh) : 0;
  return {
    ...slot,
    hours: slot.hours * factor,
    kwh: slot.maxKwh * factor,
    solarKwh: slot.solarKwh * factor,
    gridKwh: slot.gridKwh * factor,
    cost: slot.cost * factor,
    partial: factor < 0.999,
    to: new Date(slot.from.getTime() + slot.hours * factor * HOUR_MS),
  };
}

function bySelectionOrder(a, b) {
  if (a.score !== b.score) return a.score - b.score;
  // Same price: grab the hour with the most sun, then the earliest one, so
  // the car is full early rather than at the very last minute.
  if (b.solarKwh !== a.solarKwh) return b.solarKwh - a.solarKwh;
  return a.start - b.start;
}

/**
 * Plan the charging session.
 *
 * @param {object} args
 * @param {Array} args.timeline output of buildTimeline()
 * @param {object} args.settings
 * @param {Date} [args.now]
 * @param {Date} [args.deadline] overrides settings.readyBy
 * @returns {object} plan
 */
export function planCharging({ timeline, settings, now = new Date(), deadline }) {
  const mode = MODES[settings.mode] ? settings.mode : 'balans';
  const windowStart = now;
  const need = energyNeed(settings.car);

  // If you open the app shortly before the car is due, today's deadline can
  // no longer deliver the charge at all. Planning a ten-minute sliver helps
  // nobody, so the plan moves to the next day's deadline instead.
  let windowEnd = deadline ?? nextTimeOccurrence(now, settings.readyBy);
  let deadlineRolled = false;
  if (!deadline && need.grossKwh > 0) {
    const chargerKw = Math.max(0.1, Number(settings.car.chargerKw) || 0.1);
    const hoursNeeded = need.grossKwh / chargerKw;
    const hoursAvailable = (windowEnd - now) / HOUR_MS;
    if (hoursAvailable < hoursNeeded) {
      windowEnd = new Date(windowEnd.getTime() + 24 * HOUR_MS);
      deadlineRolled = true;
    }
  }
  const plan = {
    mode,
    generatedAt: new Date(now),
    windowStart,
    windowEnd,
    deadlineRolled,
    need,
    slots: [],
    blocks: [],
    totals: {
      kwh: 0,
      solarKwh: 0,
      gridKwh: 0,
      cost: 0,
      avgPricePerKwh: 0,
      solarSharePct: 0,
    },
    comparison: null,
    feasible: true,
    shortfallKwh: 0,
    reachableSocPct: settings.car.targetSocPct,
    warnings: [],
  };

  if (windowEnd <= windowStart) {
    plan.warnings.push('De deadline ligt in het verleden. Controleer "klaar om".');
    plan.feasible = false;
    return plan;
  }
  if (need.grossKwh <= 0) {
    plan.warnings.push('De accu zit al op het gewenste niveau. Laden is niet nodig.');
    return plan;
  }
  if (deadlineRolled) {
    plan.warnings.push(
      `Vandaag om ${settings.readyBy} red je het niet meer. Dit plan is voor ${formatClock(windowEnd)} de dag erna.`,
    );
  }

  const candidates = [];
  for (const slot of timeline) {
    const evaluated = evaluateSlot(slot, settings, windowStart, windowEnd, mode);
    if (evaluated) candidates.push(evaluated);
  }

  if (candidates.length === 0) {
    plan.feasible = false;
    plan.shortfallKwh = need.grossKwh;
    plan.reachableSocPct = settings.car.currentSocPct;
    plan.warnings.push(
      mode === 'zon'
        ? 'Er is voor de deadline geen bruikbaar zonoverschot. Kies "Balans" of laad bij uit het net.'
        : 'Geen bruikbare uren gevonden voor de deadline.',
    );
    return plan;
  }

  const chosen = [];
  let remaining = need.grossKwh;
  const ordered = [...candidates].sort(bySelectionOrder);
  for (const slot of ordered) {
    if (remaining <= 1e-6) break;
    const take = Math.min(slot.maxKwh, remaining);
    chosen.push({ ...scaleSlot(slot, take), source: mode === 'zon' ? 'zon' : 'mix' });
    remaining -= take;
  }

  // Solar-only mode could not fill the battery: top up from the grid in the
  // cheapest remaining hours rather than leaving the driver stranded.
  if (remaining > 1e-6 && mode === 'zon') {
    const used = new Set(chosen.map((s) => s.start.toISOString()));
    const fallback = [];
    for (const slot of timeline) {
      const evaluated = evaluateSlot(slot, settings, windowStart, windowEnd, 'balans');
      if (!evaluated) continue;
      const key = evaluated.start.toISOString();
      const alreadyUsed = used.has(key);
      const headroom = alreadyUsed
        ? evaluated.maxKwh - (chosen.find((s) => s.start.toISOString() === key)?.kwh ?? 0)
        : evaluated.maxKwh;
      if (headroom > 1e-6) fallback.push({ evaluated, headroom, alreadyUsed });
    }
    fallback.sort((a, b) => a.evaluated.score - b.evaluated.score || a.evaluated.start - b.evaluated.start);
    for (const item of fallback) {
      if (remaining <= 1e-6) break;
      const take = Math.min(item.headroom, remaining);
      const key = item.evaluated.start.toISOString();
      const existing = chosen.find((s) => s.start.toISOString() === key);
      if (existing) {
        // This hour already runs on the full solar surplus, so everything we
        // add on top of it is grid energy. Counting the sun twice here would
        // make the plan look cheaper and greener than it is.
        const unitPrice = item.evaluated.hasPrice ? item.evaluated.allInPrice : 0;
        existing.kwh += take;
        existing.gridKwh += take;
        existing.cost += take * unitPrice;
        existing.hours = Math.min(
          item.evaluated.hours,
          Math.max(existing.hours, existing.kwh / Math.max(1e-6, item.evaluated.powerKw)),
        );
        existing.powerKw = existing.hours > 0 ? existing.kwh / existing.hours : existing.powerKw;
        existing.partial = existing.kwh < item.evaluated.maxKwh - 1e-6;
        existing.source = 'mix';
        existing.to = new Date(existing.from.getTime() + existing.hours * HOUR_MS);
      } else {
        chosen.push({ ...scaleSlot(item.evaluated, take), source: 'net' });
      }
      remaining -= take;
    }
    if (chosen.some((s) => s.source !== 'zon')) {
      plan.warnings.push('Te weinig zon voor je doel: de app vult aan in de goedkoopste uren.');
    }
  }

  if (remaining > 1e-6) {
    plan.feasible = false;
    plan.shortfallKwh = remaining;
    const delivered = need.grossKwh - remaining;
    const lossFactor = 1 - Math.min(40, Math.max(0, settings.car.chargeLossPct || 0)) / 100;
    const capacity = Math.max(1e-6, Number(settings.car.batteryKwh) || 0);
    plan.reachableSocPct = Math.min(
      100,
      settings.car.currentSocPct + ((delivered * lossFactor) / capacity) * 100,
    );
    plan.warnings.push(
      `Je haalt ${settings.car.targetSocPct}% niet voor ${formatClock(windowEnd)}. ` +
        `Haalbaar is ongeveer ${Math.round(plan.reachableSocPct)}%.`,
    );
  }

  chosen.sort((a, b) => a.from - b.from);
  plan.slots = chosen;
  plan.blocks = mergeBlocks(chosen);
  plan.totals = totalsOf(chosen);
  plan.comparison = buildComparison({ candidates, need, plan });
  return plan;
}

/** Glue consecutive charging hours into human-readable windows. */
export function mergeBlocks(slots) {
  const blocks = [];
  for (const slot of slots) {
    const last = blocks[blocks.length - 1];
    if (last && Math.abs(last.to.getTime() - slot.from.getTime()) < 60000) {
      last.to = slot.to;
      last.kwh += slot.kwh;
      last.solarKwh += slot.solarKwh;
      last.gridKwh += slot.gridKwh;
      last.cost += slot.cost;
      last.slots.push(slot);
    } else {
      blocks.push({
        from: slot.from,
        to: slot.to,
        kwh: slot.kwh,
        solarKwh: slot.solarKwh,
        gridKwh: slot.gridKwh,
        cost: slot.cost,
        slots: [slot],
      });
    }
  }
  for (const block of blocks) {
    block.avgPricePerKwh = block.kwh > 0 ? block.cost / block.kwh : 0;
    block.solarSharePct = block.kwh > 0 ? (block.solarKwh / block.kwh) * 100 : 0;
    block.powerKw = block.kwh > 0 ? block.kwh / Math.max(1e-6, (block.to - block.from) / HOUR_MS) : 0;
  }
  return blocks;
}

function totalsOf(slots) {
  const totals = slots.reduce(
    (acc, s) => {
      acc.kwh += s.kwh;
      acc.solarKwh += s.solarKwh;
      acc.gridKwh += s.gridKwh;
      acc.cost += s.cost;
      return acc;
    },
    { kwh: 0, solarKwh: 0, gridKwh: 0, cost: 0 },
  );
  totals.avgPricePerKwh = totals.kwh > 0 ? totals.cost / totals.kwh : 0;
  totals.solarSharePct = totals.kwh > 0 ? (totals.solarKwh / totals.kwh) * 100 : 0;
  return totals;
}

/**
 * What the same charge would have cost if you had simply plugged in and
 * started right away, and what the average hour in the window costs.
 */
function buildComparison({ candidates, need, plan }) {
  const chronological = [...candidates].sort((a, b) => a.from - b.from);
  let remaining = need.grossKwh;
  let immediateCost = 0;
  let immediateKwh = 0;
  for (const slot of chronological) {
    if (remaining <= 1e-6) break;
    // "Right now" means: plug in and take the earliest hours, whatever they cost.
    const take = Math.min(slot.maxKwh, remaining);
    const scaled = scaleSlot(slot, take);
    immediateCost += scaled.cost;
    immediateKwh += scaled.kwh;
    remaining -= take;
  }

  const priced = candidates.filter((s) => s.hasPrice);
  const avgAllIn = priced.length
    ? priced.reduce((sum, s) => sum + s.allInPrice, 0) / priced.length
    : 0;
  const cheapest = priced.reduce((best, s) => (!best || s.allInPrice < best.allInPrice ? s : best), null);
  const dearest = priced.reduce((best, s) => (!best || s.allInPrice > best.allInPrice ? s : best), null);

  const planCost = plan.totals.cost;
  return {
    immediateCost,
    immediateKwh,
    savingsVsImmediate: immediateCost - planCost,
    avgAllInPrice: avgAllIn,
    avgWindowCost: avgAllIn * need.grossKwh,
    savingsVsAverage: avgAllIn * need.grossKwh - planCost,
    cheapestHour: cheapest ? { start: cheapest.start, price: cheapest.allInPrice } : null,
    dearestHour: dearest ? { start: dearest.start, price: dearest.allInPrice } : null,
  };
}

function formatClock(date) {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

/** Expected solar production per calendar day, for the forecast cards. */
export function solarSummary(timeline) {
  const byDay = new Map();
  for (const slot of timeline) {
    const key = slot.start.toDateString();
    const entry = byDay.get(key) ?? {
      date: new Date(slot.start),
      pvKwh: 0,
      surplusKwh: 0,
      sunshineMinutes: 0,
      peakKw: 0,
      peakAt: null,
    };
    entry.pvKwh += slot.pvKw;
    entry.surplusKwh += slot.surplusKw;
    if (Number.isFinite(slot.sunshineMinutes)) entry.sunshineMinutes += slot.sunshineMinutes;
    if (slot.pvKw > entry.peakKw) {
      entry.peakKw = slot.pvKw;
      entry.peakAt = new Date(slot.start);
    }
    byDay.set(key, entry);
  }
  return [...byDay.values()].sort((a, b) => a.date - b.date);
}
