/**
 * Bootstrap and wiring.
 *
 * Flow: read settings -> fetch prices and weather -> build the hourly
 * timeline -> plan the charging session -> render. Everything is stored on
 * the device; there is no backend.
 */

import { loadSettings, saveSettings, defaultSettings, mergeSettings } from './config.js';
import { loadData } from './api.js';
import { buildTimeline, planCharging, solarSummary, MODES } from './planner.js';
import { downloadIcs } from './ics.js';
import { briefingNotification, briefingText } from './briefing.js';
import { subscribeToPush, unsubscribeFromPush, currentSubscription, pushSupport } from './push.js';
import * as ui from './ui.js';
import { formatEuro } from './pricing.js';

const el = (id) => document.getElementById(id);

const state = {
  settings: loadSettings(),
  data: null,
  timeline: [],
  plan: null,
  loading: false,
};

/* ------------------------------------------------------------------ */
/* Navigation                                                          */
/* ------------------------------------------------------------------ */

function showView(name) {
  for (const view of document.querySelectorAll('.view')) {
    view.hidden = view.id !== `view-${name}`;
  }
  for (const tab of document.querySelectorAll('.tab')) {
    const active = tab.dataset.view === name;
    tab.classList.toggle('is-active', active);
    tab.setAttribute('aria-selected', active ? 'true' : 'false');
  }
  document.getElementById('main').scrollTo?.({ top: 0 });
  window.scrollTo({ top: 0 });
  try {
    sessionStorage.setItem('laadmoment.view', name);
  } catch {
    /* private mode: remembering the tab is optional */
  }
}

/* ------------------------------------------------------------------ */
/* Settings form                                                       */
/* ------------------------------------------------------------------ */

function getPath(object, path) {
  return path.split('.').reduce((acc, key) => (acc == null ? acc : acc[key]), object);
}

function setPath(object, path, value) {
  const keys = path.split('.');
  const last = keys.pop();
  let cursor = object;
  for (const key of keys) {
    if (typeof cursor[key] !== 'object' || cursor[key] === null) cursor[key] = {};
    cursor = cursor[key];
  }
  cursor[last] = value;
}

function fillForm() {
  const form = el('settings-form');
  for (const input of form.querySelectorAll('[name]')) {
    const path = input.name;
    if (path === 'mode') continue;
    const value = getPath(state.settings, path);
    if (input.type === 'checkbox') input.checked = Boolean(value);
    else if (value === null || value === undefined) input.value = '';
    else input.value = value;
  }
  renderModeOptions();
  renderArrays();
  el('notify-enabled').checked = Boolean(state.settings.notifications.enabled);
  el('notify-time').value = state.settings.notifications.time;
  el('vapid-key').value = state.settings.notifications.vapidPublicKey ?? '';
  el('location-name').textContent = state.settings.location.name || 'je locatie';
}

function renderModeOptions() {
  ui.renderModes(el('mode-options'), state.settings, (mode) => {
    state.settings.mode = mode;
    saveSettings(state.settings);
    renderModeOptions();
    recompute();
  });
}

function renderArrays() {
  ui.renderArrays(el('arrays'), el('array-template'), state.settings.pv.arrays, {
    onRemove: (index) => {
      if (state.settings.pv.arrays.length <= 1) return;
      state.settings.pv.arrays.splice(index, 1);
      saveSettings(state.settings);
      renderArrays();
      recompute();
    },
  });
}

function readForm() {
  const form = el('settings-form');
  const next = JSON.parse(JSON.stringify(state.settings));

  for (const input of form.querySelectorAll('[name]')) {
    const path = input.name;
    if (path === 'mode') continue;
    if (input.type === 'checkbox') {
      setPath(next, path, input.checked);
      continue;
    }
    if (input.type === 'number') {
      const raw = input.value.trim();
      if (raw === '') {
        setPath(next, path, path === 'pv.inverterKw' ? null : getPath(state.settings, path));
      } else {
        const value = Number(raw.replace(',', '.'));
        setPath(next, path, Number.isFinite(value) ? value : getPath(state.settings, path));
      }
      continue;
    }
    setPath(next, path, input.value);
  }

  next.pv.arrays = [...form.querySelectorAll('.array')].map((node, index) => ({
    id: state.settings.pv.arrays[index]?.id ?? `a${index + 1}`,
    label: node.querySelector('[data-key="label"]').value || `Vlak ${index + 1}`,
    kWp: Number(node.querySelector('[data-key="kWp"]').value.replace(',', '.')) || 0,
    tilt: Number(node.querySelector('[data-key="tilt"]').value) || 0,
    azimuth: Number(node.querySelector('[data-key="azimuth"]').value) || 0,
  }));
  if (next.pv.arrays.length === 0) next.pv.arrays = defaultSettings().pv.arrays;

  next.mode = form.querySelector('input[name="mode"]:checked')?.value ?? next.mode;
  next.notifications.enabled = el('notify-enabled').checked;
  next.notifications.time = el('notify-time').value || next.notifications.time;

  if (next.car.targetSocPct < next.car.currentSocPct) {
    next.car.targetSocPct = next.car.currentSocPct;
  }
  return next;
}

/* ------------------------------------------------------------------ */
/* Data + planning                                                     */
/* ------------------------------------------------------------------ */

async function refresh({ force = false } = {}) {
  if (state.loading) return;
  state.loading = true;
  el('refresh').classList.add('is-busy');
  setStatus('Gegevens ophalen…');
  try {
    state.data = await loadData(state.settings, { now: new Date(), force });
  } catch (err) {
    state.data = { prices: [], solar: { hours: [], days: [] }, stale: false, errors: [err] };
  } finally {
    state.loading = false;
    el('refresh').classList.remove('is-busy');
  }
  recompute();
}

function recompute() {
  const now = new Date();
  const data = state.data ?? { prices: [], solar: { hours: [], days: [] }, errors: [] };
  state.timeline = buildTimeline(data.prices, data.solar.hours, state.settings);
  state.plan = planCharging({ timeline: state.timeline, settings: state.settings, now });
  render(now);
  maybeNotify(now);
}

function render(now = new Date()) {
  const data = state.data ?? { prices: [], solar: { hours: [], days: [] }, errors: [], stale: false };

  const messages = [];
  for (const err of data.errors ?? []) {
    messages.push({ level: 'error', text: `${err.message}` });
  }
  if (data.stale) {
    messages.push({ level: 'warn', text: 'Je ziet opgeslagen gegevens van eerder. Ververs zodra je weer internet hebt.' });
  }
  if (!hasTomorrowPrices(data.prices, now) && now.getHours() >= 15) {
    messages.push({
      level: 'info',
      text: 'De prijzen voor morgen staan nog niet online. Ze verschijnen meestal rond 15:00 uur.',
    });
  }
  for (const warning of state.plan?.warnings ?? []) {
    messages.push({ level: 'warn', text: warning });
  }
  ui.renderMessages(el('messages'), messages);

  ui.renderHero({
    windowEl: el('hero-window'),
    subEl: el('hero-sub'),
    statsEl: el('hero-stats'),
    plan: state.plan,
    now,
  });
  ui.renderExtraBlocks({
    card: el('extra-blocks'),
    list: el('extra-blocks-list'),
    plan: state.plan,
    now,
  });
  ui.renderSolarDays({
    container: el('solar-days'),
    noteEl: el('solar-note'),
    days: data.solar.days,
    summary: solarSummary(state.timeline),
    settings: state.settings,
  });
  ui.renderSavings({ container: el('savings'), plan: state.plan });
  ui.renderChart({
    container: el('chart'),
    legendEl: el('chart-legend'),
    readoutEl: el('chart-readout'),
    timeline: state.timeline,
    plan: state.plan,
    now,
  });
  ui.renderHoursTable({ table: el('hours-table'), timeline: state.timeline, plan: state.plan, now });

  const hasPlan = Boolean(state.plan?.blocks?.length);
  el('add-calendar').disabled = !hasPlan;
  el('share-plan').disabled = !hasPlan;
  el('location-name').textContent = state.settings.location.name || 'je locatie';

  setStatus(
    data.prices.length
      ? `Bijgewerkt ${ui.clock(data.fetchedAt ?? now)} · ${data.prices.length} uurprijzen · ${MODES[state.settings.mode].label}`
      : 'Geen prijsgegevens beschikbaar',
  );
}

function hasTomorrowPrices(prices, now) {
  const tomorrow = new Date(now);
  tomorrow.setDate(tomorrow.getDate() + 1);
  tomorrow.setHours(12, 0, 0, 0);
  return (prices ?? []).some((p) => Math.abs(p.start - tomorrow) < 13 * 3600000 && p.start > now);
}

function setStatus(text) {
  el('status-line').textContent = text;
}

/* ------------------------------------------------------------------ */
/* Sharing                                                             */
/* ------------------------------------------------------------------ */

function planAsText() {
  return briefingText(state.plan, state.settings, { now: new Date() });
}

async function copyText(text, okMessage, statusFn = setStatus) {
  try {
    await navigator.clipboard.writeText(text);
    statusFn(okMessage);
    return true;
  } catch {
    statusFn('Kopiëren lukte niet. Selecteer de tekst en kopieer hem met de hand.');
    return false;
  }
}

async function sharePlan() {
  const text = planAsText();
  if (navigator.share) {
    try {
      await navigator.share({ title: 'Laadmoment', text });
      return;
    } catch (err) {
      // A cancelled share is not a failure; anything else falls through.
      if (err?.name === 'AbortError') return;
    }
  }
  await copyText(text, 'Plan gekopieerd naar het klembord.');
}

/* ------------------------------------------------------------------ */
/* Morning notification, in the app                                    */
/* ------------------------------------------------------------------ */

function notifyStatus(text) {
  el('notify-status').textContent = text;
}

async function toggleNotifications(enabled) {
  state.settings.notifications.enabled = enabled;
  if (!enabled) {
    saveSettings(state.settings);
    notifyStatus('Melding bij openen staat uit.');
    return;
  }
  if (!('Notification' in window)) {
    state.settings.notifications.enabled = false;
    el('notify-enabled').checked = false;
    saveSettings(state.settings);
    notifyStatus('Deze browser kan geen meldingen tonen. Gebruik de agenda-knop.');
    return;
  }
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') {
    state.settings.notifications.enabled = false;
    el('notify-enabled').checked = false;
    saveSettings(state.settings);
    notifyStatus('Meldingen zijn geweigerd. Zet ze aan in de instellingen van je telefoon.');
    return;
  }
  saveSettings(state.settings);
  await registerPeriodicSync();
  notifyStatus('Aan. Je ziet het plan zodra je de app na dit tijdstip opent.');
}

async function registerPeriodicSync() {
  try {
    const registration = await navigator.serviceWorker?.ready;
    if (!registration?.periodicSync) return;
    const status = await navigator.permissions.query({ name: 'periodic-background-sync' });
    if (status.state !== 'granted') return;
    await registration.periodicSync.register('ochtendplan', { minInterval: 12 * 3600 * 1000 });
  } catch {
    /* not supported everywhere; the in-app briefing still works */
  }
}

/**
 * Show the briefing once per day, the first time the app is opened after the
 * chosen time. This is the fallback for devices that cannot receive a push
 * message; the notifier covers the case where the app stays closed.
 */
function maybeNotify(now) {
  const settings = state.settings;
  if (!settings.notifications.enabled) return;
  if (!state.plan?.blocks?.length) return;

  const [h, m] = settings.notifications.time.split(':').map(Number);
  const trigger = new Date(now);
  trigger.setHours(h || 0, m || 0, 0, 0);
  if (now < trigger) return;

  const today = now.toDateString();
  if (settings.lastBriefingDate === today) return;

  settings.lastBriefingDate = today;
  saveSettings(settings);

  const { title, body } = briefingNotification(state.plan, { now });
  const options = { body, icon: 'icons/icon-192.png', tag: 'laadmoment', renotify: false };

  try {
    if ('Notification' in window && Notification.permission === 'granted') {
      new Notification(title, options);
    }
  } catch {
    // Some browsers only allow notifications from the service worker.
    navigator.serviceWorker?.ready
      .then((reg) => reg.showNotification(title, options))
      .catch(() => {});
  }
}

/* ------------------------------------------------------------------ */
/* Push notification, from the notifier                                */
/* ------------------------------------------------------------------ */

function pushStatus(text) {
  el('push-status').textContent = text;
}

function showSubscription(subscription) {
  const field = el('push-subscription-field');
  const row = el('push-copy-row');
  const box = el('push-subscription');
  if (!subscription) {
    field.hidden = true;
    row.hidden = true;
    box.value = '';
    return;
  }
  box.value = JSON.stringify(subscription);
  field.hidden = false;
  row.hidden = false;
}

async function refreshPushState() {
  const support = pushSupport();
  const subscription = support.supported ? await currentSubscription() : null;
  showSubscription(subscription);
  el('push-subscribe').disabled = !support.supported;
  el('push-unsubscribe').disabled = !subscription;

  if (!support.supported) {
    pushStatus(support.reason);
    return;
  }
  pushStatus(
    subscription
      ? 'Aangemeld. Plak de code hieronder als GitHub-secret PUSH_SUBSCRIPTION.'
      : 'Nog niet aangemeld voor pushmeldingen.',
  );
}

async function handleSubscribe() {
  const key = el('vapid-key').value.trim();
  state.settings.notifications.vapidPublicKey = key;
  saveSettings(state.settings);
  pushStatus('Bezig met aanmelden…');
  try {
    const subscription = await subscribeToPush(key);
    showSubscription(subscription);
    el('push-unsubscribe').disabled = false;
    pushStatus('Gelukt. Plak de code hieronder als GitHub-secret PUSH_SUBSCRIPTION.');
  } catch (err) {
    showSubscription(null);
    pushStatus(err.message);
  }
}

async function handleUnsubscribe() {
  try {
    await unsubscribeFromPush();
  } catch {
    /* already gone is fine */
  }
  await refreshPushState();
  pushStatus('Afgemeld. Verwijder het secret PUSH_SUBSCRIPTION als je geen meldingen meer wilt.');
}

/* ------------------------------------------------------------------ */
/* Settings export and import                                          */
/* ------------------------------------------------------------------ */

function exportStatus(text) {
  el('export-status').textContent = text;
}

/** The shape tools/notify.js expects in notify-config.json. */
function exportableSettings() {
  const { lastBriefingDate, ...rest } = state.settings;
  return JSON.stringify(rest, null, 2);
}

async function handleExportSettings() {
  await copyText(
    exportableSettings(),
    'Instellingen gekopieerd. Plak ze in notify-config.json.',
    exportStatus,
  );
}

async function handleImportSettings() {
  let text = '';
  try {
    text = await navigator.clipboard.readText();
  } catch {
    text = window.prompt('Plak hier de inhoud van notify-config.json') ?? '';
  }
  if (!text.trim()) {
    exportStatus('Niets geplakt.');
    return;
  }
  try {
    state.settings = mergeSettings(defaultSettings(), JSON.parse(text));
    saveSettings(state.settings);
    fillForm();
    exportStatus('Instellingen overgenomen.');
    refresh({ force: true });
  } catch (err) {
    exportStatus(`Dat was geen geldige JSON: ${err.message}`);
  }
}

/* ------------------------------------------------------------------ */
/* Start                                                               */
/* ------------------------------------------------------------------ */

function bind() {
  for (const tab of document.querySelectorAll('.tab')) {
    tab.addEventListener('click', () => showView(tab.dataset.view));
  }

  el('refresh').addEventListener('click', () => refresh({ force: true }));
  el('add-calendar').addEventListener('click', () => state.plan && downloadIcs(state.plan));
  el('share-plan').addEventListener('click', sharePlan);
  el('notify-enabled').addEventListener('change', (event) => toggleNotifications(event.target.checked));
  el('notify-time').addEventListener('change', (event) => {
    state.settings.notifications.time = event.target.value || '06:45';
    saveSettings(state.settings);
  });

  el('push-subscribe').addEventListener('click', handleSubscribe);
  el('push-unsubscribe').addEventListener('click', handleUnsubscribe);
  el('push-copy').addEventListener('click', () =>
    copyText(el('push-subscription').value, 'Aanmelding gekopieerd.', pushStatus),
  );
  el('vapid-key').addEventListener('change', (event) => {
    state.settings.notifications.vapidPublicKey = event.target.value.trim();
    saveSettings(state.settings);
  });

  el('export-settings').addEventListener('click', handleExportSettings);
  el('import-settings').addEventListener('click', handleImportSettings);

  el('add-array').addEventListener('click', () => {
    const next = readForm();
    next.pv.arrays.push({
      id: `a${next.pv.arrays.length + 1}`,
      label: `Vlak ${next.pv.arrays.length + 1}`,
      kWp: 2,
      tilt: 35,
      azimuth: 90,
    });
    state.settings = next;
    saveSettings(state.settings);
    renderArrays();
  });

  el('settings-form').addEventListener('submit', (event) => {
    event.preventDefault();
    state.settings = readForm();
    saveSettings(state.settings);
    el('settings-status').textContent = `Opgeslagen om ${ui.clock(new Date())}.`;
    fillForm();
    refresh({ force: true }).then(() => showView('vandaag'));
  });

  el('reset-settings').addEventListener('click', () => {
    state.settings = defaultSettings();
    saveSettings(state.settings);
    fillForm();
    el('settings-status').textContent = 'Standaardwaarden hersteld.';
    refresh({ force: true });
  });

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') recompute();
  });
}

async function start() {
  bind();
  fillForm();
  const stored = (() => {
    try {
      return sessionStorage.getItem('laadmoment.view');
    } catch {
      return null;
    }
  })();
  showView(stored ?? 'vandaag');
  await refresh();

  if ('serviceWorker' in navigator) {
    try {
      await navigator.serviceWorker.register('sw.js');
    } catch (err) {
      console.warn('Service worker niet geregistreerd:', err);
    }
  }
  // Only meaningful once the service worker is up. Deliberately not awaited:
  // without a service worker this settles late, and the timer below should
  // not wait for it.
  refreshPushState();
  // Keep clocks and the "now" marker honest while the app stays open.
  setInterval(() => recompute(), 5 * 60 * 1000);
}

start();
