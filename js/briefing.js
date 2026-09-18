/**
 * The wording of the morning briefing.
 *
 * Shared by three callers: the notification the app shows when you open it,
 * the text behind the share button, and the server-side notifier that runs
 * in GitHub Actions. Keeping it in one place means the phone and the push
 * message never tell you different things.
 *
 * Pure functions only, so this runs in the browser and in Node alike.
 */

const HOUR_MS = 3600000;

function nl(options) {
  return new Intl.DateTimeFormat('nl-NL', { timeZone: options?.timeZone, ...options });
}

function clock(date, timeZone) {
  return nl({ hour: '2-digit', minute: '2-digit', timeZone }).format(date);
}

function euro(amount, fractionDigits = 2) {
  return new Intl.NumberFormat('nl-NL', {
    style: 'currency',
    currency: 'EUR',
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  }).format(Number.isFinite(amount) ? amount : 0);
}

function decimal(value, digits = 1) {
  return new Intl.NumberFormat('nl-NL', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(Number.isFinite(value) ? value : 0);
}

function cents(eurPerKwh) {
  return `${decimal((Number.isFinite(eurPerKwh) ? eurPerKwh : 0) * 100, 1)} ct`;
}

/** "vandaag" / "morgen", relative to the day `now` falls in. */
export function dayWord(date, now = new Date()) {
  const a = new Date(now);
  const b = new Date(date);
  a.setHours(0, 0, 0, 0);
  b.setHours(0, 0, 0, 0);
  const days = Math.round((b - a) / 86400000);
  if (days === 0) return 'vandaag';
  if (days === 1) return 'morgen';
  if (days === 2) return 'overmorgen';
  return nl({ weekday: 'long' }).format(date);
}

/**
 * The one-line headline and body for a push or local notification.
 *
 * @param {object} plan output of planCharging()
 * @param {object} [options] { now, timeZone }
 * @returns {{title: string, body: string, empty: boolean}}
 */
export function briefingNotification(plan, options = {}) {
  const now = options.now ?? new Date();
  const tz = options.timeZone;

  if (!plan || plan.blocks.length === 0) {
    const reason =
      plan && plan.need?.grossKwh <= 0
        ? 'De accu zit al op je doel, laden is vandaag niet nodig.'
        : 'Geen bruikbaar laadvenster gevonden. Open de app voor details.';
    return { title: 'Laadmoment', body: reason, empty: true };
  }

  const first = plan.blocks[0];
  const title = `Laden ${dayWord(first.from, now)} ${clock(first.from, tz)}–${clock(first.to, tz)}`;

  const parts = [
    `${decimal(plan.totals.kwh, 1)} kWh voor ${euro(plan.totals.cost)}`,
    `${cents(plan.totals.avgPricePerKwh)}/kWh`,
  ];
  if (plan.totals.solarSharePct >= 1) {
    parts.push(`${Math.round(plan.totals.solarSharePct)}% eigen zon`);
  }
  if (plan.comparison?.savingsVsImmediate > 0.05) {
    parts.push(`${euro(plan.comparison.savingsVsImmediate)} goedkoper dan nu laden`);
  }
  if (plan.blocks.length > 1) {
    parts.push(`${plan.blocks.length} blokken`);
  }

  return { title, body: parts.join(' · '), empty: false };
}

/**
 * The longer, readable version used by the share button and by the log of
 * the server-side notifier.
 */
export function briefingText(plan, settings, options = {}) {
  const now = options.now ?? new Date();
  const tz = options.timeZone;

  if (!plan || plan.blocks.length === 0) {
    return briefingNotification(plan, options).body;
  }

  const place = settings?.location?.name ? ` voor ${settings.location.name}` : '';
  const lines = [`Laadplan${place}:`];

  for (const block of plan.blocks) {
    const hours = (block.to - block.from) / HOUR_MS;
    lines.push(
      `• ${dayWord(block.from, now)} ${clock(block.from, tz)}–${clock(block.to, tz)} ` +
        `(${decimal(hours, 1)} uur) · ${decimal(block.kwh, 1)} kWh · ${euro(block.cost)} · ` +
        `${Math.round(block.solarSharePct)}% eigen zon`,
    );
  }

  lines.push(
    `Totaal ${decimal(plan.totals.kwh, 1)} kWh voor ${euro(plan.totals.cost)} ` +
      `(${cents(plan.totals.avgPricePerKwh)}/kWh), klaar om ${clock(plan.windowEnd, tz)}.`,
  );

  if (plan.comparison?.savingsVsImmediate > 0.005) {
    lines.push(`Dat is ${euro(plan.comparison.savingsVsImmediate)} minder dan meteen laden.`);
  }
  for (const warning of plan.warnings ?? []) {
    lines.push(`Let op: ${warning}`);
  }

  return lines.join('\n');
}
