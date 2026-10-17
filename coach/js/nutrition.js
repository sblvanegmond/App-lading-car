/**
 * The optional food and drink log.
 *
 * Every number is optional: "boterham met kaas" without calories is a valid
 * entry, and the coach can still talk about it. Amounts are per entry, not
 * per 100 g.
 */

import { isDay, localDate, localTime } from './format.js';

export const KINDS = {
  ontbijt: 'Ontbijt',
  lunch: 'Lunch',
  diner: 'Diner',
  snack: 'Tussendoor',
  drinken: 'Drinken',
};

/** Numeric fields of an entry with their sensible upper bounds. */
export const FIELDS = {
  kcal: { label: 'kcal', max: 5000 },
  proteinG: { label: 'eiwit (g)', max: 400 },
  carbsG: { label: 'koolhydraten (g)', max: 800 },
  fatG: { label: 'vet (g)', max: 400 },
  fluidMl: { label: 'vocht (ml)', max: 5000 },
  alcoholUnits: { label: 'alcohol (glazen)', max: 30 },
  caffeineMg: { label: 'cafeïne (mg)', max: 2000 },
};

export const QUICK_ADDS = [
  { label: 'Glas water', kind: 'drinken', description: 'Glas water', fluidMl: 250 },
  { label: 'Fles water', kind: 'drinken', description: 'Fles water', fluidMl: 500 },
  { label: 'Koffie', kind: 'drinken', description: 'Kop koffie', fluidMl: 150, caffeineMg: 80 },
  { label: 'Thee', kind: 'drinken', description: 'Kop thee', fluidMl: 250, caffeineMg: 30 },
  { label: 'Bier', kind: 'drinken', description: 'Glas bier', fluidMl: 250, alcoholUnits: 1, kcal: 105 },
  { label: 'Wijn', kind: 'drinken', description: 'Glas wijn', fluidMl: 100, alcoholUnits: 1, kcal: 80 },
];

function cleanNumber(value, max) {
  if (value === '' || value === null || value === undefined) return undefined;
  const x = typeof value === 'number' ? value : Number(String(value).replace(',', '.'));
  if (!Number.isFinite(x) || x < 0) return undefined;
  return Math.round(Math.min(x, max) * 10) / 10;
}

function newId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function defaultKind(time) {
  const hour = Number(String(time).slice(0, 2));
  if (hour >= 5 && hour < 10) return 'ontbijt';
  if (hour >= 11 && hour < 14) return 'lunch';
  if (hour >= 17 && hour < 21) return 'diner';
  return 'snack';
}

/** Validates and normalises raw input (form, AI estimate or coach tool). */
export function createEntry(input, { now = new Date(), source = 'hand' } = {}) {
  const description = String(input?.description ?? '').trim().slice(0, 300);
  if (!description) throw new Error('Beschrijf wat je at of dronk.');
  const date = isDay(input?.date) ? input.date : localDate(now);
  const time = /^\d{2}:\d{2}$/.test(String(input?.time ?? '')) ? input.time : localTime(now);
  const kind = KINDS[input?.kind] ? input.kind : defaultKind(time);

  const entry = { id: input?.id ?? newId(), date, time, kind, description, source };
  for (const [key, { max }] of Object.entries(FIELDS)) {
    const value = cleanNumber(input?.[key], max);
    if (value !== undefined) entry[key] = value;
  }
  if (input?.estimate) entry.estimate = input.estimate;
  return entry;
}

export function sortEntries(entries) {
  return [...entries].sort((a, b) => `${a.date} ${a.time}`.localeCompare(`${b.date} ${b.time}`));
}

export function entriesOn(entries, day) {
  return sortEntries(entries.filter((e) => e.date === day));
}

export function totals(entries) {
  const sum = { count: entries.length };
  for (const key of Object.keys(FIELDS)) {
    const values = entries.map((e) => e[key]).filter((v) => Number.isFinite(v));
    sum[key] = values.length ? Math.round(values.reduce((a, b) => a + b, 0) * 10) / 10 : 0;
  }
  // Only meaningful when everything eaten that day carries a calorie count.
  const food = entries.filter((e) => e.kind !== 'drinken');
  sum.kcalComplete = food.length > 0 && food.every((e) => Number.isFinite(e.kcal));
  return sum;
}

export function totalsByDay(entries, from, to) {
  const days = {};
  for (const entry of entries) {
    if (entry.date < from || entry.date > to) continue;
    (days[entry.date] ??= []).push(entry);
  }
  return Object.fromEntries(
    Object.keys(days)
      .sort()
      .map((day) => [day, totals(days[day])]),
  );
}
