/**
 * Dutch formatting for numbers, distances, durations and dates.
 *
 * Calendar days travel through the app as 'YYYY-MM-DD' strings in local
 * time, the same way Garmin and Strava's *_local fields express them, so
 * there is never a timezone conversion between "the day" and its data.
 */

const number = (digits) =>
  new Intl.NumberFormat('nl-NL', { minimumFractionDigits: digits, maximumFractionDigits: digits });

export function formatNumber(value, digits = 0) {
  return Number.isFinite(value) ? number(digits).format(value) : '–';
}

export function formatDistance(meters) {
  if (!Number.isFinite(meters) || meters <= 0) return '–';
  if (meters < 1000) return `${formatNumber(meters)} m`;
  return `${formatNumber(meters / 1000, meters < 100000 ? 1 : 0)} km`;
}

function pad(value) {
  return String(value).padStart(2, '0');
}

/** 50:12 or 1:02:03, for activity times. */
export function formatClock(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return '–';
  const s = Math.round(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const rest = s % 60;
  return h ? `${h}:${pad(m)}:${pad(rest)}` : `${m}:${pad(rest)}`;
}

/** 7u15 or 45 min, for sleep and totals. */
export function formatHours(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return '–';
  const minutes = Math.round(seconds / 60);
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h ? `${h}u${pad(m)}` : `${m} min`;
}

export function formatPace(speedMs) {
  if (!Number.isFinite(speedMs) || speedMs <= 0) return '–';
  const secondsPerKm = Math.round(1000 / speedMs);
  return `${Math.floor(secondsPerKm / 60)}:${pad(secondsPerKm % 60)} /km`;
}

export function formatSpeed(speedMs) {
  if (!Number.isFinite(speedMs) || speedMs <= 0) return '–';
  return `${formatNumber(speedMs * 3.6, 1)} km/u`;
}

export function formatLitres(ml) {
  if (!Number.isFinite(ml) || ml <= 0) return '0 l';
  return ml < 1000 ? `${formatNumber(ml)} ml` : `${formatNumber(ml / 1000, 2)} l`;
}

export function localDate(date = new Date()) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function localTime(date = new Date()) {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function parseDay(ymd) {
  const [y, m, d] = String(ymd).split('-').map(Number);
  return new Date(y, m - 1, d, 12);
}

export function isDay(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value))) return false;
  return localDate(parseDay(value)) === value;
}

export function addDays(ymd, days) {
  const date = parseDay(ymd);
  date.setDate(date.getDate() + days);
  return localDate(date);
}

export function daysBetween(from, to) {
  return Math.round((parseDay(to) - parseDay(from)) / 86400000);
}

/** Monday of the week the day falls in. */
export function weekStart(ymd) {
  const date = parseDay(ymd);
  const offset = (date.getDay() + 6) % 7;
  date.setDate(date.getDate() - offset);
  return localDate(date);
}

// Built on use rather than at import, so they follow the current time zone.
const DAY_SHORT = () => new Intl.DateTimeFormat('nl-NL', { weekday: 'short', day: 'numeric', month: 'short' });
const DAY_LONG = () => new Intl.DateTimeFormat('nl-NL', { weekday: 'long', day: 'numeric', month: 'long' });
const MOMENT = () =>
  new Intl.DateTimeFormat('nl-NL', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });

export function formatDay(ymd) {
  return isDay(ymd) ? DAY_SHORT().format(parseDay(ymd)).replace(/\./g, '') : String(ymd ?? '');
}

export function formatDayLong(ymd) {
  return isDay(ymd) ? DAY_LONG().format(parseDay(ymd)) : String(ymd ?? '');
}

export function formatMoment(date = new Date()) {
  return MOMENT().format(date);
}

/** An ISO timestamp as local "za 17 okt 08:23". */
export function formatStamp(iso) {
  const date = new Date(iso);
  if (!Number.isFinite(date.getTime())) return String(iso ?? '');
  return `${formatDay(localDate(date))} ${localTime(date)}`;
}

export function timeAgo(iso, now = new Date()) {
  const then = Date.parse(iso);
  if (!Number.isFinite(then)) return 'nooit';
  const minutes = Math.round((now - then) / 60000);
  if (minutes < 2) return 'zojuist';
  if (minutes < 60) return `${minutes} min geleden`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} uur geleden`;
  const days = Math.round(hours / 24);
  return days === 1 ? 'gisteren' : `${days} dagen geleden`;
}
