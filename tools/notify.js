#!/usr/bin/env node
/**
 * The morning push notification.
 *
 * Runs on a schedule in GitHub Actions, computes the plan with exactly the
 * same code as the app, and pushes one line to your phone.
 *
 *   node tools/notify.js            # sends only inside the chosen window
 *   node tools/notify.js --force    # sends right now, for testing
 *   node tools/notify.js --dry-run  # prints the message, sends nothing
 *
 * Needs these environment variables (GitHub secrets):
 *   VAPID_PUBLIC_KEY   the same public key the app is subscribed with
 *   VAPID_PRIVATE_KEY  its private half, from `npm run vapid`
 *   VAPID_SUBJECT      a mailto: or https: address identifying you
 *   PUSH_SUBSCRIPTION  the JSON the app shows after subscribing; one object,
 *                      or an array of them for several phones
 *
 * Settings come from notify-config.json when present, and otherwise from the
 * defaults. Copy them out of the app with "Kopieer instellingen".
 */

import { readFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { defaultSettings, mergeSettings } from '../js/config.js';
import { loadData } from '../js/api.js';
import { buildTimeline, planCharging } from '../js/planner.js';
import { briefingNotification, briefingText } from '../js/briefing.js';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const CONFIG = join(ROOT, 'notify-config.json');

const args = new Set(process.argv.slice(2));
const FORCE = args.has('--force');
const DRY_RUN = args.has('--dry-run');

/** How far past the chosen time a run may still deliver the briefing. */
const WINDOW_MINUTES = 60;

function log(message) {
  console.log(`[laadmoment] ${message}`);
}

async function readSettings() {
  try {
    const raw = await readFile(CONFIG, 'utf8');
    log(`Instellingen uit ${CONFIG.replace(`${ROOT}/`, '')}.`);
    return mergeSettings(defaultSettings(), JSON.parse(raw));
  } catch (err) {
    if (err.code !== 'ENOENT') {
      throw new Error(`notify-config.json kon niet gelezen worden: ${err.message}`);
    }
    log('Geen notify-config.json gevonden, standaardinstellingen gebruikt.');
    return defaultSettings();
  }
}

function parseSubscriptions(raw) {
  if (!raw || !raw.trim()) return [];
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    throw new Error(`PUSH_SUBSCRIPTION is geen geldige JSON: ${err.message}`);
  }
  const list = Array.isArray(parsed) ? parsed : [parsed];
  return list.filter((item) => {
    const ok = item && typeof item.endpoint === 'string' && item.keys?.p256dh && item.keys?.auth;
    if (!ok) log('Een aanmelding wordt overgeslagen: endpoint of sleutels ontbreken.');
    return ok;
  });
}

/**
 * Whether this run falls inside the window after the chosen time.
 * The workflow fires every hour so that summer and winter time both land,
 * and this is what keeps exactly one of those runs from being a no-op.
 */
export function insideWindow(now, hhmm, windowMinutes = WINDOW_MINUTES) {
  const [h, m] = String(hhmm ?? '06:45').split(':').map((n) => parseInt(n, 10));
  const target = (Number.isFinite(h) ? h : 6) * 60 + (Number.isFinite(m) ? m : 45);
  const current = now.getHours() * 60 + now.getMinutes();
  const delta = current - target;
  return delta >= 0 && delta < windowMinutes;
}

async function main() {
  const settings = await readSettings();
  const now = new Date();
  log(`Lokale tijd: ${now.toLocaleString('nl-NL')} (${process.env.TZ ?? 'systeemzone'}).`);

  const subscriptions = parseSubscriptions(process.env.PUSH_SUBSCRIPTION);
  const hasKeys = Boolean(process.env.VAPID_PUBLIC_KEY && process.env.VAPID_PRIVATE_KEY);

  if (!DRY_RUN && (!hasKeys || subscriptions.length === 0)) {
    log('Geen VAPID-sleutels of aanmeldingen ingesteld. Niets te doen.');
    log('Zet de secrets VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY, VAPID_SUBJECT en PUSH_SUBSCRIPTION.');
    return;
  }

  if (!FORCE && !DRY_RUN && !insideWindow(now, settings.notifications.time)) {
    log(`Buiten het venster van ${settings.notifications.time}. Deze run stuurt niets.`);
    return;
  }

  log('Prijzen en weerdata ophalen…');
  const data = await loadData(settings, { now, force: true });
  for (const error of data.errors) log(`Waarschuwing: ${error.message}`);
  if (data.prices.length === 0) {
    throw new Error('Geen stroomprijzen opgehaald; er valt niets te plannen.');
  }

  const timeline = buildTimeline(data.prices, data.solar.hours, settings);
  const plan = planCharging({ timeline, settings, now });
  const { title, body, empty } = briefingNotification(plan, { now });

  log('');
  log(briefingText(plan, settings, { now }).split('\n').join(`\n[laadmoment] `));
  log('');

  if (empty) {
    log('Geen laadvenster te melden. Er wordt niets verstuurd.');
    return;
  }
  if (DRY_RUN) {
    log(`Proefdraai: "${title}" — "${body}"`);
    return;
  }

  const webpush = (await import('web-push')).default;
  webpush.setVapidDetails(
    process.env.VAPID_SUBJECT || 'mailto:laadmoment@example.com',
    process.env.VAPID_PUBLIC_KEY,
    process.env.VAPID_PRIVATE_KEY,
  );

  const payload = JSON.stringify({ title, body, tag: 'laadmoment-ochtend', url: './' });
  let delivered = 0;

  for (const [index, subscription] of subscriptions.entries()) {
    try {
      await webpush.sendNotification(subscription, payload, { TTL: 6 * 3600 });
      delivered += 1;
      log(`Aanmelding ${index + 1}: verstuurd.`);
    } catch (err) {
      const status = err.statusCode;
      if (status === 404 || status === 410) {
        log(
          `Aanmelding ${index + 1}: verlopen (${status}). Meld je in de app opnieuw aan ` +
            'en vervang het secret PUSH_SUBSCRIPTION.',
        );
      } else if (status === 403) {
        log(
          `Aanmelding ${index + 1}: geweigerd (403). De VAPID-sleutel hoort niet bij deze ` +
            'aanmelding; meld je in de app opnieuw aan met de juiste publieke sleutel.',
        );
      } else {
        log(`Aanmelding ${index + 1}: mislukt (${status ?? 'onbekend'}) ${err.message}`);
      }
    }
  }

  log(`Klaar: ${delivered} van ${subscriptions.length} meldingen verstuurd.`);
  if (delivered === 0) throw new Error('Geen enkele melding kwam aan.');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((err) => {
    console.error(`[laadmoment] Fout: ${err.message}`);
    process.exitCode = 1;
  });
}
