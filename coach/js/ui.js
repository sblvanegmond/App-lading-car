/**
 * Rendering. Every function takes plain data and fills one container; the
 * wiring in app.js decides when. Anything that came from Strava, Garmin, the
 * user or the model goes through escapeHtml.
 */

import { SPORTS, activitiesInRange, weeklySummary } from './data.js';
import {
  addDays,
  formatClock,
  formatDay,
  formatDayLong,
  formatDistance,
  formatHours,
  formatLitres,
  formatNumber,
  formatPace,
  formatSpeed,
  weekStart,
} from './format.js';
import { escapeHtml, renderMarkdown } from './markdown.js';
import { KINDS } from './nutrition.js';
import { TOOL_LABELS } from './coach.js';

const HRV_STATUS = { BALANCED: 'in balans', UNBALANCED: 'uit balans', LOW: 'laag', POOR: 'slecht' };
const READINESS = { PRIME: 'optimaal', HIGH: 'hoog', MODERATE: 'gemiddeld', LOW: 'laag', POOR: 'slecht' };
const SLEEP_QUALITY = { EXCELLENT: 'uitstekend', GOOD: 'goed', FAIR: 'redelijk', POOR: 'slecht' };
const TRAINING_STATUS = {
  PRODUCTIVE: 'productief',
  MAINTAINING: 'onderhoud',
  RECOVERY: 'herstel',
  UNPRODUCTIVE: 'onproductief',
  DETRAINING: 'conditieverlies',
  OVERREACHING: 'overbelast',
  PEAKING: 'piek',
  STRAINED: 'onder druk',
};

export function trainingStatusLabel(phrase) {
  const key = String(phrase ?? '').replace(/_\d+$/, '');
  return TRAINING_STATUS[key] ?? (phrase ? key.toLowerCase().replace(/_/g, ' ') : '');
}

function stat({ label, value, sub = '', tone = '' }) {
  return `<div class="stat ${tone}">
    <div class="stat__label">${escapeHtml(label)}</div>
    <div class="stat__value">${escapeHtml(value)}</div>
    <div class="stat__sub">${escapeHtml(sub) || '&nbsp;'}</div>
  </div>`;
}

export function renderNotices(container, notices) {
  container.innerHTML = notices
    .map((n) => `<p class="notice notice--${n.level ?? 'warn'}">${escapeHtml(n.text)}</p>`)
    .join('');
}

export function renderSetup(card, list, checks) {
  card.hidden = checks.every((c) => c.done);
  list.innerHTML = checks
    .map((c) => `<li class="${c.done ? 'is-done' : ''}">${escapeHtml(c.label)}</li>`)
    .join('');
}

/* ------------------------------------------------------------------ */
/* Vandaag                                                             */
/* ------------------------------------------------------------------ */

export function renderRecovery({ grid, empty, dateEl }, day) {
  const has = day && Object.keys(day).length > 1;
  empty.hidden = Boolean(has);
  grid.hidden = !has;
  dateEl.textContent = has ? formatDay(day.date) : '';
  if (!has) {
    grid.innerHTML = '';
    return;
  }
  const s = day.sleep ?? {};
  const tiles = [
    stat({
      label: 'Slaap',
      value: s.totalS ? formatHours(s.totalS) : '–',
      sub: [s.score && `score ${s.score}`, SLEEP_QUALITY[s.quality]].filter(Boolean).join(' · '),
      tone: 'tone-sleep',
    }),
    stat({
      label: 'HRV',
      value: day.hrv?.lastNight ? `${day.hrv.lastNight} ms` : '–',
      sub: HRV_STATUS[day.hrv?.status] ?? (day.hrv?.weeklyAvg ? `week ${day.hrv.weeklyAvg}` : ''),
      tone: 'tone-recover',
    }),
    stat({
      label: 'Rusthartslag',
      value: day.rhr ? `${day.rhr}` : '–',
      sub: day.rhr7d ? `7 dagen ${day.rhr7d}` : 'slagen/min',
      tone: 'tone-recover',
    }),
    stat({
      label: 'Body Battery',
      value: Number.isFinite(day.bbHigh) ? `${day.bbHigh}` : '–',
      sub: Number.isFinite(day.bbNow) ? `nu ${day.bbNow}` : Number.isFinite(day.bbLow) ? `laagst ${day.bbLow}` : '',
      tone: 'tone-recover',
    }),
    stat({
      label: 'Gereedheid',
      value: Number.isFinite(day.readiness?.score) ? `${day.readiness.score}` : '–',
      sub: READINESS[day.readiness?.level] ?? '',
      tone: 'tone-move',
    }),
    stat({
      label: 'Stress',
      value: Number.isFinite(day.stressAvg) ? `${day.stressAvg}` : '–',
      sub: Number.isFinite(day.stressAvg) ? (day.stressAvg < 26 ? 'rustig' : day.stressAvg < 51 ? 'laag' : day.stressAvg < 76 ? 'gemiddeld' : 'hoog') : '',
      tone: 'tone-sleep',
    }),
  ];
  grid.innerHTML = tiles.join('');
}

const WEEKDAYS = ['ma', 'di', 'wo', 'do', 'vr', 'za', 'zo'];

export function renderMovement({ grid, meta, strip, last }, { activities, today, todayWellness, weekWellness, status }) {
  const monday = weekStart(today);
  const [week] = weeklySummary(activities, { weeks: 1, today });
  const intensity = weekWellness.reduce((sum, d) => sum + (d.intensityModerate ?? 0) + 2 * (d.intensityVigorous ?? 0), 0);
  meta.textContent = `week van ${formatDay(monday)}`;

  const steps = todayWellness?.steps;
  const goal = todayWellness?.stepGoal;
  grid.innerHTML = [
    stat({
      label: 'Stappen',
      value: Number.isFinite(steps) ? formatNumber(steps) : '–',
      sub: Number.isFinite(steps) && goal ? `${Math.round((steps / goal) * 100)}% van ${formatNumber(goal)}` : 'vandaag',
      tone: 'tone-move',
    }),
    stat({
      label: 'Deze week',
      value: `${week.count}×`,
      sub: [week.movingS && formatHours(week.movingS), week.distanceM && formatDistance(week.distanceM)].filter(Boolean).join(' · ') || 'nog niets',
      tone: 'tone-move',
    }),
    stat({
      label: 'Intensiteit',
      value: weekWellness.length ? `${intensity} min` : '–',
      sub: weekWellness.length ? 'van 150 per week' : 'Garmin',
      tone: 'tone-move',
    }),
    ...(status?.vo2max || status?.trainingStatus
      ? [
          stat({
            label: 'VO2max',
            value: status.vo2max ? formatNumber(status.vo2max, 1) : '–',
            sub: trainingStatusLabel(status.trainingStatus),
            tone: 'tone-recover',
          }),
        ]
      : []),
    ...(Number.isFinite(status?.acuteLoad)
      ? [
          stat({
            label: 'Belasting',
            value: `${status.acuteLoad}`,
            sub: Number.isFinite(status.chronicLoad) ? `chronisch ${status.chronicLoad}` : 'acuut',
            tone: 'tone-recover',
          }),
        ]
      : []),
  ].join('');

  const minutes = WEEKDAYS.map((_, i) => {
    const day = addDays(monday, i);
    const total = activitiesInRange(activities, day, day).reduce((sum, a) => sum + (a.movingS ?? 0), 0);
    return { day, minutes: Math.round(total / 60) };
  });
  const max = Math.max(60, ...minutes.map((m) => m.minutes));
  strip.innerHTML = minutes
    .map(
      (m, i) => `<div class="week-strip__day ${m.day === today ? 'is-today' : ''}" title="${escapeHtml(formatDay(m.day))}: ${m.minutes} min">
        <span class="week-strip__value">${m.minutes || ''}</span>
        <div class="week-strip__col"><div class="week-strip__bar" style="height:${Math.round((m.minutes / max) * 100)}%"></div></div>
        <span>${WEEKDAYS[i]}</span>
      </div>`,
    )
    .join('');

  const latest = activities[0];
  last.textContent = latest
    ? `Laatst: ${latest.name} · ${formatDay(latest.date)}${latest.distanceM ? ` · ${formatDistance(latest.distanceM)}` : ''}${latest.movingS ? ` · ${formatClock(latest.movingS)}` : ''}`
    : 'Nog geen activiteiten gesynchroniseerd.';
}

function bar({ label, value, target, unit = '', tone, format = (v) => formatNumber(v) }) {
  const pct = target ? Math.min(100, Math.round((value / target) * 100)) : 0;
  const right = target ? `${format(value)} / ${format(target)}${unit}` : `${format(value)}${unit}`;
  // Without a target there is nothing to fill up to; just the number.
  const track = target ? `<div class="bar__track"><div class="bar__fill" style="width:${pct}%"></div></div>` : '';
  return `<div class="bar ${tone}">
    <div class="bar__top"><span>${escapeHtml(label)}</span><b>${escapeHtml(right)}</b></div>
    ${track}
  </div>`;
}

export function renderIntakeBars(container, totals, targets) {
  const rows = [
    bar({ label: 'Vocht', value: totals.fluidMl, target: targets.waterMl, tone: 'tone-water', format: (v) => formatLitres(v) }),
    bar({ label: 'Energie', value: totals.kcal, target: targets.kcal, unit: ' kcal', tone: 'tone-food' }),
    bar({ label: 'Eiwit', value: totals.proteinG, target: targets.proteinG, unit: ' g', tone: 'tone-food' }),
  ];
  const extra = [
    totals.caffeineMg && `${formatNumber(totals.caffeineMg)} mg cafeïne`,
    totals.alcoholUnits && `${formatNumber(totals.alcoholUnits)} ${totals.alcoholUnits === 1 ? 'glas' : 'glazen'} alcohol`,
  ].filter(Boolean);
  container.innerHTML =
    rows.join('') +
    (extra.length ? `<p class="muted small">${escapeHtml(extra.join(' · '))}</p>` : '') +
    (totals.count && !totals.kcalComplete && totals.kcal ? '<p class="muted small">Niet alles heeft kcal; het totaal is te laag.</p>' : '');
}

export function renderChips(container, items, { active = null } = {}) {
  container.innerHTML = items
    .map((item, i) => `<button class="chip ${item.key === active ? 'is-active' : ''}" type="button" data-index="${i}" data-key="${escapeHtml(item.key ?? '')}">${escapeHtml(item.label)}</button>`)
    .join('');
}

export function renderSuggestions(container, prompts) {
  container.innerHTML = prompts
    .map((p, i) => `<button class="suggestion" type="button" data-index="${i}">${escapeHtml(p.label)}</button>`)
    .join('');
}

/* ------------------------------------------------------------------ */
/* Activiteiten                                                        */
/* ------------------------------------------------------------------ */

function figures(a) {
  const onFoot = a.sport === 'run' || a.sport === 'walk';
  return [
    a.distanceM && formatDistance(a.distanceM),
    a.movingS && formatClock(a.movingS),
    a.avgSpeed && a.distanceM && (onFoot ? formatPace(a.avgSpeed) : a.sport === 'swim' ? null : formatSpeed(a.avgSpeed)),
    a.avgHr && `♥ ${Math.round(a.avgHr)}`,
    a.avgWatts && `${Math.round(a.weightedWatts ?? a.avgWatts)} W`,
    a.elevationM && a.elevationM >= 20 && `↑ ${Math.round(a.elevationM)} m`,
    a.trainingLoad && `belasting ${Math.round(a.trainingLoad)}`,
  ].filter(Boolean);
}

function table(head, rows) {
  return `<table><thead><tr>${head.map((h) => `<th>${escapeHtml(h)}</th>`).join('')}</tr></thead><tbody>${rows
    .map((r) => `<tr>${r.map((c) => `<td>${escapeHtml(c)}</td>`).join('')}</tr>`)
    .join('')}</tbody></table>`;
}

function detailHtml(a) {
  const parts = [];
  const effect = [
    Number.isFinite(a.aerobicTE) && `aeroob ${formatNumber(a.aerobicTE, 1)}`,
    Number.isFinite(a.anaerobicTE) && `anaeroob ${formatNumber(a.anaerobicTE, 1)}`,
    a.teLabel && a.teLabel.toLowerCase().replace(/_/g, ' '),
  ].filter(Boolean);
  if (effect.length) parts.push(`<p class="muted">Trainingseffect: ${escapeHtml(effect.join(' · '))}</p>`);
  const extra = [
    a.maxHr && `max ♥ ${Math.round(a.maxHr)}`,
    a.calories && `${Math.round(a.calories)} kcal`,
    a.cadence && `cadans ${Math.round(a.cadence)}`,
    a.sets && `${a.sets} sets`,
    a.elapsedS && a.movingS && a.elapsedS - a.movingS > 60 && `totaal ${formatClock(a.elapsedS)}`,
  ].filter(Boolean);
  if (extra.length) parts.push(`<p class="muted">${escapeHtml(extra.join(' · '))}</p>`);
  if (a.description) parts.push(`<p>${escapeHtml(a.description)}</p>`);
  if (a.splits?.length) {
    parts.push(
      table(
        ['km', 'tempo', '♥', 'hm'],
        a.splits.map((s) => [String(s.km ?? ''), s.avgSpeed ? formatPace(s.avgSpeed).replace(' /km', '') : '–', s.avgHr ? String(Math.round(s.avgHr)) : '–', Number.isFinite(s.elevDiffM) ? String(s.elevDiffM) : '–']),
      ),
    );
  } else if (a.laps?.length) {
    parts.push(
      table(
        ['ronde', 'afstand', 'tijd', '♥'],
        a.laps.map((l, i) => [l.name || String(i + 1), formatDistance(l.distanceM), formatClock(l.movingS), l.avgHr ? String(Math.round(l.avgHr)) : '–']),
      ),
    );
  }
  parts.push(`<button class="button button--ghost" type="button" data-ask="${escapeHtml(a.id)}">Vraag de coach over deze training</button>`);
  parts.push(`<p class="muted small">Bron: ${escapeHtml(a.sources.map((s) => (s === 'strava' ? 'Strava' : 'Garmin')).join(' + '))}</p>`);
  return `<div class="activity__detail">${parts.join('')}</div>`;
}

export function renderActivities(list, empty, activities, { filter = 'all', expanded = null } = {}) {
  const shown = filter === 'all' ? activities : activities.filter((a) => a.sport === filter);
  empty.hidden = shown.length > 0;
  const weeks = new Map();
  for (const a of shown.slice(0, 200)) {
    const key = weekStart(a.date);
    if (!weeks.has(key)) weeks.set(key, []);
    weeks.get(key).push(a);
  }
  list.innerHTML = [...weeks.entries()]
    .map(([monday, items]) => {
      const seconds = items.reduce((s, a) => s + (a.movingS ?? 0), 0);
      const meters = items.reduce((s, a) => s + (a.distanceM ?? 0), 0);
      const head = `<div class="week-head"><b>Week van ${escapeHtml(formatDay(monday))}</b><span>${items.length}× · ${escapeHtml(formatHours(seconds))}${meters ? ` · ${escapeHtml(formatDistance(meters))}` : ''}</span></div>`;
      const cards = items
        .map(
          (a) => `<article class="card activity sport-${a.sport}" data-id="${escapeHtml(a.id)}" tabindex="0">
            <div class="activity__top">
              <span class="activity__sport">${escapeHtml(SPORTS[a.sport].label)}</span>
              <span class="activity__date">${escapeHtml(formatDay(a.date))} ${escapeHtml(a.start.slice(11, 16))}</span>
            </div>
            <div class="activity__name">${escapeHtml(a.name)}</div>
            <div class="activity__figures">${figures(a).map((f) => `<span>${escapeHtml(f)}</span>`).join('')}</div>
            ${expanded === a.id ? detailHtml(a) : ''}
          </article>`,
        )
        .join('');
      return head + cards;
    })
    .join('');
}

/* ------------------------------------------------------------------ */
/* Voeding                                                             */
/* ------------------------------------------------------------------ */

function entryMeta(e) {
  return [
    KINDS[e.kind],
    e.kcal && `${formatNumber(e.kcal)} kcal`,
    e.proteinG && `${formatNumber(e.proteinG)} g eiwit`,
    e.fluidMl && formatLitres(e.fluidMl),
    e.alcoholUnits && `${formatNumber(e.alcoholUnits)} alcohol`,
    e.source === 'ai' && 'geschat',
    e.source === 'coach' && 'door de coach',
  ]
    .filter(Boolean)
    .join(' · ');
}

export function renderEntries(list, empty, entries) {
  empty.hidden = entries.length > 0;
  list.innerHTML = entries
    .map(
      (e) => `<li class="entry">
        <span class="entry__time">${escapeHtml(e.time)}</span>
        <div class="entry__body">
          <div class="entry__title">${escapeHtml(e.description)}</div>
          <div class="entry__meta">${escapeHtml(entryMeta(e))}</div>
        </div>
        <button class="entry__delete" type="button" data-delete="${escapeHtml(e.id)}" aria-label="Verwijderen">×</button>
      </li>`,
    )
    .join('');
}

export function renderEstimate(container, estimate) {
  if (!estimate) {
    container.hidden = true;
    container.innerHTML = '';
    return;
  }
  container.hidden = false;
  const items = (estimate.items ?? []).map((i) => `<li>${escapeHtml(`${i.name} (${i.amount}): ${formatNumber(i.kcal)} kcal`)}</li>`).join('');
  container.innerHTML = `<b>Schatting: ${escapeHtml(formatNumber(estimate.kcal))} kcal, ${escapeHtml(formatNumber(estimate.proteinG))} g eiwit</b>
    <span class="muted small">Zekerheid: ${escapeHtml(estimate.confidence)}${estimate.note ? ` · ${escapeHtml(estimate.note)}` : ''}</span>
    ${items ? `<ul>${items}</ul>` : ''}`;
}

export function renderMemory(list, empty, memory) {
  empty.hidden = memory.length > 0;
  list.innerHTML = memory
    .map(
      (m) => `<li class="entry">
        <div class="entry__body"><div class="entry__title">${escapeHtml(m.text)}</div><div class="entry__meta">${escapeHtml(formatDayLong(m.date))}</div></div>
        <button class="entry__delete" type="button" data-forget="${escapeHtml(m.id)}" aria-label="Vergeten">×</button>
      </li>`,
    )
    .join('');
}

/* ------------------------------------------------------------------ */
/* Coach                                                               */
/* ------------------------------------------------------------------ */

function traceHtml(parts) {
  return parts.length
    ? `<div class="trace">${parts
        .map((p) => `<span class="tool-chip ${p.pending ? 'is-pending' : ''}" title="${escapeHtml(p.summary ?? '')}">${escapeHtml(TOOL_LABELS[p.name] ?? p.name)}</span>`)
        .join('')}</div>`
    : '';
}

function assistantHtml(parts, { live = false } = {}) {
  const html = [];
  let tools = [];
  const flush = () => {
    html.push(traceHtml(tools));
    tools = [];
  };
  for (const part of parts) {
    if (part.type === 'tool') {
      tools.push(part);
      continue;
    }
    flush();
    if (part.type === 'text') html.push(renderMarkdown(part.text));
    else if (part.type === 'update') html.push(`<p class="update">${escapeHtml(part.text)}</p>`);
    else if (part.type === 'note') html.push(`<p class="update">${escapeHtml(part.text)}</p>`);
  }
  flush();
  const waiting = live && !parts.some((p) => p.type === 'text');
  return `<div class="bubble bubble--assistant">${html.join('')}${waiting ? '<span class="typing" aria-label="De coach denkt na"><i></i><i></i><i></i></span>' : ''}</div>`;
}

export function renderChat(log, items, { live = null, error = null, canRetry = false } = {}) {
  const html = items.map((item) => {
    if (item.role === 'user') {
      const photo = item.images ? `<div class="bubble__photo">📷 foto bijgevoegd</div>` : '';
      return `<div class="bubble bubble--user">${photo}${escapeHtml(item.text)}</div>`;
    }
    return assistantHtml(item.parts);
  });
  if (live) html.push(assistantHtml(live.parts, { live: true }));
  if (error) {
    html.push(
      `<div class="bubble bubble--assistant bubble--error"><p>${escapeHtml(error)}</p>${canRetry ? '<button class="link-button" type="button" data-retry>Opnieuw proberen</button>' : ''}</div>`,
    );
  }
  log.innerHTML = html.join('');
}
