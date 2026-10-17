/**
 * Bootstrap and wiring.
 *
 * Flow: settings from localStorage, everything else from IndexedDB, render
 * what is cached, then fetch the latest encrypted bundle from GitHub. The
 * coach talks to Claude straight from the phone.
 */

import { MODELS, loadSettings, saveSettings, repoFromLocation, defaultSettings, mergeSettings } from './config.js';
import { KEYS, openStore } from './store.js';
import { CryptoError, generateKey } from './crypto.js';
import { SPORTS, dataSource, fetchEnvelope, loadBundle, mergeActivities, wellnessInRange, wellnessOn } from './data.js';
import { FIELDS, KINDS, QUICK_ADDS, createEntry, defaultKind, entriesOn, totals } from './nutrition.js';
import {
  buildRequest,
  conversationView,
  createToolbox,
  newConversation,
  runTurn,
  userMessage,
} from './coach.js';
import { createClient, describeError, estimateIntake, prepareImage } from './claude.js';
import { triggerSync } from './github.js';
import { addDays, formatDay, formatDayLong, localDate, localTime, timeAgo, weekStart } from './format.js';
import * as ui from './ui.js';

const el = (id) => document.getElementById(id);

const state = {
  settings: loadSettings(),
  store: null,
  cached: null, // { bundle, t }
  activities: [],
  entries: [],
  memory: [],
  conversation: null,
  dataError: null,
  loading: false,
  view: 'vandaag',
  activityFilter: 'all',
  expandedActivity: null,
  nutritionDay: localDate(),
  intakePhoto: null,
  intakeEstimate: null,
  chatPhoto: null,
  chat: { busy: false, live: null, error: null, controller: null },
  claude: null, // { key, session }
  compat: new Set(),
};

const SUGGESTIONS = [
  { label: 'Geef me mijn dagadvies', prompt: 'Geef me een kort dagadvies voor vandaag: hoe sta ik ervoor qua herstel, en wat raad je aan voor training, eten en slapen?' },
  { label: 'Analyseer mijn laatste training', prompt: 'Analyseer mijn laatste training. Wat ging goed en wat kan beter?' },
  { label: 'Hoe is mijn herstel deze week?', prompt: 'Hoe is mijn herstel deze week (slaap, HRV, rusthartslag) vergeleken met de weken ervoor?' },
  { label: 'Plan mijn trainingen voor volgende week', prompt: 'Maak een trainingsplan voor volgende week dat past bij mijn doelen en mijn huidige belasting.' },
  { label: 'Hoe was mijn eten en drinken vandaag?', prompt: 'Hoe was mijn eten en drinken vandaag, en wat kan ik de rest van de dag nog het beste eten en drinken?' },
];

/* ------------------------------------------------------------------ */
/* Small helpers                                                       */
/* ------------------------------------------------------------------ */

let toastTimer;
function toast(text, ms = 3500) {
  const box = el('toast');
  box.textContent = text;
  box.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    box.hidden = true;
  }, ms);
}

function bundle() {
  return state.cached?.bundle ?? null;
}

function save(key, value) {
  return state.store.set(key, value).catch((err) => {
    console.error(err);
    toast('Opslaan op dit toestel mislukt.');
  });
}

const saveEntries = () => save(KEYS.nutrition, state.entries);
const saveMemory = () => save(KEYS.memory, state.memory);
const saveConversation = () => save(KEYS.conversation, state.conversation);

function newId() {
  return crypto.randomUUID?.() ?? String(Date.now());
}

/* ------------------------------------------------------------------ */
/* Navigation                                                          */
/* ------------------------------------------------------------------ */

function showView(name) {
  state.view = name;
  for (const view of document.querySelectorAll('.view')) view.hidden = view.id !== `view-${name}`;
  for (const tab of document.querySelectorAll('.tab')) {
    const active = tab.dataset.view === name;
    tab.classList.toggle('is-active', active);
    tab.setAttribute('aria-selected', active ? 'true' : 'false');
  }
  window.scrollTo({ top: name === 'coach' ? document.body.scrollHeight : 0 });
  try {
    sessionStorage.setItem('coach.view', name);
  } catch {
    /* remembering the tab is optional */
  }
  if (name === 'coach') renderChat();
}

/* ------------------------------------------------------------------ */
/* Data                                                                */
/* ------------------------------------------------------------------ */

function setStatus() {
  const b = bundle();
  let text;
  if (state.loading) text = 'Gegevens ophalen…';
  else if (b) {
    const failed = ['strava', 'garmin'].filter((s) => b.sources?.[s]?.configured && !b.sources[s].ok);
    text = `Gesynchroniseerd ${timeAgo(b.generatedAt)}${failed.length ? ` · ${failed.map((s) => (s === 'strava' ? 'Strava' : 'Garmin')).join(' en ')} mislukt` : ''}`;
  } else if (state.dataError) text = 'Geen gegevens';
  else text = 'Nog niet gekoppeld';
  el('status-line').textContent = text;
  el('refresh').classList.toggle('is-busy', state.loading);
}

async function refreshData({ quiet = false } = {}) {
  const sync = state.settings.sync;
  if (!sync.key || !dataSource(sync)) {
    state.dataError = null;
    renderAll();
    return;
  }
  state.loading = true;
  setStatus();
  try {
    const result = await loadBundle({ sync, cached: state.cached });
    if (result.changed) {
      state.cached = { bundle: result.bundle, t: result.t };
      state.activities = mergeActivities(result.bundle);
      await save(KEYS.bundle, state.cached);
      if (!quiet) toast('Nieuwe gegevens binnen.');
    } else if (!quiet) {
      toast('Alles is bijgewerkt.');
    }
    state.dataError = null;
  } catch (err) {
    state.dataError = err instanceof CryptoError ? 'De gegevens konden niet ontsleuteld worden. Is de synchronisatiesleutel hier dezelfde als in GitHub?' : err.message;
    if (!quiet) toast(state.dataError, 6000);
  } finally {
    state.loading = false;
    renderAll();
  }
}

async function syncNow() {
  const button = el('sync-now');
  button.disabled = true;
  el('sync-status').textContent = 'Workflow starten…';
  try {
    await triggerSync(state.settings.sync);
    el('sync-status').textContent = 'Gestart. Over een paar minuten zijn de nieuwe gegevens er.';
    toast('Synchronisatie gestart.');
    const before = state.cached?.t;
    const source = dataSource(state.settings.sync);
    for (let i = 0; i < 18; i += 1) {
      await new Promise((resolve) => setTimeout(resolve, 20000));
      const envelope = await fetchEnvelope(source).catch(() => null);
      if (envelope && envelope.t !== before) {
        await refreshData({ quiet: true });
        el('sync-status').textContent = `Bijgewerkt ${timeAgo(bundle()?.generatedAt)}.`;
        toast('Nieuwe gegevens binnen.');
        return;
      }
    }
    el('sync-status').textContent = 'Nog geen nieuwe gegevens. Kijk in GitHub bij Actions of de workflow goed ging.';
  } catch (err) {
    el('sync-status').textContent = err.message;
    toast(err.message, 6000);
  } finally {
    button.disabled = false;
  }
}

/* ------------------------------------------------------------------ */
/* Rendering                                                           */
/* ------------------------------------------------------------------ */

function notices() {
  const list = [];
  if (state.dataError) list.push({ level: 'error', text: state.dataError });
  const b = bundle();
  for (const [key, name] of [['strava', 'Strava'], ['garmin', 'Garmin']]) {
    const source = b?.sources?.[key];
    if (source?.configured && !source.ok) list.push({ level: 'warn', text: `${name}: laatste synchronisatie mislukt. ${source.error ?? ''}` });
  }
  if (b && Date.now() - Date.parse(b.generatedAt) > 8 * 3600 * 1000) {
    list.push({ level: 'warn', text: `De gegevens zijn van ${timeAgo(b.generatedAt)}. Draait de workflow "Coach synchroniseren" nog?` });
  }
  return list;
}

function renderToday() {
  const today = localDate();
  const b = bundle();
  ui.renderNotices(el('notices'), notices());
  ui.renderSetup(el('setup-card'), el('setup-list'), [
    { label: 'Claude API-sleutel ingevuld', done: Boolean(state.settings.claude.apiKey) },
    { label: 'Synchronisatiesleutel ingevuld', done: Boolean(state.settings.sync.key) },
    { label: 'Strava- of Garmin-gegevens gevonden', done: Boolean(b) },
  ]);

  const day = wellnessOn(b, today);
  const yesterday = wellnessOn(b, addDays(today, -1));
  // Early in the morning today's record may still be nearly empty.
  const shown = day && Object.keys(day).length > 3 ? day : (yesterday ?? day);
  ui.renderRecovery({ grid: el('recovery-grid'), empty: el('recovery-empty'), dateEl: el('recovery-date') }, b?.garmin ? shown : null);
  ui.renderMovement(
    { grid: el('movement-grid'), meta: el('movement-meta'), strip: el('week-strip'), last: el('last-activity') },
    {
      activities: state.activities,
      today,
      todayWellness: day,
      weekWellness: wellnessInRange(b, weekStart(today), today),
      status: b?.garmin?.status,
    },
  );
  ui.renderIntakeBars(el('intake-bars'), totals(entriesOn(state.entries, today)), state.settings.targets);
}

function renderActivities() {
  const present = new Set(state.activities.map((a) => a.sport));
  const filters = [{ key: 'all', label: 'Alles' }, ...Object.entries(SPORTS).filter(([key]) => present.has(key)).map(([key, s]) => ({ key, label: s.label }))];
  ui.renderChips(el('sport-filter'), filters, { active: state.activityFilter });
  ui.renderActivities(el('activity-list'), el('activity-empty'), state.activities, {
    filter: state.activityFilter,
    expanded: state.expandedActivity,
  });
}

function renderNutrition() {
  const day = state.nutritionDay;
  const today = localDate();
  el('day-label').textContent = day === today ? 'Vandaag' : day === addDays(today, -1) ? 'Gisteren' : formatDayLong(day);
  el('day-next').disabled = day >= today;
  const entries = entriesOn(state.entries, day);
  ui.renderIntakeBars(el('day-bars'), totals(entries), state.settings.targets);
  ui.renderEntries(el('entry-list'), el('entry-empty'), entries);
}

function renderMemory() {
  ui.renderMemory(el('memory-list'), el('memory-empty'), [...state.memory].reverse());
}

function renderAll() {
  setStatus();
  renderToday();
  renderActivities();
  renderNutrition();
  renderMemory();
  renderChatNote();
  if (state.view === 'coach') renderChat();
}

/* ------------------------------------------------------------------ */
/* Food log                                                            */
/* ------------------------------------------------------------------ */

async function addEntry(entry) {
  state.entries.push(entry);
  await saveEntries();
  renderToday();
  renderNutrition();
}

async function quickAdd(item, day) {
  const now = new Date();
  const time = day === localDate(now) ? localTime(now) : '12:00';
  const { label, ...fields } = item;
  await addEntry(createEntry({ ...fields, date: day, time }, { now }));
  toast(`${label} toegevoegd.`);
}

function readIntakeForm() {
  const fields = {};
  for (const key of Object.keys(FIELDS)) {
    const value = el(`field-${key}`).value;
    if (value !== '') fields[key] = value;
  }
  return {
    description: el('intake-description').value,
    kind: el('intake-kind').value,
    time: el('intake-time').value,
    ...fields,
  };
}

function resetIntakeForm() {
  el('intake-form').reset();
  el('intake-time').value = localTime();
  el('intake-kind').value = defaultKind(el('intake-time').value);
  el('intake-preview').hidden = true;
  el('intake-numbers').open = false;
  state.intakePhoto = null;
  state.intakeEstimate = null;
  ui.renderEstimate(el('intake-estimate-result'), null);
}

async function getClaude() {
  const key = state.settings.claude.apiKey.trim();
  if (state.claude?.key !== key) state.claude = { key, session: await createClient(key) };
  return state.claude.session;
}

async function estimate() {
  const button = el('intake-estimate');
  const input = readIntakeForm();
  if (!input.description.trim() && !state.intakePhoto) {
    toast('Beschrijf eerst wat je at of dronk, of kies een foto.');
    return;
  }
  button.disabled = true;
  button.textContent = 'Schatten…';
  let session;
  try {
    session = await getClaude();
    const result = await estimateIntake({
      client: session.client,
      model: state.settings.claude.model,
      text: input.description.trim(),
      image: state.intakePhoto,
      time: input.time,
    });
    state.intakeEstimate = result;
    if (!input.description.trim()) el('intake-description').value = result.description;
    if (KINDS[result.kind]) el('intake-kind').value = result.kind;
    for (const key of Object.keys(FIELDS)) {
      const value = result[key];
      el(`field-${key}`).value = Number.isFinite(value) && value > 0 ? String(Math.round(value * 10) / 10) : '';
    }
    el('intake-numbers').open = true;
    ui.renderEstimate(el('intake-estimate-result'), result);
  } catch (err) {
    toast(describeError(err, session?.sdk), 6000);
  } finally {
    button.disabled = false;
    button.textContent = 'Schat met AI';
  }
}

async function submitIntake(event) {
  event.preventDefault();
  try {
    const input = readIntakeForm();
    const estimateUsed = state.intakeEstimate;
    const entry = createEntry(
      {
        ...input,
        date: state.nutritionDay,
        estimate: estimateUsed ? { confidence: estimateUsed.confidence, note: estimateUsed.note, items: estimateUsed.items } : undefined,
      },
      { source: estimateUsed ? 'ai' : 'hand' },
    );
    await addEntry(entry);
    resetIntakeForm();
    toast('Opgeslagen.');
  } catch (err) {
    toast(err.message);
  }
}

/* ------------------------------------------------------------------ */
/* Coach                                                               */
/* ------------------------------------------------------------------ */

const toolbox = createToolbox({
  getBundle: bundle,
  getEntries: () => state.entries,
  addEntry,
  addMemory: async (text) => {
    const item = { id: newId(), text, date: localDate() };
    state.memory.push(item);
    await saveMemory();
    renderMemory();
    return item;
  },
  getSettings: () => state.settings,
});

/**
 * A conversation that was cut off between a tool call and its result (app
 * closed mid-turn) is closed with error results, so the next request is
 * valid. Appending keeps the history append-only.
 */
function repairConversation() {
  const messages = state.conversation?.messages;
  const last = messages?.at(-1);
  if (last?.role !== 'assistant' || !Array.isArray(last.content)) return;
  const pending = last.content.filter((b) => b.type === 'tool_use');
  if (!pending.length) return;
  messages.push({
    role: 'user',
    content: pending.map((b) => ({ type: 'tool_result', tool_use_id: b.id, is_error: true, content: 'Onderbroken: de app werd gesloten.' })),
  });
}

function renderChatNote() {
  const conv = state.conversation;
  const note = el('chat-note');
  if (!conv?.messages.length) {
    note.textContent = 'De coach kent je Strava-, Garmin- en voedingsgegevens.';
    return;
  }
  const started = localDate(new Date(conv.createdAt));
  note.textContent =
    started === localDate()
      ? `Vandaag · ${(MODELS.find((m) => m.id === state.settings.claude.model)?.label ?? '').replace('Claude ', '')}`
      : `Gesprek van ${formatDay(started)}. Begin een nieuw gesprek voor een verse samenvatting.`;
}

let chatFrame = 0;
function renderChat() {
  cancelAnimationFrame(chatFrame);
  // Another tab is open: showView('coach') renders when the user comes back.
  if (state.view !== 'coach') return;
  chatFrame = requestAnimationFrame(() => {
    const nearBottom = window.innerHeight + window.scrollY >= document.body.scrollHeight - 160;
    const messages = state.conversation?.messages ?? [];
    const last = messages.at(-1);
    ui.renderChat(el('chat-log'), conversationView(messages), {
      live: state.chat.live,
      error: state.chat.error,
      canRetry: !state.chat.busy && last?.role === 'user',
    });
    const empty = !messages.length && !state.chat.busy;
    el('chat-suggestions').hidden = !empty;
    if (empty) ui.renderSuggestions(el('chat-suggestions'), SUGGESTIONS);
    el('chat-send').hidden = state.chat.busy;
    el('chat-stop').hidden = !state.chat.busy;
    renderChatNote();
    if (nearBottom || state.chat.busy) window.scrollTo({ top: document.body.scrollHeight });
  });
}

function onChatEvent(event) {
  const live = state.chat.live;
  if (!live) return;
  const lastPart = live.parts.at(-1);
  switch (event.type) {
    case 'text':
    case 'update': {
      const key = `${live.segment}:${event.index}`;
      if (lastPart?.type === event.type && lastPart.key === key) lastPart.text += event.text;
      else live.parts.push({ type: event.type, key, text: event.text });
      break;
    }
    case 'tool_start':
      live.parts.push({ type: 'tool', name: event.name, pending: true });
      break;
    case 'tool_done': {
      const part = live.parts.find((p) => p.type === 'tool' && p.pending && p.name === event.name);
      if (part) Object.assign(part, { pending: false, summary: event.summary });
      if (event.effect?.type === 'nutrition') toast(`In je voedingslog: ${event.effect.entry.description}`);
      if (event.effect?.type === 'memory') toast('De coach heeft dit onthouden.');
      break;
    }
    case 'assistant':
      live.segment += 1;
      break;
    case 'fallback':
      live.parts.push({ type: 'note', text: `Overgenomen door ${event.to ?? 'een ander model'}.` });
      break;
    default:
      break;
  }
  renderChat();
}

async function continueChat() {
  let session;
  try {
    session = await getClaude();
  } catch (err) {
    state.chat.error = err.message;
    renderChat();
    return;
  }
  const conv = state.conversation;
  const model = state.settings.claude.model;
  const compat = state.compat.has(model);
  state.chat = { busy: true, live: { parts: [], segment: 0 }, error: null, controller: new AbortController() };
  renderChat();
  try {
    const result = await runTurn({
      client: session.client,
      isApiError: session.isApiError,
      isBadRequest: session.isBadRequest,
      request: buildRequest({ model, system: conv.system, compat }),
      compatRequest: compat ? null : buildRequest({ model, system: conv.system, compat: true }),
      messages: conv.messages,
      execute: toolbox,
      append: saveConversation,
      onEvent: onChatEvent,
      signal: state.chat.controller.signal,
    });
    if (result.compat) state.compat.add(model);
    if (result.stopReason === 'refusal') state.chat.error = 'Hier kon Claude niet op ingaan. Probeer het anders te formuleren.';
    if (result.stopReason === 'max_steps') state.chat.error = 'De coach had te veel stappen nodig. Stel de vraag iets specifieker.';
  } catch (err) {
    state.chat.error = describeError(err, session.sdk);
  } finally {
    state.chat.busy = false;
    state.chat.live = null;
    state.chat.controller = null;
    renderChat();
  }
}

async function sendChat(text) {
  if (state.chat.busy) return;
  const clean = String(text ?? '').trim();
  if (!clean && !state.chatPhoto) return;
  if (!state.settings.claude.apiKey.trim()) {
    state.chat.error = 'Vul eerst bij Instellingen je Claude API-sleutel in.';
    renderChat();
    return;
  }
  if (!state.conversation) {
    state.conversation = newConversation({ settings: state.settings, bundle: bundle(), entries: state.entries, memory: state.memory });
  }
  const images = state.chatPhoto ? [state.chatPhoto] : [];
  state.conversation.messages.push(userMessage(clean || 'Wat zie je op deze foto?', { images }));
  state.chatPhoto = null;
  el('chat-preview').hidden = true;
  el('chat-input').value = '';
  autosize();
  await saveConversation();
  await continueChat();
}

function askCoach(prompt) {
  showView('coach');
  sendChat(prompt);
}

async function newChat() {
  if (state.chat.busy) return;
  if (state.conversation?.messages.length && !confirm('Een nieuw gesprek beginnen? Dit gesprek wordt gewist.')) return;
  state.conversation = null;
  state.chat.error = null;
  await state.store.delete(KEYS.conversation);
  renderChat();
}

function autosize() {
  const input = el('chat-input');
  input.style.height = 'auto';
  input.style.height = `${Math.min(input.scrollHeight, 160)}px`;
}

/* ------------------------------------------------------------------ */
/* Settings                                                            */
/* ------------------------------------------------------------------ */

function getPath(object, path) {
  return path.split('.').reduce((acc, key) => (acc == null ? acc : acc[key]), object);
}

function setPath(object, path, value) {
  const keys = path.split('.');
  const last = keys.pop();
  const target = keys.reduce((acc, key) => (acc[key] ??= {}), object);
  target[last] = value;
}

function fillSettingsForm() {
  for (const input of document.querySelectorAll('[data-path]')) {
    const value = getPath(state.settings, input.dataset.path);
    input.value = value ?? '';
  }
  const repo = repoFromLocation();
  el('sync-owner').placeholder = repo?.owner ?? 'gebruikersnaam';
  el('sync-repo').placeholder = repo?.repo ?? 'App-lading-car';
}

let settingsTimer;
function onSettingsInput(event) {
  const input = event.target.closest('[data-path]');
  if (!input) return;
  let value = input.value;
  if (input.dataset.type === 'number') {
    const n = Number(String(value).replace(',', '.'));
    value = value === '' || !Number.isFinite(n) ? null : n;
  } else {
    value = value.trim() === '' ? '' : value;
  }
  setPath(state.settings, input.dataset.path, value);
  saveSettings(state.settings);
  clearTimeout(settingsTimer);
  const path = input.dataset.path;
  settingsTimer = setTimeout(() => {
    if (path.startsWith('sync.') && event.type === 'change') refreshData({ quiet: false });
    renderAll();
  }, 400);
}

async function exportBackup() {
  // Secrets stay out of the file: a backup tends to end up in a mailbox.
  const settings = structuredClone(state.settings);
  settings.claude.apiKey = '';
  settings.sync.key = '';
  settings.sync.githubToken = '';
  const backup = {
    app: 'coach',
    version: 1,
    exportedAt: new Date().toISOString(),
    settings,
    entries: state.entries,
    memory: state.memory,
    conversation: state.conversation,
  };
  const blob = new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `coach-backup-${localDate()}.json`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(link.href), 5000);
}

async function importBackup(file) {
  try {
    const backup = JSON.parse(await file.text());
    if (backup?.app !== 'coach') throw new Error('Dit is geen back-up van Coach.');
    const known = new Set(state.entries.map((e) => e.id));
    state.entries.push(...(backup.entries ?? []).filter((e) => e?.id && !known.has(e.id)));
    const memoryIds = new Set(state.memory.map((m) => m.id));
    state.memory.push(...(backup.memory ?? []).filter((m) => m?.id && !memoryIds.has(m.id)));
    const secrets = { claude: { apiKey: state.settings.claude.apiKey }, sync: { key: state.settings.sync.key, githubToken: state.settings.sync.githubToken } };
    state.settings = mergeSettings(mergeSettings(defaultSettings(), backup.settings), secrets);
    if (!state.conversation && backup.conversation?.messages) state.conversation = backup.conversation;
    saveSettings(state.settings);
    await Promise.all([saveEntries(), saveMemory(), state.conversation ? saveConversation() : null]);
    fillSettingsForm();
    renderAll();
    toast('Back-up teruggezet.');
  } catch (err) {
    toast(err.message || 'Back-up niet te lezen.');
  }
}

async function wipe() {
  if (!confirm('Alles van dit toestel wissen: instellingen, sleutels, voedingslog en gesprekken?')) return;
  for (const key of Object.values(KEYS)) await state.store.delete(key);
  localStorage.clear();
  location.reload();
}

/* ------------------------------------------------------------------ */
/* Events                                                              */
/* ------------------------------------------------------------------ */

function bindEvents() {
  for (const tab of document.querySelectorAll('.tab')) tab.addEventListener('click', () => showView(tab.dataset.view));
  document.addEventListener('click', (event) => {
    const goto = event.target.closest('[data-goto]');
    if (goto) showView(goto.dataset.goto);
  });
  el('refresh').addEventListener('click', () => refreshData());

  el('ask-suggestions').addEventListener('click', (event) => {
    const button = event.target.closest('[data-index]');
    if (button) askCoach(SUGGESTIONS[Number(button.dataset.index)].prompt);
  });
  el('quick-today').addEventListener('click', (event) => {
    const chip = event.target.closest('[data-index]');
    if (chip) quickAdd(QUICK_ADDS[Number(chip.dataset.index)], localDate());
  });

  el('sport-filter').addEventListener('click', (event) => {
    const chip = event.target.closest('[data-key]');
    if (!chip) return;
    state.activityFilter = chip.dataset.key;
    renderActivities();
  });
  el('activity-list').addEventListener('click', (event) => {
    const ask = event.target.closest('[data-ask]');
    if (ask) {
      const activity = state.activities.find((a) => a.id === ask.dataset.ask);
      if (activity) askCoach(`Analyseer mijn activiteit "${activity.name}" van ${formatDay(activity.date)} (id ${activity.id}).`);
      return;
    }
    if (event.target.closest('table')) return;
    const card = event.target.closest('[data-id]');
    if (!card) return;
    state.expandedActivity = state.expandedActivity === card.dataset.id ? null : card.dataset.id;
    renderActivities();
  });

  el('day-prev').addEventListener('click', () => {
    state.nutritionDay = addDays(state.nutritionDay, -1);
    renderNutrition();
  });
  el('day-next').addEventListener('click', () => {
    if (state.nutritionDay < localDate()) state.nutritionDay = addDays(state.nutritionDay, 1);
    renderNutrition();
  });
  el('quick-adds').addEventListener('click', (event) => {
    const chip = event.target.closest('[data-index]');
    if (chip) quickAdd(QUICK_ADDS[Number(chip.dataset.index)], state.nutritionDay);
  });
  el('entry-list').addEventListener('click', async (event) => {
    const button = event.target.closest('[data-delete]');
    if (!button || !confirm('Dit item verwijderen?')) return;
    state.entries = state.entries.filter((e) => e.id !== button.dataset.delete);
    await saveEntries();
    renderToday();
    renderNutrition();
  });
  el('intake-form').addEventListener('submit', submitIntake);
  el('intake-estimate').addEventListener('click', estimate);
  el('intake-photo').addEventListener('change', async (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      state.intakePhoto = await prepareImage(file);
      el('intake-preview').src = state.intakePhoto.preview;
      el('intake-preview').hidden = false;
    } catch {
      toast('Deze foto kon niet gelezen worden.');
    }
    event.target.value = '';
  });
  el('intake-description').addEventListener('input', () => {
    // A new description makes an earlier estimate meaningless.
    if (state.intakeEstimate) {
      state.intakeEstimate = null;
      ui.renderEstimate(el('intake-estimate-result'), null);
    }
  });

  el('chat-form').addEventListener('submit', (event) => {
    event.preventDefault();
    sendChat(el('chat-input').value);
  });
  el('chat-input').addEventListener('input', autosize);
  el('chat-input').addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && window.matchMedia('(pointer: fine)').matches) {
      event.preventDefault();
      sendChat(el('chat-input').value);
    }
  });
  el('chat-stop').addEventListener('click', () => state.chat.controller?.abort());
  el('chat-new').addEventListener('click', newChat);
  el('chat-suggestions').addEventListener('click', (event) => {
    const button = event.target.closest('[data-index]');
    if (button) sendChat(SUGGESTIONS[Number(button.dataset.index)].prompt);
  });
  el('chat-log').addEventListener('click', (event) => {
    if (event.target.closest('[data-retry]')) continueChat();
  });
  el('chat-photo').addEventListener('change', async (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      state.chatPhoto = await prepareImage(file);
      el('chat-preview').src = state.chatPhoto.preview;
      el('chat-preview').hidden = false;
    } catch {
      toast('Deze foto kon niet gelezen worden.');
    }
    event.target.value = '';
  });

  const form = el('settings-form');
  form.addEventListener('input', onSettingsInput);
  form.addEventListener('change', onSettingsInput);
  form.addEventListener('submit', (event) => event.preventDefault());
  el('key-generate').addEventListener('click', () => {
    if (state.settings.sync.key && !confirm('Een nieuwe sleutel maken? Zet hem daarna ook als COACH_SYNC_KEY in GitHub, anders kan de app de gegevens niet meer lezen.')) return;
    state.settings.sync.key = generateKey();
    saveSettings(state.settings);
    fillSettingsForm();
    el('sync-key').type = 'text';
    toast('Nieuwe sleutel gemaakt. Kopieer hem naar GitHub.');
    renderAll();
  });
  el('key-copy').addEventListener('click', async () => {
    if (!state.settings.sync.key) return toast('Er is nog geen sleutel.');
    try {
      await navigator.clipboard.writeText(state.settings.sync.key);
      toast('Sleutel gekopieerd.');
    } catch {
      el('sync-key').type = 'text';
      el('sync-key').select();
      toast('Kopiëren lukte niet; de sleutel staat geselecteerd.');
    }
  });
  el('key-show').addEventListener('click', () => {
    const input = el('sync-key');
    input.type = input.type === 'password' ? 'text' : 'password';
  });
  el('sync-now').addEventListener('click', syncNow);
  el('memory-list').addEventListener('click', async (event) => {
    const button = event.target.closest('[data-forget]');
    if (!button) return;
    state.memory = state.memory.filter((m) => m.id !== button.dataset.forget);
    await saveMemory();
    renderMemory();
  });
  el('backup-export').addEventListener('click', exportBackup);
  el('backup-import').addEventListener('change', (event) => {
    const file = event.target.files?.[0];
    if (file) importBackup(file);
    event.target.value = '';
  });
  el('wipe').addEventListener('click', wipe);

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && Date.now() - lastRefresh > 10 * 60 * 1000) {
      lastRefresh = Date.now();
      refreshData({ quiet: true });
    }
  });
}

let lastRefresh = Date.now();

function staticUi() {
  el('model-select').innerHTML = MODELS.map((m) => `<option value="${m.id}">${m.label} – ${m.note}</option>`).join('');
  el('intake-kind').innerHTML = Object.entries(KINDS).map(([key, label]) => `<option value="${key}">${label}</option>`).join('');
  el('intake-fields').innerHTML = Object.entries(FIELDS)
    .map(([key, f]) => `<label class="field"><span>${f.label}</span><input id="field-${key}" type="number" inputmode="decimal" min="0" step="any" /></label>`)
    .join('');
  ui.renderChips(el('quick-today'), QUICK_ADDS.slice(0, 4));
  ui.renderChips(el('quick-adds'), QUICK_ADDS);
  ui.renderSuggestions(el('ask-suggestions'), SUGGESTIONS);
  fillSettingsForm();
  resetIntakeForm();
}

function registerServiceWorker() {
  if (!('serviceWorker' in navigator) || (location.protocol === 'http:' && location.hostname !== 'localhost')) return;
  navigator.serviceWorker.register('sw.js').catch((err) => console.warn('Service worker niet geregistreerd', err));
}

async function init() {
  staticUi();
  bindEvents();
  const { backend, persistent } = await openStore();
  state.store = backend;
  const [cached, entries, memory, conversation] = await Promise.all([
    backend.get(KEYS.bundle),
    backend.get(KEYS.nutrition),
    backend.get(KEYS.memory),
    backend.get(KEYS.conversation),
  ]);
  state.cached = cached ?? null;
  state.activities = cached?.bundle ? mergeActivities(cached.bundle) : [];
  state.entries = entries ?? [];
  state.memory = memory ?? [];
  state.conversation = conversation ?? null;
  repairConversation();
  if (!persistent) toast('Let op: deze browser bewaart niets. Gebruik de app vanaf je beginscherm.', 6000);

  let view = 'vandaag';
  try {
    view = sessionStorage.getItem('coach.view') || view;
  } catch {
    /* default view */
  }
  renderAll();
  showView(document.getElementById(`view-${view}`) ? view : 'vandaag');
  registerServiceWorker();
  refreshData({ quiet: true });
}

init();
