/**
 * The Strava/Garmin bundle: fetching it, decrypting it, and asking it
 * questions.
 *
 * The bundle is written by coach/sync/sync.py. Activities usually exist
 * twice (the watch records them, Garmin forwards them to Strava), so they are
 * matched on start time and merged: Strava brings the name, laps and
 * description, Garmin the training effect and load.
 */

import { resolveRepo } from './config.js';
import { decryptEnvelope, isEnvelope } from './crypto.js';
import { addDays, weekStart } from './format.js';

export const BUNDLE_VERSION = 1;
export const DATA_FILE = 'bundle.enc.json';

export class DataError extends Error {
  constructor(message, options) {
    super(message, options);
    this.name = 'DataError';
  }
}

/* ------------------------------------------------------------------ */
/* Fetching                                                            */
/* ------------------------------------------------------------------ */

export function dataSource(sync, location = globalThis.location) {
  const repo = resolveRepo(sync, location);
  if (!repo) return null;
  const branch = (sync?.branch || 'coach-data').trim();
  const token = (sync?.githubToken || '').trim();
  if (token) {
    // The API serves fresh content; raw.githubusercontent.com caches for minutes.
    return {
      ...repo,
      branch,
      url: `https://api.github.com/repos/${repo.owner}/${repo.repo}/contents/${DATA_FILE}?ref=${encodeURIComponent(branch)}`,
      headers: { Accept: 'application/vnd.github.raw+json', Authorization: `Bearer ${token}` },
    };
  }
  return {
    ...repo,
    branch,
    url: `https://raw.githubusercontent.com/${repo.owner}/${repo.repo}/${encodeURIComponent(branch)}/${DATA_FILE}`,
    headers: {},
  };
}

export async function fetchEnvelope(source, { fetchImpl = globalThis.fetch, signal } = {}) {
  let response;
  try {
    response = await fetchImpl(source.url, { headers: source.headers, cache: 'no-store', signal });
  } catch (err) {
    throw new DataError('Geen verbinding met GitHub.', { cause: err });
  }
  if (response.status === 404) {
    throw new DataError(
      `Nog geen data gevonden op de branch ${source.branch} van ${source.owner}/${source.repo}. ` +
        'Heeft de workflow "Coach synchroniseren" al een keer gedraaid?',
    );
  }
  if (response.status === 401 || response.status === 403) {
    throw new DataError('GitHub weigert de toegang. Klopt het GitHub-token in de instellingen?');
  }
  if (!response.ok) throw new DataError(`GitHub antwoordde met ${response.status}.`);
  const envelope = await response.json().catch(() => null);
  if (!isEnvelope(envelope)) throw new DataError('Het databestand op GitHub heeft een onbekend formaat.');
  return envelope;
}

/**
 * Returns `{ bundle, t, changed }`. Decrypting costs a key derivation, so an
 * envelope with the same timestamp as the cached copy is not decrypted again.
 */
export async function loadBundle({ sync, cached, fetchImpl, location } = {}) {
  const source = dataSource(sync, location);
  if (!source) {
    throw new DataError('Vul bij Instellingen in in welke GitHub-repository de data staat.');
  }
  const envelope = await fetchEnvelope(source, { fetchImpl });
  if (cached?.t && cached.t === envelope.t && cached.bundle) return { ...cached, changed: false };
  const bundle = await decryptEnvelope(envelope, sync.key);
  if (bundle?.version !== BUNDLE_VERSION) {
    throw new DataError('De data is gemaakt door een andere versie van de synchronisatie. Werk de app bij.');
  }
  return { bundle, t: envelope.t, changed: true };
}

/* ------------------------------------------------------------------ */
/* Activities                                                          */
/* ------------------------------------------------------------------ */

export const SPORTS = {
  run: { label: 'Hardlopen', name: 'hardlopen' },
  ride: { label: 'Fietsen', name: 'fietsen' },
  swim: { label: 'Zwemmen', name: 'zwemmen' },
  walk: { label: 'Wandelen', name: 'wandelen' },
  strength: { label: 'Kracht en fitness', name: 'kracht' },
  other: { label: 'Overig', name: 'overig' },
};

export function sportGroup(raw) {
  const s = String(raw ?? '').toLowerCase();
  if (/run/.test(s)) return 'run';
  if (/ride|cycl|bik|velomobile/.test(s)) return 'ride';
  if (/swim/.test(s)) return 'swim';
  if (/walk|hik/.test(s)) return 'walk';
  if (/weight|strength|crossfit|workout|hiit|highintensity|pilates|yoga|cardio|elliptical|stair|row/.test(s)) {
    return 'strength';
  }
  return 'other';
}

export function sportFromName(name) {
  return Object.keys(SPORTS).find((key) => SPORTS[key].name === name) ?? null;
}

const MATCH_WINDOW_MS = 3 * 60 * 1000;

function startTimes(activity) {
  const utc = Date.parse(activity?.startUtc ?? '');
  const local = Date.parse(activity?.start ?? '');
  return { utc: Number.isFinite(utc) ? utc : null, local: Number.isFinite(local) ? local : null };
}

function sameStart(a, b) {
  if (a.utc !== null && b.utc !== null) return Math.abs(a.utc - b.utc) <= MATCH_WINDOW_MS;
  if (a.local !== null && b.local !== null) return Math.abs(a.local - b.local) <= MATCH_WINDOW_MS;
  return false;
}

function positive(value) {
  return Number.isFinite(value) && value > 0 ? value : undefined;
}

function unify(s, g) {
  const start = s?.start ?? g?.start ?? '';
  const out = {
    id: s ? `strava:${s.id}` : `garmin:${g.id}`,
    sources: [s && 'strava', g && 'garmin'].filter(Boolean),
    name: s?.name ?? g?.name ?? 'Activiteit',
    sport: sportGroup(s?.sport ?? g?.type),
    sportType: s?.sport ?? g?.type,
    start,
    date: start.slice(0, 10),
    distanceM: positive(s?.distanceM) ?? positive(g?.distanceM),
    movingS: positive(s?.movingS) ?? positive(g?.movingS) ?? positive(g?.durationS),
    elapsedS: positive(s?.elapsedS) ?? positive(g?.durationS),
    elevationM: positive(s?.elevationM) ?? positive(g?.elevationM),
    avgSpeed: positive(s?.avgSpeed) ?? positive(g?.avgSpeed),
    avgHr: s?.avgHr ?? g?.avgHr,
    maxHr: s?.maxHr ?? g?.maxHr,
    avgWatts: s?.avgWatts ?? g?.avgPower,
    weightedWatts: s?.weightedWatts ?? g?.normPower,
    calories: s?.calories ?? g?.calories,
    cadence: s?.avgCadence ?? g?.cadence,
    trainingLoad: g?.trainingLoad,
    aerobicTE: g?.aerobicTE,
    anaerobicTE: g?.anaerobicTE,
    teLabel: g?.teLabel,
    vo2max: g?.vo2max,
    sets: g?.sets,
    reps: g?.reps,
    sufferScore: s?.sufferScore,
    prCount: s?.prCount,
    workoutType: s?.workoutType,
    trainer: s?.trainer,
    commute: s?.commute,
    description: s?.description,
    device: s?.device,
    laps: s?.laps,
    splits: s?.splits,
    bestEfforts: s?.bestEfforts,
  };
  for (const key of Object.keys(out)) if (out[key] === undefined || out[key] === null) delete out[key];
  return out;
}

/** All activities, newest first, each sport session once. */
export function mergeActivities(bundle) {
  const strava = bundle?.strava?.activities ?? [];
  const garmin = (bundle?.garmin?.activities ?? []).map((g) => ({ g, times: startTimes(g) }));
  const used = new Set();
  const merged = strava.map((s) => {
    const times = startTimes(s);
    const match = garmin.find((entry) => !used.has(entry) && sameStart(times, entry.times));
    if (match) used.add(match);
    return unify(s, match?.g);
  });
  for (const entry of garmin) if (!used.has(entry)) merged.push(unify(null, entry.g));
  return merged.sort((a, b) => b.start.localeCompare(a.start));
}

export function activitiesInRange(activities, from, to, sport = null) {
  return activities.filter((a) => a.date >= from && a.date <= to && (!sport || a.sport === sport));
}

/* ------------------------------------------------------------------ */
/* Wellness (Garmin days)                                              */
/* ------------------------------------------------------------------ */

export function wellnessDays(bundle) {
  return [...(bundle?.garmin?.days ?? [])].sort((a, b) => a.date.localeCompare(b.date));
}

export function wellnessInRange(bundle, from, to) {
  return wellnessDays(bundle).filter((d) => d.date >= from && d.date <= to);
}

export function wellnessOn(bundle, day) {
  return wellnessDays(bundle).find((d) => d.date === day) ?? null;
}

export function average(values) {
  const list = values.filter((v) => Number.isFinite(v));
  return list.length ? list.reduce((sum, v) => sum + v, 0) / list.length : null;
}

export function wellnessSummary(days) {
  return {
    days: days.length,
    sleepS: average(days.map((d) => d.sleep?.totalS)),
    sleepScore: average(days.map((d) => d.sleep?.score)),
    rhr: average(days.map((d) => d.rhr)),
    hrv: average(days.map((d) => d.hrv?.lastNight)),
    steps: average(days.map((d) => d.steps)),
    stress: average(days.map((d) => d.stressAvg)),
    bbHigh: average(days.map((d) => d.bbHigh)),
    intensityMinutes: days.reduce((sum, d) => sum + (d.intensityModerate ?? 0) + 2 * (d.intensityVigorous ?? 0), 0),
  };
}

/** Most recent weight, from Garmin or from the profile. */
export function latestWeight(bundle) {
  const days = wellnessDays(bundle).filter((d) => Number.isFinite(d.weightKg));
  const last = days.at(-1);
  return last ? { kg: last.weightKg, date: last.date, bodyFatPct: last.bodyFatPct } : null;
}

/* ------------------------------------------------------------------ */
/* Weekly training                                                     */
/* ------------------------------------------------------------------ */

function emptyTotals() {
  return { count: 0, movingS: 0, distanceM: 0, elevationM: 0, load: 0 };
}

function addTo(totals, activity) {
  totals.count += 1;
  totals.movingS += activity.movingS ?? 0;
  totals.distanceM += activity.distanceM ?? 0;
  totals.elevationM += activity.elevationM ?? 0;
  totals.load += activity.trainingLoad ?? 0;
}

/** One entry per week (Monday first), newest week first. */
export function weeklySummary(activities, { weeks = 8, today }) {
  const current = weekStart(today);
  const result = [];
  for (let i = 0; i < weeks; i += 1) {
    const monday = addDays(current, -7 * i);
    const sunday = addDays(monday, 6);
    const total = emptyTotals();
    const bySport = {};
    for (const activity of activitiesInRange(activities, monday, sunday)) {
      addTo(total, activity);
      addTo((bySport[activity.sport] ??= emptyTotals()), activity);
    }
    result.push({ week: monday, ...total, bySport });
  }
  return result;
}
