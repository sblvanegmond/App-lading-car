/**
 * Settings and their persistence.
 *
 * Small and read at startup, so they live in localStorage. The larger data
 * (Strava/Garmin bundle, food log, conversation) goes through store.js.
 */

export const STORAGE_KEY = 'coach.settings.v1';

/** Claude Opus 5.5 is the default; the others trade advice quality for speed and price. */
export const MODELS = [
  { id: 'claude-opus-5-5', label: 'Claude Opus 5.5', note: 'beste advies, standaard' },
  { id: 'claude-sonnet-5-5', label: 'Claude Sonnet 5.5', note: 'sneller en goedkoper' },
  { id: 'claude-haiku-5-5', label: 'Claude Haiku 5.5', note: 'snelst en goedkoopst' },
];

export const DEFAULT_MODEL = MODELS[0].id;

export const DEFAULT_SETTINGS = {
  profile: {
    name: '',
    birthYear: null,
    /** 'man' | 'vrouw' | 'anders' | '' */
    sex: '',
    heightCm: null,
    weightKg: null,
    /** Free text: what the user wants to work towards. */
    goals: '',
    /** Free text: injuries, schedule, diet, anything the coach should know. */
    notes: '',
  },
  targets: {
    kcal: null,
    proteinG: null,
    waterMl: 2000,
  },
  claude: {
    apiKey: '',
    model: DEFAULT_MODEL,
  },
  sync: {
    /** Empty means: derive from the GitHub Pages address. */
    owner: '',
    repo: '',
    branch: 'coach-data',
    key: '',
    /** Optional fine-grained token: private repo and the "sync now" button. */
    githubToken: '',
    workflow: 'coach-sync.yml',
  },
};

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function mergeSettings(base, patch) {
  const out = structuredClone(base);
  if (!isPlainObject(patch)) return out;
  for (const [key, value] of Object.entries(patch)) {
    if (isPlainObject(value) && isPlainObject(out[key])) out[key] = mergeSettings(out[key], value);
    else if (value !== undefined) out[key] = value;
  }
  return out;
}

export function defaultSettings() {
  return structuredClone(DEFAULT_SETTINGS);
}

export function loadSettings(storage = globalThis.localStorage) {
  try {
    const raw = storage?.getItem(STORAGE_KEY);
    return raw ? mergeSettings(DEFAULT_SETTINGS, JSON.parse(raw)) : defaultSettings();
  } catch {
    return defaultSettings();
  }
}

export function saveSettings(settings, storage = globalThis.localStorage) {
  storage?.setItem(STORAGE_KEY, JSON.stringify(settings));
}

/**
 * `https://<owner>.github.io/<repo>/coach/` tells us where the data branch
 * lives, so on GitHub Pages the user does not have to type it.
 */
export function repoFromLocation(location = globalThis.location) {
  const host = String(location?.hostname ?? '');
  const match = host.match(/^([a-z0-9-]+)\.github\.io$/i);
  if (!match) return null;
  const repo = String(location.pathname ?? '').split('/').filter(Boolean)[0];
  return repo ? { owner: match[1], repo } : { owner: match[1], repo: `${match[1]}.github.io` };
}

export function resolveRepo(sync, location = globalThis.location) {
  if (sync?.owner && sync?.repo) return { owner: sync.owner.trim(), repo: sync.repo.trim() };
  return repoFromLocation(location);
}

export function ageFrom(profile, now = new Date()) {
  const year = Number(profile?.birthYear);
  return year > 1900 && year <= now.getFullYear() ? now.getFullYear() - year : null;
}
