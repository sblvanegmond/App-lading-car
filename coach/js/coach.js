/**
 * The AI layer: what Claude is told, which tools it gets, and the turn loop.
 *
 * Everything here works on plain data and an injected client, so the loop is
 * tested without a network (tests/coach-chat.test.js). claude.js supplies the
 * real SDK client.
 *
 * Three rules keep prompt caching and Claude's reasoning intact across a
 * conversation:
 *  - the system prompt and the tool list are fixed when a conversation starts;
 *  - messages are only ever appended, assistant turns exactly as returned
 *    (thinking blocks included);
 *  - the current date and time travel in each user message, not the system
 *    prompt.
 */

import { ageFrom } from './config.js';
import {
  SPORTS,
  activitiesInRange,
  latestWeight,
  mergeActivities,
  sportFromName,
  weeklySummary,
  wellnessInRange,
  wellnessOn,
  wellnessSummary,
} from './data.js';
import {
  addDays,
  daysBetween,
  formatClock,
  formatDay,
  formatDistance,
  formatHours,
  formatLitres,
  formatMoment,
  formatNumber,
  formatPace,
  formatSpeed,
  formatStamp,
  isDay,
  localDate,
} from './format.js';
import { KINDS, createEntry, entriesOn, totals, totalsByDay } from './nutrition.js';

export const MAX_TOKENS = 64000;
export const MAX_TOOL_STEPS = 12;

/* ------------------------------------------------------------------ */
/* Request options per model                                           */
/* ------------------------------------------------------------------ */

/**
 * Optional API features, per model:
 *  - fallbacks: a request declined by a safety classifier is retried on the
 *    model Anthropic recommends for that case, instead of failing.
 *  - updates: the short notes between tool calls come back readable
 *    ("ik kijk naar je slaap van deze week") while the reasoning stays hidden.
 *  - binding: should an earlier turn ever not match byte for byte, the API
 *    drops the stale reasoning instead of rejecting the request.
 */
const MODEL_FEATURES = {
  'claude-opus-5-5': { fallbacks: true, updates: true, binding: true },
  'claude-sonnet-5-5': { fallbacks: true, updates: true, binding: true },
};

export function requestOptions(model, { effort = 'medium', updates = true, compat = false } = {}) {
  const features = compat ? {} : (MODEL_FEATURES[model] ?? {});
  const betas = [];
  const thinking = { type: 'adaptive' };
  const options = {};
  if (features.fallbacks) {
    betas.push('server-side-fallback-2026-07-01');
    options.fallbacks = 'default';
  }
  if (features.updates && updates) {
    betas.push('thinking-display-updates-2026-08-18');
    thinking.display = 'updates';
  }
  if (features.binding) {
    betas.push('thinking-binding-controls-2026-08-01');
    thinking.block_binding = { prefix_mismatch_behavior: 'drop_block' };
  }
  if (betas.length) options.betas = betas;
  return { ...options, thinking, output_config: { effort } };
}

export function buildRequest({ model, system, compat = false }) {
  return {
    model,
    max_tokens: MAX_TOKENS,
    system,
    tools: TOOLS,
    // Caches the conversation so far; each turn only pays for what is new.
    cache_control: { type: 'ephemeral' },
    ...requestOptions(model, { compat }),
  };
}

/* ------------------------------------------------------------------ */
/* Tools                                                               */
/* ------------------------------------------------------------------ */

const DAY = { type: 'string', description: 'Datum als JJJJ-MM-DD' };
const SPORT_NAMES = Object.values(SPORTS).map((s) => s.name);

const TOOL_DEFINITIONS = [
  {
    name: 'get_activities',
    description:
      'Trainingen en andere activiteiten uit Strava en Garmin in een periode, samengevoegd en nieuwste eerst. ' +
      'Per activiteit: id, datum en starttijd, naam, sport, afstand, bewegingstijd, tempo of snelheid, hartslag, ' +
      'vermogen, hoogtemeters, kcal, en van Garmin het trainingseffect en de trainingsbelasting.',
    input_schema: {
      type: 'object',
      properties: {
        from: DAY,
        to: DAY,
        sport: { type: 'string', enum: SPORT_NAMES, description: 'Alleen deze sport (optioneel)' },
        limit: { type: 'integer', description: 'Maximaal aantal activiteiten, standaard 50, hoogstens 200' },
      },
      required: ['from', 'to'],
      additionalProperties: false,
    },
  },
  {
    name: 'get_activity_details',
    description:
      'Alle details van één activiteit: ronden, kilometersplits, beste prestaties, beschrijving en apparaat. ' +
      'Gebruik het id uit get_activities.',
    input_schema: {
      type: 'object',
      properties: { id: { type: 'string', description: 'Id zoals "strava:123" of "garmin:456"' } },
      required: ['id'],
      additionalProperties: false,
    },
  },
  {
    name: 'get_wellness',
    description:
      'Horlogegegevens uit Garmin per dag: slaap (duur, fasen, score, tijden), HRV (afgelopen nacht, ' +
      'weekgemiddelde, status, normaalbereik), rusthartslag, Body Battery, stress, stappen, ' +
      'intensiteitsminuten, trainingsgereedheid en gewicht. Slaap hoort bij de dag waarop de gebruiker ' +
      'wakker werd. Hoogstens 92 dagen per keer.',
    input_schema: {
      type: 'object',
      properties: { from: DAY, to: DAY },
      required: ['from', 'to'],
      additionalProperties: false,
    },
  },
  {
    name: 'get_training_summary',
    description:
      'Training per week (maandag tot en met zondag): aantal, tijd, afstand, hoogtemeters en ' +
      'Garmin-trainingsbelasting, totaal en per sport. Plus de Garmin-status (VO2max, trainingsstatus, ' +
      'acute en chronische belasting) en de Strava-totalen van dit jaar.',
    input_schema: {
      type: 'object',
      properties: { weeks: { type: 'integer', description: 'Aantal weken terug, standaard 8, hoogstens 52' } },
      additionalProperties: false,
    },
  },
  {
    name: 'get_nutrition_log',
    description:
      'Het voedings- en drinklogboek dat de gebruiker zelf bijhoudt, met dagtotalen en streefwaarden. ' +
      'Waarden kunnen ontbreken, en niet alles wordt gelogd. Hoogstens 31 dagen per keer.',
    input_schema: {
      type: 'object',
      properties: { from: DAY, to: DAY },
      required: ['from', 'to'],
      additionalProperties: false,
    },
  },
  {
    name: 'log_intake',
    description:
      'Zet iets in het voedings- en drinklogboek. Alleen gebruiken als de gebruiker vraagt om iets vast te ' +
      'leggen. Schat ontbrekende voedingswaarden redelijk in voor gangbare Nederlandse porties en zeg in je ' +
      'antwoord dat het een schatting is.',
    input_schema: {
      type: 'object',
      properties: {
        description: { type: 'string', description: 'Wat er gegeten of gedronken is, met hoeveelheid' },
        kind: { type: 'string', enum: Object.keys(KINDS) },
        date: { ...DAY, description: 'Standaard vandaag' },
        time: { type: 'string', description: 'Tijd als UU:MM, standaard nu' },
        kcal: { type: 'number' },
        protein_g: { type: 'number' },
        carbs_g: { type: 'number' },
        fat_g: { type: 'number' },
        fluid_ml: { type: 'number' },
        alcohol_units: { type: 'number', description: 'Standaardglazen alcohol' },
        caffeine_mg: { type: 'number' },
      },
      required: ['description'],
      additionalProperties: false,
    },
  },
  {
    name: 'remember',
    description:
      'Onthoud een blijvend feit over de gebruiker voor latere gesprekken: een doel, blessure, voorkeur, ' +
      'weekschema of dieetwens. Kort en feitelijk, in de derde persoon. Niet voor tijdelijke dingen.',
    input_schema: {
      type: 'object',
      properties: { note: { type: 'string' } },
      required: ['note'],
      additionalProperties: false,
    },
  },
];

/** Inputs arrive unvalidated (eager streaming), so validate() below checks them. */
export const TOOLS = TOOL_DEFINITIONS.map((tool) => ({ ...tool, eager_input_streaming: true }));

export const TOOL_LABELS = {
  get_activities: 'Activiteiten bekeken',
  get_activity_details: 'Activiteit in detail bekeken',
  get_wellness: 'Slaap en herstel bekeken',
  get_training_summary: 'Trainingsweken bekeken',
  get_nutrition_log: 'Voedingslog bekeken',
  log_intake: 'In het voedingslog gezet',
  remember: 'Onthouden',
};

function typeMatches(schema, value) {
  switch (schema.type) {
    case 'string':
      return typeof value === 'string' && (!schema.enum || schema.enum.includes(value));
    case 'integer':
      return Number.isInteger(value);
    case 'number':
      return typeof value === 'number' && Number.isFinite(value);
    default:
      return true;
  }
}

/** Checks a tool input against its schema: object, required keys, types, enums. */
export function validateInput(name, input) {
  const tool = TOOL_DEFINITIONS.find((t) => t.name === name);
  if (!tool) return { ok: false, error: `Onbekende tool ${name}.` };
  if (!input || typeof input !== 'object' || Array.isArray(input)) return { ok: false, error: 'Invoer is geen object.' };
  const { properties, required = [] } = tool.input_schema;
  for (const key of required) {
    if (input[key] === undefined || input[key] === '') return { ok: false, error: `Veld ${key} ontbreekt.` };
  }
  for (const [key, value] of Object.entries(input)) {
    if (!properties[key]) return { ok: false, error: `Onbekend veld ${key}.` };
    if (!typeMatches(properties[key], value)) return { ok: false, error: `Veld ${key} heeft een ongeldige waarde.` };
  }
  for (const key of ['from', 'to', 'date']) {
    if (input[key] !== undefined && !isDay(input[key])) return { ok: false, error: `${key} moet een datum JJJJ-MM-DD zijn.` };
  }
  return { ok: true, value: input };
}

function round(value, digits = 0) {
  if (!Number.isFinite(value)) return undefined;
  const f = 10 ** digits;
  return Math.round(value * f) / f;
}

function compact(object) {
  return Object.fromEntries(Object.entries(object).filter(([, v]) => v !== undefined && v !== null && v !== ''));
}

function activityRow(a) {
  const onFoot = a.sport === 'run' || a.sport === 'walk';
  return compact({
    id: a.id,
    date: a.date,
    start: a.start.slice(11, 16),
    name: a.name,
    sport: SPORTS[a.sport]?.name,
    type: a.sportType,
    distanceKm: round((a.distanceM ?? 0) / 1000, 2) || undefined,
    movingTime: a.movingS ? formatClock(a.movingS) : undefined,
    pace: onFoot && a.avgSpeed ? formatPace(a.avgSpeed) : undefined,
    speed: !onFoot && a.avgSpeed && a.distanceM ? formatSpeed(a.avgSpeed) : undefined,
    avgHr: round(a.avgHr),
    maxHr: round(a.maxHr),
    avgWatts: round(a.avgWatts),
    normWatts: round(a.weightedWatts),
    elevationM: round(a.elevationM),
    kcal: round(a.calories),
    trainingLoad: round(a.trainingLoad),
    aerobicTE: a.aerobicTE,
    anaerobicTE: a.anaerobicTE,
    effect: a.teLabel,
    sufferScore: a.sufferScore,
    race: a.workoutType === 1 || a.workoutType === 11 ? true : undefined,
    indoor: a.trainer,
    sets: a.sets,
    reps: a.reps,
    sources: a.sources.join('+'),
  });
}

function wellnessRow(d) {
  return compact({
    date: d.date,
    sleepH: round((d.sleep?.totalS ?? NaN) / 3600, 1),
    deepH: round((d.sleep?.deepS ?? NaN) / 3600, 1),
    remH: round((d.sleep?.remS ?? NaN) / 3600, 1),
    awakeMin: round((d.sleep?.awakeS ?? NaN) / 60),
    sleepScore: d.sleep?.score,
    sleepQuality: d.sleep?.quality,
    bedtime: d.sleep?.start?.slice(11),
    wakeTime: d.sleep?.end?.slice(11),
    hrvMs: d.hrv?.lastNight,
    hrvWeekMs: d.hrv?.weeklyAvg,
    hrvStatus: d.hrv?.status,
    hrvNormalRange: d.hrv?.baselineLow && d.hrv?.baselineHigh ? `${d.hrv.baselineLow}-${d.hrv.baselineHigh}` : undefined,
    restingHr: d.rhr,
    restingHr7d: d.rhr7d,
    bodyBatteryHigh: d.bbHigh,
    bodyBatteryLow: d.bbLow,
    stressAvg: d.stressAvg,
    steps: d.steps,
    stepGoal: d.stepGoal,
    intensityMin: (d.intensityModerate ?? 0) + 2 * (d.intensityVigorous ?? 0) || undefined,
    activeKcal: d.kcalActive,
    readiness: d.readiness?.score,
    readinessLevel: d.readiness?.level,
    recoveryH: round((d.readiness?.recoveryMin ?? NaN) / 60),
    weightKg: d.weightKg,
    bodyFatPct: d.bodyFatPct,
  });
}

function nutritionRow(e) {
  return compact({
    date: e.date,
    time: e.time,
    kind: e.kind,
    what: e.description,
    kcal: e.kcal,
    proteinG: e.proteinG,
    carbsG: e.carbsG,
    fatG: e.fatG,
    fluidMl: e.fluidMl,
    alcohol: e.alcoholUnits,
    caffeineMg: e.caffeineMg,
    estimated: e.source !== 'hand' || undefined,
  });
}

function clampRange(from, to, maxDays) {
  let start = from <= to ? from : to;
  const end = from <= to ? to : from;
  if (daysBetween(start, end) > maxDays - 1) start = addDays(end, -(maxDays - 1));
  return { from: start, to: end };
}

const INTAKE_KEYS = {
  protein_g: 'proteinG',
  carbs_g: 'carbsG',
  fat_g: 'fatG',
  fluid_ml: 'fluidMl',
  alcohol_units: 'alcoholUnits',
  caffeine_mg: 'caffeineMg',
};

/**
 * Tool implementations over the app's state. Each returns
 * `{ content, summary, isError?, effect? }`: content goes back to Claude,
 * summary is shown in the chat, effect tells the app what changed.
 */
export function createToolbox({ getBundle, getEntries, addEntry, addMemory, getSettings, now = () => new Date() }) {
  let cache = { bundle: null, activities: [] };
  const activities = () => {
    const bundle = getBundle();
    if (cache.bundle !== bundle) cache = { bundle, activities: bundle ? mergeActivities(bundle) : [] };
    return cache.activities;
  };
  const json = (value) => JSON.stringify(value);

  const handlers = {
    get_activities({ from, to, sport, limit }) {
      if (!getBundle()) return { content: json({ note: 'Er is nog geen Strava- of Garmin-data gesynchroniseerd.' }) };
      const range = clampRange(from, to, 366);
      const key = sport ? sportFromName(sport) : null;
      const list = activitiesInRange(activities(), range.from, range.to, key);
      const max = Math.min(Math.max(limit ?? 50, 1), 200);
      return {
        content: json({ ...range, total: list.length, shown: Math.min(max, list.length), activities: list.slice(0, max).map(activityRow) }),
        summary: `${list.length} activiteiten, ${formatDay(range.from)} – ${formatDay(range.to)}`,
      };
    },

    get_activity_details({ id }) {
      const activity = activities().find((a) => a.id === id || a.id.endsWith(`:${id}`));
      if (!activity) return { content: json({ error: `Geen activiteit met id ${id}.` }), isError: true };
      const detail = compact({
        ...activityRow(activity),
        elapsedTime: activity.elapsedS ? formatClock(activity.elapsedS) : undefined,
        cadence: activity.cadence,
        vo2max: activity.vo2max,
        description: activity.description,
        device: activity.device,
        prCount: activity.prCount,
        commute: activity.commute,
        laps: activity.laps?.map((l) =>
          compact({
            name: l.name,
            km: round((l.distanceM ?? 0) / 1000, 2),
            time: formatClock(l.movingS),
            pace: l.avgSpeed ? formatPace(l.avgSpeed) : undefined,
            avgHr: l.avgHr,
            watts: l.avgWatts,
          }),
        ),
        splits: activity.splits?.map((s) =>
          compact({ km: s.km, pace: s.avgSpeed ? formatPace(s.avgSpeed) : undefined, avgHr: s.avgHr, elevDiffM: s.elevDiffM }),
        ),
        bestEfforts: activity.bestEfforts?.map((e) => compact({ name: e.name, time: formatClock(e.movingS), prRank: e.prRank })),
      });
      return { content: json(detail), summary: activity.name };
    },

    get_wellness({ from, to }) {
      const bundle = getBundle();
      if (!bundle?.garmin) return { content: json({ note: 'Er zijn geen Garmin-gegevens gesynchroniseerd.' }) };
      const range = clampRange(from, to, 92);
      const days = wellnessInRange(bundle, range.from, range.to).filter((d) => Object.keys(d).length > 1);
      return {
        content: json({ ...range, days: days.map(wellnessRow) }),
        summary: `${days.length} dagen, ${formatDay(range.from)} – ${formatDay(range.to)}`,
      };
    },

    get_training_summary({ weeks }) {
      const bundle = getBundle();
      if (!bundle) return { content: json({ note: 'Er is nog geen Strava- of Garmin-data gesynchroniseerd.' }) };
      const count = Math.min(Math.max(weeks ?? 8, 1), 52);
      const summary = weeklySummary(activities(), { weeks: count, today: localDate(now()) }).map((w) =>
        compact({
          week: w.week,
          count: w.count,
          hours: round(w.movingS / 3600, 1),
          distanceKm: round(w.distanceM / 1000, 1),
          elevationM: round(w.elevationM),
          trainingLoad: round(w.load) || undefined,
          bySport: Object.fromEntries(
            Object.entries(w.bySport).map(([sport, t]) => [
              SPORTS[sport].name,
              compact({ count: t.count, hours: round(t.movingS / 3600, 1), distanceKm: round(t.distanceM / 1000, 1) || undefined }),
            ]),
          ),
        }),
      );
      return {
        content: json({
          weeks: summary,
          garminStatus: bundle.garmin?.status ?? null,
          stravaTotals: bundle.strava?.totals ?? null,
          heartRateZones: bundle.strava?.hrZones ?? null,
        }),
        summary: `${count} weken`,
      };
    },

    get_nutrition_log({ from, to }) {
      const range = clampRange(from, to, 31);
      const entries = getEntries().filter((e) => e.date >= range.from && e.date <= range.to);
      const days = totalsByDay(entries, range.from, range.to);
      return {
        content: json({
          ...range,
          targets: compact(getSettings().targets ?? {}),
          entries: entries.map(nutritionRow),
          dayTotals: Object.fromEntries(
            Object.entries(days).map(([day, t]) => [
              day,
              compact({ items: t.count, kcal: t.kcal || undefined, kcalComplete: t.kcalComplete, proteinG: t.proteinG || undefined, fluidMl: t.fluidMl || undefined, alcohol: t.alcoholUnits || undefined, caffeineMg: t.caffeineMg || undefined }),
            ]),
          ),
        }),
        summary: `${entries.length} items`,
      };
    },

    async log_intake(input) {
      const fields = { ...input };
      for (const [from, to] of Object.entries(INTAKE_KEYS)) {
        if (from in fields) {
          fields[to] = fields[from];
          delete fields[from];
        }
      }
      const entry = createEntry(fields, { now: now(), source: 'coach' });
      await addEntry(entry);
      return { content: json({ saved: true, entry: nutritionRow(entry) }), summary: entry.description, effect: { type: 'nutrition', entry } };
    },

    async remember({ note }) {
      const text = String(note).trim().slice(0, 300);
      const item = await addMemory(text);
      return { content: json({ saved: true }), summary: text, effect: { type: 'memory', item } };
    },
  };

  return async function execute(name, input) {
    const handler = handlers[name];
    if (!handler) return { content: json({ error: `Onbekende tool ${name}.` }), isError: true };
    try {
      return await handler(input);
    } catch (err) {
      return { content: json({ error: err.message || String(err) }), isError: true };
    }
  };
}

/* ------------------------------------------------------------------ */
/* System prompt                                                       */
/* ------------------------------------------------------------------ */

const PERSONA = `Je bent de persoonlijke leefstijlcoach in de app Coach. Je helpt de gebruiker met training, herstel, slaap, voeding, drinken, stress en gewoontes. Je kunt de eigen gegevens van de gebruiker opvragen: activiteiten uit Strava, horlogegegevens uit Garmin Connect (slaap, HRV, rusthartslag, Body Battery, stress, stappen, trainingsstatus) en een voedings- en drinklogboek dat de gebruiker zelf bijhoudt.

<werkwijze>
- Baseer wat je over de training, het herstel of de voeding van de gebruiker zegt op de echte gegevens. Haal met de tools op wat je nodig hebt; gok niet. Het overzicht hieronder is een momentopname van het begin van dit gesprek.
- Noem concrete getallen met de datum erbij, en wees duidelijk over de herkomst: gemeten door Strava of Garmin, zelf gelogd, of door jou berekend of geschat.
- Ontbreekt iets (geen Garmin, geen voedingslog, een gat in de data), zeg dat kort en help met wat er wel is.
- Geef praktisch en haalbaar advies: één tot drie concrete stappen die passen bij de doelen en het niveau van de gebruiker, met kort waarom.
- Vraag door als je advies echt afhangt van iets wat je niet weet; anders niet.
- Wees eerlijk en vriendelijk, niet zalvend. Geen moraliseren over eten of drinken.
- Leg alleen iets in het voedingslog vast (log_intake) als de gebruiker daarom vraagt. Gebruik remember voor blijvende feiten die de gebruiker deelt, zoals een blessure, een doel of een voorkeur, en zeg kort dat je het onthoudt.
</werkwijze>

<grenzen>
- Je bent geen arts of diëtist. Bij klachten zoals pijn op de borst, flauwvallen, benauwdheid, hartritmeklachten, aanhoudende pijn of een blessure die niet herstelt: raad aan contact op te nemen met de huisarts (of 112 bij acute nood) en stel geen diagnose.
- Geen crashdiëten of grote tekorten. Merk je signalen van een verstoorde verhouding met eten of sporten, reageer dan steunend en noem professionele hulp.
- Over supplementen en medicijnen alleen algemene, voorzichtige informatie.
</grenzen>

<vorm>
- Schrijf in het Nederlands, met metrische eenheden, tempo in min/km en datums als "di 14 okt".
- Houd antwoorden kort en overzichtelijk: een paar zinnen of een korte lijst. Gebruik vet spaarzaam en geen tabellen, tenzij de gebruiker erom vraagt. Disclaimers kort.
- Elk bericht van de gebruiker begint met een <moment>-blok met de datum en tijd van dat moment. Dat is "nu".
</vorm>`;

const SEX_LABEL = { man: 'man', vrouw: 'vrouw', anders: 'anders' };

function profileSection(settings, bundle, now) {
  const p = settings.profile ?? {};
  const athlete = bundle?.strava?.athlete;
  const weight = latestWeight(bundle);
  const lines = [];
  const name = p.name || athlete?.firstname;
  if (name) lines.push(`Naam: ${name}`);
  const age = ageFrom(p, now);
  if (age) lines.push(`Leeftijd: ${age}`);
  if (SEX_LABEL[p.sex]) lines.push(`Geslacht: ${SEX_LABEL[p.sex]}`);
  if (p.heightCm) lines.push(`Lengte: ${p.heightCm} cm`);
  if (weight) lines.push(`Gewicht: ${formatNumber(weight.kg, 1)} kg (Garmin, ${formatDay(weight.date)})`);
  else if (p.weightKg) lines.push(`Gewicht: ${p.weightKg} kg (zelf ingevuld)`);
  else if (athlete?.weightKg) lines.push(`Gewicht: ${athlete.weightKg} kg (Strava-profiel)`);
  if (athlete?.ftp) lines.push(`FTP: ${athlete.ftp} W`);
  if (p.goals) lines.push(`Doelen: ${p.goals}`);
  if (p.notes) lines.push(`Wat de coach moet weten: ${p.notes}`);
  if (!lines.length) lines.push('De gebruiker heeft nog geen profiel ingevuld.');
  const t = settings.targets ?? {};
  const targets = [
    t.kcal && `${t.kcal} kcal`,
    t.proteinG && `${t.proteinG} g eiwit`,
    t.waterMl && `${formatLitres(t.waterMl)} vocht`,
  ].filter(Boolean);
  if (targets.length) lines.push(`Streefwaarden per dag: ${targets.join(', ')}`);
  return `<profiel>\n${lines.join('\n')}\n</profiel>`;
}

function sourceLine(name, status, generatedAt) {
  if (!status?.configured) return `${name}: niet gekoppeld.`;
  if (!status.ok) {
    const since = status.lastSuccess ? `gegevens tot ${formatStamp(status.lastSuccess)}` : 'nog geen gegevens';
    return `${name}: laatste synchronisatie mislukt (${status.error}); ${since}.`;
  }
  return `${name}: gekoppeld, gesynchroniseerd ${formatStamp(generatedAt)}.`;
}

function dataSection(bundle, entries, settings, now) {
  const today = localDate(now);
  const lines = [];
  if (!bundle) {
    lines.push('Er is nog geen Strava- of Garmin-data gesynchroniseerd. Help met wat de gebruiker vertelt en met het voedingslog.');
  } else {
    lines.push(sourceLine('Strava', bundle.sources?.strava, bundle.generatedAt));
    lines.push(sourceLine('Garmin', bundle.sources?.garmin, bundle.generatedAt));

    const day = wellnessOn(bundle, today) ?? wellnessOn(bundle, addDays(today, -1));
    if (day && Object.keys(day).length > 1) {
      const parts = [
        day.sleep?.totalS && `slaap ${formatHours(day.sleep.totalS)}${day.sleep.score ? ` (score ${day.sleep.score})` : ''}`,
        day.hrv?.lastNight && `HRV ${day.hrv.lastNight} ms${day.hrv.status ? ` (${day.hrv.status})` : ''}`,
        day.rhr && `rusthartslag ${day.rhr}`,
        day.bbHigh !== undefined && `Body Battery max ${day.bbHigh}`,
        day.readiness?.score !== undefined && `trainingsgereedheid ${day.readiness.score}`,
        day.steps !== undefined && `${formatNumber(day.steps)} stappen`,
      ].filter(Boolean);
      lines.push(`Garmin ${formatDay(day.date)}: ${parts.join(', ')}.`);
    }

    const week = wellnessInRange(bundle, addDays(today, -6), today);
    if (week.length) {
      const s = wellnessSummary(week);
      const parts = [
        s.sleepS && `slaap ${formatHours(s.sleepS)}`,
        s.rhr && `rusthartslag ${formatNumber(s.rhr)}`,
        s.hrv && `HRV ${formatNumber(s.hrv)} ms`,
        s.steps && `${formatNumber(s.steps)} stappen`,
      ].filter(Boolean);
      if (parts.length) lines.push(`Gemiddeld afgelopen 7 dagen: ${parts.join(', ')}.`);
    }

    const all = mergeActivities(bundle);
    const recent = activitiesInRange(all, addDays(today, -6), today);
    if (all.length) {
      const bySport = {};
      for (const a of recent) {
        const t = (bySport[a.sport] ??= { count: 0, seconds: 0, meters: 0 });
        t.count += 1;
        t.seconds += a.movingS ?? 0;
        t.meters += a.distanceM ?? 0;
      }
      const sports = Object.entries(bySport).map(
        ([sport, t]) => `${SPORTS[sport].name} ${t.count}x ${formatHours(t.seconds)}${t.meters ? ` ${formatDistance(t.meters)}` : ''}`,
      );
      lines.push(`Afgelopen 7 dagen: ${recent.length} activiteiten${sports.length ? ` (${sports.join('; ')})` : ''}.`);
      const last = all.slice(0, 5).map((a) => {
        const bits = [formatDay(a.date), a.name, SPORTS[a.sport].name, a.distanceM && formatDistance(a.distanceM), a.movingS && formatClock(a.movingS), a.avgHr && `hr ${Math.round(a.avgHr)}`];
        return `- ${bits.filter(Boolean).join(', ')} [${a.id}]`;
      });
      lines.push(`Laatste activiteiten:\n${last.join('\n')}`);
    }

    const status = bundle.garmin?.status;
    if (status) {
      const parts = [
        status.vo2max && `VO2max ${formatNumber(status.vo2max, 1)}`,
        status.trainingStatus && `trainingsstatus ${status.trainingStatus}`,
        status.acuteLoad !== undefined && status.chronicLoad !== undefined && `belasting acuut ${status.acuteLoad} / chronisch ${status.chronicLoad}`,
      ].filter(Boolean);
      if (parts.length) lines.push(`Garmin-status: ${parts.join(', ')}.`);
    }
  }

  const todayEntries = entriesOn(entries, today);
  if (todayEntries.length) {
    const t = totals(todayEntries);
    const parts = [
      `${t.count} items`,
      t.kcal && `${formatNumber(t.kcal)} kcal${t.kcalComplete ? '' : ' (niet alles heeft kcal)'}`,
      t.proteinG && `${formatNumber(t.proteinG)} g eiwit`,
      t.fluidMl && `${formatLitres(t.fluidMl)} vocht`,
      t.alcoholUnits && `${formatNumber(t.alcoholUnits)} glazen alcohol`,
    ].filter(Boolean);
    lines.push(`Voedingslog vandaag: ${parts.join(', ')}.`);
  } else {
    lines.push(entries.length ? 'Voedingslog vandaag: nog niets gelogd.' : 'Het voedingslog wordt (nog) niet gebruikt.');
  }
  return `<overzicht moment="${formatMoment(now)}">\n${lines.join('\n')}\n</overzicht>`;
}

export function buildSystemPrompt({ settings, bundle, entries = [], memory = [], now = new Date() }) {
  const sections = [PERSONA, profileSection(settings, bundle, now)];
  if (memory.length) {
    sections.push(`<onthouden>\n${memory.map((m) => `- ${m.text} (${formatDay(m.date)})`).join('\n')}\n</onthouden>`);
  }
  sections.push(dataSection(bundle, entries, settings, now));
  return sections.join('\n\n');
}

/* ------------------------------------------------------------------ */
/* Conversation                                                        */
/* ------------------------------------------------------------------ */

const MOMENT_PREFIX = '<moment>';

export function newConversation({ settings, bundle, entries, memory, now = new Date() }) {
  return {
    id: globalThis.crypto?.randomUUID?.() ?? String(now.getTime()),
    createdAt: now.toISOString(),
    system: buildSystemPrompt({ settings, bundle, entries, memory, now }),
    messages: [],
  };
}

export function userMessage(text, { now = new Date(), images = [] } = {}) {
  const content = [{ type: 'text', text: `${MOMENT_PREFIX}${formatMoment(now)}</moment>` }];
  for (const image of images) {
    content.push({ type: 'image', source: { type: 'base64', media_type: image.mediaType, data: image.data } });
  }
  content.push({ type: 'text', text });
  return { role: 'user', content };
}

/**
 * After a fallback mid-answer, the declined model's reasoning and tool calls
 * before the switch must not be sent back; its text may.
 */
export function contentForHistory(content) {
  const boundary = content.findLastIndex((block) => block.type === 'fallback');
  if (boundary < 0) return content;
  return content.filter((block, index) => index > boundary || block.type === 'text');
}

/** What the chat shows: user bubbles and assistant turns, tool traffic folded in. */
export function conversationView(messages) {
  const items = [];
  let turn = null;
  for (const message of messages) {
    const blocks = typeof message.content === 'string' ? [{ type: 'text', text: message.content }] : message.content;
    if (message.role === 'user') {
      if (blocks.every((b) => b.type === 'tool_result')) continue;
      turn = null;
      const text = blocks
        .filter((b) => b.type === 'text' && !b.text.startsWith(MOMENT_PREFIX))
        .map((b) => b.text)
        .join('\n');
      items.push({ role: 'user', text, images: blocks.filter((b) => b.type === 'image').length });
      continue;
    }
    if (!turn) {
      turn = { role: 'assistant', parts: [] };
      items.push(turn);
    }
    for (const block of blocks) {
      if (block.type === 'text' && block.text.trim()) turn.parts.push({ type: 'text', text: block.text });
      else if (block.type === 'tool_use') turn.parts.push({ type: 'tool', name: block.name, input: block.input });
      else if (block.type === 'thinking' && block.thinking?.trim()) turn.parts.push({ type: 'update', text: block.thinking });
    }
  }
  return items;
}

export class CoachError extends Error {
  constructor(message, options) {
    super(message, options);
    this.name = 'CoachError';
  }
}

async function streamOnce(client, params, onEvent, signal) {
  const stream = client.beta.messages.stream(params, { signal });
  for await (const event of stream) {
    if (event.type === 'content_block_start') {
      const block = event.content_block;
      if (block.type === 'tool_use') onEvent({ type: 'tool_start', name: block.name, index: event.index });
      else if (block.type === 'fallback') onEvent({ type: 'fallback', from: block.from?.model, to: block.to?.model });
    } else if (event.type === 'content_block_delta') {
      if (event.delta.type === 'text_delta') onEvent({ type: 'text', text: event.delta.text, index: event.index });
      else if (event.delta.type === 'thinking_delta' && event.delta.thinking) {
        onEvent({ type: 'update', text: event.delta.thinking, index: event.index });
      }
    }
  }
  return stream.finalMessage();
}

/**
 * One user turn: stream Claude's answer, run the tools it asks for, repeat
 * until it is done. Every message is handed to `append` the moment it
 * exists, so the caller can persist it; `messages` is extended in place.
 *
 * `compatRequest` is the same request without the optional features; it is
 * used once if the API rejects the full one with a 400.
 */
export async function runTurn({
  client,
  isApiError,
  isBadRequest = () => false,
  request,
  compatRequest = null,
  messages,
  execute,
  append = async () => {},
  onEvent = () => {},
  signal,
  maxSteps = MAX_TOOL_STEPS,
}) {
  let current = request;
  let jsonRetries = 0;

  const push = async (message) => {
    messages.push(message);
    await append(message);
  };

  for (let step = 0; step < maxSteps; step += 1) {
    let message;
    try {
      // A copy: the request must not see messages appended after it was sent.
      message = await streamOnce(client, { ...current, messages: [...messages] }, onEvent, signal);
      jsonRetries = 0;
    } catch (err) {
      if (signal?.aborted) throw err;
      if (compatRequest && current !== compatRequest && isBadRequest(err)) {
        current = compatRequest;
        onEvent({ type: 'compat', error: err });
        step -= 1;
        continue;
      }
      // Only a tool input the SDK could not parse at all is retried here.
      if (isApiError(err) || jsonRetries >= 2) throw err;
      jsonRetries += 1;
      onEvent({ type: 'retry' });
      step -= 1;
      continue;
    }

    if (message.stop_reason === 'refusal') {
      // A partial answer before a refusal is not kept.
      onEvent({ type: 'refusal', details: message.stop_details ?? null });
      return { stopReason: 'refusal', compat: current === compatRequest };
    }

    const content = contentForHistory(message.content);
    const toolUses = content.filter((block) => block.type === 'tool_use');
    if (message.stop_reason === 'max_tokens' && toolUses.length) {
      throw new CoachError('Het antwoord werd te lang en is afgebroken. Stel de vraag iets specifieker.');
    }
    await push({ role: 'assistant', content });
    onEvent({ type: 'assistant', content, model: message.model });

    if (message.stop_reason === 'pause_turn') continue;
    if (!toolUses.length) return { stopReason: message.stop_reason, compat: current === compatRequest };

    const results = await Promise.all(
      toolUses.map(async (block) => {
        const check = validateInput(block.name, block.input);
        if (!check.ok) {
          onEvent({ type: 'tool_done', name: block.name, isError: true, summary: check.error });
          return {
            type: 'tool_result',
            tool_use_id: block.id,
            is_error: true,
            content: JSON.stringify({ INVALID_JSON: JSON.stringify(block.input), error: check.error }),
          };
        }
        const result = await execute(block.name, check.value);
        onEvent({ type: 'tool_done', name: block.name, isError: Boolean(result.isError), summary: result.summary, effect: result.effect });
        return {
          type: 'tool_result',
          tool_use_id: block.id,
          content: result.content,
          ...(result.isError ? { is_error: true } : {}),
        };
      }),
    );
    // All results of one step go back together, in one user message.
    await push({ role: 'user', content: results });
  }
  return { stopReason: 'max_steps', compat: current === compatRequest };
}
