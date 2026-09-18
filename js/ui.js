/**
 * Rendering. Every function here takes data and writes into the DOM;
 * none of them fetch or compute anything.
 */

import { formatEuro, formatCents } from './pricing.js';
import { MODES } from './planner.js';
import { totalKwp } from './config.js';

const HOUR_MS = 3600000;

export const fmt = {
  time: new Intl.DateTimeFormat('nl-NL', { hour: '2-digit', minute: '2-digit' }),
  hour: new Intl.DateTimeFormat('nl-NL', { hour: '2-digit' }),
  weekday: new Intl.DateTimeFormat('nl-NL', { weekday: 'short', day: 'numeric', month: 'short' }),
  dayShort: new Intl.DateTimeFormat('nl-NL', { weekday: 'short' }),
};

export function clock(date) {
  return fmt.time.format(date instanceof Date ? date : new Date(date));
}

export function number(value, decimals = 1) {
  const n = Number.isFinite(value) ? value : 0;
  return new Intl.NumberFormat('nl-NL', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(n);
}

/** "vanavond 23:00", "morgenochtend 04:00" - a phrase a human would use. */
export function dayPhrase(date, now = new Date()) {
  const start = new Date(now);
  start.setHours(0, 0, 0, 0);
  const target = new Date(date);
  target.setHours(0, 0, 0, 0);
  const days = Math.round((target - start) / 86400000);
  if (days === 0) return 'vandaag';
  if (days === 1) return 'morgen';
  if (days === 2) return 'overmorgen';
  return fmt.weekday.format(date);
}

export function clearNode(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
}

export function renderMessages(container, messages) {
  clearNode(container);
  for (const item of messages) {
    const div = document.createElement('div');
    div.className = `message message--${item.level ?? 'info'}`;
    div.textContent = item.text;
    container.appendChild(div);
  }
}

/* ------------------------------------------------------------------ */
/* Hero card                                                           */
/* ------------------------------------------------------------------ */

export function renderHero({ windowEl, subEl, statsEl, plan, now }) {
  clearNode(statsEl);

  if (!plan || plan.blocks.length === 0) {
    windowEl.textContent = plan && plan.need.grossKwh <= 0 ? 'Accu is vol' : 'Geen plan';
    subEl.textContent =
      plan && plan.need.grossKwh <= 0
        ? 'Je huidige accustand zit al op je doel.'
        : 'Er zijn geen bruikbare laaduren gevonden voor je deadline.';
    return;
  }

  const first = plan.blocks[0];
  windowEl.textContent = `${clock(first.from)} – ${clock(first.to)}`;
  const hours = (first.to - first.from) / HOUR_MS;
  subEl.textContent =
    `${dayPhrase(first.from, now)}, ${number(hours, 1)} uur laden · ` +
    `${number(first.kwh, 1)} kWh · ${formatEuro(first.cost)}`;

  const stats = [
    ['Totaal', `${number(plan.totals.kwh, 1)} kWh`],
    ['Kosten', formatEuro(plan.totals.cost)],
    ['Gem. prijs', formatCents(plan.totals.avgPricePerKwh)],
    ['Eigen zon', `${Math.round(plan.totals.solarSharePct)}%`],
    ['Accu straks', `${Math.round(plan.reachableSocPct)}%`],
    ['Klaar om', clock(plan.windowEnd)],
  ];
  for (const [label, value] of stats) {
    const wrap = document.createElement('div');
    const dt = document.createElement('dt');
    dt.textContent = label;
    const dd = document.createElement('dd');
    dd.textContent = value;
    wrap.append(dt, dd);
    statsEl.appendChild(wrap);
  }
}

export function renderExtraBlocks({ card, list, plan, now }) {
  clearNode(list);
  const extra = (plan?.blocks ?? []).slice(1);
  card.hidden = extra.length === 0;
  for (const block of extra) {
    const li = document.createElement('li');
    const left = document.createElement('div');
    const strong = document.createElement('b');
    strong.textContent = `${clock(block.from)} – ${clock(block.to)}`;
    const small = document.createElement('small');
    small.textContent = ` ${dayPhrase(block.from, now)}`;
    left.append(strong, small);

    const right = document.createElement('div');
    right.textContent = `${number(block.kwh, 1)} kWh · ${formatEuro(block.cost)}`;
    li.append(left, right);
    list.appendChild(li);
  }
}

export function renderSolarDays({ container, noteEl, days, summary, settings }) {
  clearNode(container);
  const byDate = new Map();
  for (const day of summary ?? []) {
    byDate.set(new Date(day.date).toDateString(), day);
  }

  const entries = (days ?? []).slice(0, 3);
  if (entries.length === 0) {
    noteEl.textContent = 'Nog geen weersverwachting opgehaald.';
    return;
  }

  for (const day of entries) {
    const date = new Date(day.date);
    const match = byDate.get(date.toDateString());
    const box = document.createElement('div');
    const title = document.createElement('h3');
    title.textContent = dayPhrase(date);
    const strong = document.createElement('strong');
    strong.textContent = match ? `${number(match.pvKwh, 1)} kWh` : '—';
    const sun = document.createElement('p');
    const sunHours = Number.isFinite(day.sunshineHours) ? `${number(day.sunshineHours, 1)} zonuren` : '';
    const rise = day.sunrise ? clock(new Date(day.sunrise)) : null;
    const set = day.sunset ? clock(new Date(day.sunset)) : null;
    sun.textContent = [sunHours, rise && set ? `${rise}–${set}` : null].filter(Boolean).join(' · ');
    box.append(title, strong, sun);
    container.appendChild(box);
  }

  const kwp = totalKwp(settings.pv);
  noteEl.textContent =
    `Verwachte opbrengst van ${number(kwp, 2)} kWp in ${settings.location.name}. ` +
    `Het basisverbruik van je huis (${number(settings.houseBaseLoadKw, 2)} kW) gaat er nog af ` +
    'voordat er stroom naar de auto kan.';
}

export function renderSavings({ container, plan }) {
  clearNode(container);
  if (!plan?.comparison || plan.blocks.length === 0) return;
  const c = plan.comparison;

  const rows = [
    ['Kosten met dit plan', formatEuro(plan.totals.cost), false],
    ['Als je nu meteen zou laden', formatEuro(c.immediateCost), false],
    ['Je bespaart daarmee', formatEuro(Math.max(0, c.savingsVsImmediate)), c.savingsVsImmediate > 0.005],
    ['Gemiddelde prijs in dit venster', formatCents(c.avgAllInPrice), false],
    ['Voordeel t.o.v. gemiddeld uur', formatEuro(Math.max(0, c.savingsVsAverage)), c.savingsVsAverage > 0.005],
  ];
  if (c.cheapestHour) {
    rows.push([
      `Goedkoopste uur (${clock(c.cheapestHour.start)})`,
      formatCents(c.cheapestHour.price),
      false,
    ]);
  }
  if (c.dearestHour) {
    rows.push([`Duurste uur (${clock(c.dearestHour.start)})`, formatCents(c.dearestHour.price), false]);
  }

  for (const [label, value, good] of rows) {
    const row = document.createElement('div');
    const left = document.createElement('span');
    left.textContent = label;
    const right = document.createElement('span');
    right.textContent = value;
    if (good) right.classList.add('is-good');
    row.append(left, right);
    container.appendChild(row);
  }
}

/* ------------------------------------------------------------------ */
/* Hourly chart                                                        */
/* ------------------------------------------------------------------ */

const SVG_NS = 'http://www.w3.org/2000/svg';

const CHART = {
  barW: 20,
  gap: 4,
  padLeft: 38,
  padRight: 14,
  priceTop: 28,
  priceH: 96,
  axisGap: 15,
  solarGap: 22,
  solarH: 56,
};

function svgEl(name, attrs = {}) {
  const node = document.createElementNS(SVG_NS, name);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === null || value === undefined) continue;
    node.setAttribute(key, String(value));
  }
  return node;
}

/** Bar with rounded ends only on the data side, anchored to the baseline. */
function barPath(x, baselineY, valueY, width, radius = 4) {
  const up = valueY <= baselineY;
  const h = Math.abs(baselineY - valueY);
  const r = Math.min(radius, width / 2, Math.max(0, h));
  if (h < 0.5) return `M${x} ${baselineY}h${width}`;
  return up
    ? `M${x} ${baselineY}V${valueY + r}a${r} ${r} 0 0 1 ${r} ${-r}h${width - 2 * r}a${r} ${r} 0 0 1 ${r} ${r}V${baselineY}Z`
    : `M${x} ${baselineY}V${valueY - r}a${r} ${r} 0 0 0 ${r} ${r}h${width - 2 * r}a${r} ${r} 0 0 0 ${r} ${-r}V${baselineY}Z`;
}

function niceCeil(value) {
  if (!Number.isFinite(value) || value <= 0) return 1;
  const exp = Math.floor(Math.log10(value));
  const base = 10 ** exp;
  for (const step of [1, 1.5, 2, 2.5, 3, 4, 5, 7.5, 10]) {
    if (value <= step * base) return step * base;
  }
  return 10 * base;
}

/**
 * Two stacked panes over one shared time axis: price on top, expected solar
 * surplus underneath. Deliberately not a dual-axis chart - the two measures
 * have nothing to do with each other numerically.
 */
export function renderChart({ container, legendEl, readoutEl, timeline, plan, now = new Date() }) {
  clearNode(container);
  if (legendEl) clearNode(legendEl);

  const from = new Date(now.getTime() - HOUR_MS);
  const slots = timeline.filter((s) => s.end > from);
  if (slots.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'card__note';
    empty.textContent = 'Nog geen uurgegevens beschikbaar.';
    container.appendChild(empty);
    return;
  }

  const selected = new Map();
  for (const slot of plan?.slots ?? []) selected.set(slot.start.toISOString(), slot);

  const step = CHART.barW + CHART.gap;
  const width = CHART.padLeft + slots.length * step + CHART.padRight;
  const priceBottom = CHART.priceTop + CHART.priceH;
  const axisY = priceBottom + CHART.axisGap;
  const solarTitleY = axisY + CHART.solarGap;
  const solarTop = solarTitleY + 8;
  const solarBottom = solarTop + CHART.solarH;
  const height = solarBottom + 16;

  const prices = slots.filter((s) => s.hasPrice).map((s) => s.allInPrice);
  const domainMax = niceCeil(Math.max(0.05, ...prices));
  const domainMin = Math.min(0, ...prices, 0);
  const priceSpan = domainMax - domainMin || 1;
  const yOfPrice = (p) => priceBottom - ((p - domainMin) / priceSpan) * CHART.priceH;
  const priceBase = yOfPrice(0);

  const maxSurplus = Math.max(0.5, ...slots.map((s) => s.surplusKw));
  const solarMax = niceCeil(maxSurplus);
  const yOfSolar = (kw) => solarBottom - (kw / solarMax) * CHART.solarH;

  const svg = svgEl('svg', {
    width,
    height,
    viewBox: `0 0 ${width} ${height}`,
    role: 'img',
    'aria-label':
      'Staafdiagram met per uur de all-in stroomprijs en het verwachte overschot van de zonnepanelen. De volledige cijfers staan in de tabel eronder.',
  });

  // Pane titles and the two value axes.
  svg.appendChild(textEl(CHART.padLeft, 14, 'Prijs in ct/kWh', 'chart__pane-title'));
  svg.appendChild(textEl(CHART.padLeft, solarTitleY, 'Zon over voor de auto, in kW', 'chart__pane-title'));

  for (const value of [domainMax, domainMin]) {
    const y = yOfPrice(value);
    svg.appendChild(svgEl('line', { x1: CHART.padLeft - 4, x2: width - CHART.padRight, y1: y, y2: y, class: 'chart__grid' }));
    svg.appendChild(textEl(0, y + 3, number(value * 100, 0), 'chart__label'));
  }
  if (domainMin < 0) {
    svg.appendChild(svgEl('line', { x1: CHART.padLeft - 4, x2: width - CHART.padRight, y1: priceBase, y2: priceBase, class: 'chart__grid' }));
  }
  svg.appendChild(svgEl('line', { x1: CHART.padLeft - 4, x2: width - CHART.padRight, y1: solarBottom, y2: solarBottom, class: 'chart__grid' }));
  svg.appendChild(textEl(0, solarTop + 4, number(solarMax, 1), 'chart__label'));

  const showLabels = selected.size > 0 && selected.size <= 14;

  slots.forEach((slot, index) => {
    const x = CHART.padLeft + index * step;
    const key = slot.start.toISOString();
    const pick = selected.get(key);
    const isSelected = Boolean(pick);
    const isPast = slot.end <= now;

    const group = svgEl('g', { opacity: isPast && !isSelected ? 0.4 : 1 });

    if (slot.hasPrice) {
      const y = yOfPrice(slot.allInPrice);
      group.appendChild(
        svgEl('path', {
          d: barPath(x, priceBase, y, CHART.barW),
          class: isSelected ? 'chart__bar-price chart__bar-price--selected' : 'chart__bar-price',
        }),
      );
      if (isSelected && showLabels) {
        group.appendChild(
          textEl(x + CHART.barW / 2, Math.min(y, priceBase) - 4, number(slot.allInPrice * 100, 0), 'chart__value', 'middle'),
        );
      }
    }

    if (slot.surplusKw > 0.01) {
      const y = yOfSolar(slot.surplusKw);
      group.appendChild(
        svgEl('path', {
          d: barPath(x, solarBottom, y, CHART.barW),
          class: isSelected ? 'chart__bar-solar chart__bar-solar--selected' : 'chart__bar-solar',
        }),
      );
    }

    // Hour labels every other bar keeps them from colliding.
    if (index % 2 === 0) {
      group.appendChild(textEl(x + CHART.barW / 2, axisY, fmt.hour.format(slot.start), 'chart__label', 'middle'));
    }
    if (slot.start.getHours() === 0) {
      group.appendChild(svgEl('line', { x1: x - CHART.gap / 2, x2: x - CHART.gap / 2, y1: CHART.priceTop - 8, y2: solarBottom, class: 'chart__grid' }));
      group.appendChild(textEl(x + 2, CHART.priceTop - 12, fmt.dayShort.format(slot.start), 'chart__label'));
    }

    const hit = svgEl('rect', {
      x,
      y: CHART.priceTop - 10,
      width: CHART.barW + CHART.gap,
      height: solarBottom - CHART.priceTop + 12,
      fill: 'transparent',
      tabindex: 0,
      role: 'button',
      'aria-label': describeSlot(slot, pick),
    });
    const title = svgEl('title');
    title.textContent = describeSlot(slot, pick);
    hit.appendChild(title);
    const show = () => {
      if (readoutEl) readoutEl.textContent = describeSlot(slot, pick);
    };
    hit.addEventListener('pointerenter', show);
    hit.addEventListener('pointerdown', show);
    hit.addEventListener('focus', show);
    group.appendChild(hit);

    svg.appendChild(group);
  });

  // Where "now" sits inside its hour.
  const firstStart = slots[0].start.getTime();
  const offsetHours = (now.getTime() - firstStart) / HOUR_MS;
  if (offsetHours >= 0 && offsetHours <= slots.length) {
    const x = CHART.padLeft + offsetHours * step;
    svg.appendChild(svgEl('line', { x1: x, x2: x, y1: CHART.priceTop - 10, y2: solarBottom, class: 'chart__now' }));
  }

  container.appendChild(svg);

  if (legendEl) {
    legendEl.append(
      legendItem('var(--series-price)', 'All-in prijs per uur'),
      legendItem('var(--series-solar)', 'Zonoverschot'),
      legendItem('var(--accent)', 'Fel gekleurd = gepland laaduur'),
    );
  }

  // Open the view at "now" rather than at midnight.
  requestAnimationFrame(() => {
    const target = CHART.padLeft + Math.max(0, offsetHours - 1) * step;
    container.scrollLeft = Math.max(0, target - 24);
  });
}

function describeSlot(slot, pick) {
  const parts = [`${clock(slot.start)}–${clock(slot.end)}`];
  parts.push(slot.hasPrice ? `${formatCents(slot.allInPrice)}/kWh` : 'prijs onbekend');
  parts.push(`zon over ${number(slot.surplusKw, 1)} kW`);
  if (Number.isFinite(slot.cloudCoverPct)) parts.push(`${Math.round(slot.cloudCoverPct)}% bewolking`);
  if (pick) parts.push(`plan: ${number(pick.kwh, 1)} kWh laden`);
  return parts.join(' · ');
}

function textEl(x, y, content, className, anchor) {
  const node = svgEl('text', { x, y, class: className, 'text-anchor': anchor });
  node.textContent = content;
  return node;
}

function legendItem(color, label) {
  const span = document.createElement('span');
  const swatch = document.createElement('i');
  swatch.style.background = color;
  span.append(swatch, document.createTextNode(label));
  return span;
}

/* ------------------------------------------------------------------ */
/* Hour table - the accessible view of the same numbers                */
/* ------------------------------------------------------------------ */

export function renderHoursTable({ table, timeline, plan, now = new Date() }) {
  const tbody = table.querySelector('tbody');
  clearNode(tbody);

  const selected = new Map();
  for (const slot of plan?.slots ?? []) selected.set(slot.start.toISOString(), slot);

  let lastDay = null;
  for (const slot of timeline) {
    const dayKey = slot.start.toDateString();
    if (dayKey !== lastDay) {
      lastDay = dayKey;
      const row = document.createElement('tr');
      row.className = 'daybreak';
      const cell = document.createElement('td');
      cell.colSpan = 4;
      cell.textContent = `${dayPhrase(slot.start, now)} · ${fmt.weekday.format(slot.start)}`;
      row.appendChild(cell);
      tbody.appendChild(row);
    }

    const pick = selected.get(slot.start.toISOString());
    const row = document.createElement('tr');
    if (pick) row.classList.add('is-selected');
    if (slot.end <= now) row.classList.add('is-past');

    row.append(
      cellText(`${clock(slot.start)}`),
      cellText(slot.hasPrice ? formatCents(slot.allInPrice) : '—'),
      cellText(slot.surplusKw > 0.01 ? `${number(slot.surplusKw, 1)} kW` : '—'),
      cellText(pick ? `${number(pick.kwh, 1)} kWh` : ''),
    );
    tbody.appendChild(row);
  }
}

function cellText(text) {
  const td = document.createElement('td');
  td.textContent = text;
  return td;
}

/* ------------------------------------------------------------------ */
/* Settings                                                            */
/* ------------------------------------------------------------------ */

export function renderModes(container, settings, onChange) {
  clearNode(container);
  for (const mode of Object.values(MODES)) {
    const label = document.createElement('label');
    label.className = `mode${settings.mode === mode.id ? ' mode--active' : ''}`;
    const input = document.createElement('input');
    input.type = 'radio';
    input.name = 'mode';
    input.value = mode.id;
    input.checked = settings.mode === mode.id;
    input.addEventListener('change', () => onChange(mode.id));
    const text = document.createElement('div');
    const strong = document.createElement('strong');
    strong.textContent = mode.label;
    const hint = document.createElement('span');
    hint.textContent = mode.hint;
    text.append(strong, hint);
    label.append(input, text);
    container.appendChild(label);
  }
}

export function renderArrays(container, template, arrays, { onRemove }) {
  clearNode(container);
  arrays.forEach((array, index) => {
    const node = template.content.firstElementChild.cloneNode(true);
    node.dataset.index = String(index);
    node.querySelector('.array__legend').textContent = `Vlak ${index + 1}`;
    node.querySelector('[data-key="label"]').value = array.label ?? `Vlak ${index + 1}`;
    node.querySelector('[data-key="kWp"]').value = array.kWp ?? 0;
    node.querySelector('[data-key="tilt"]').value = array.tilt ?? 35;
    const azimuth = node.querySelector('[data-key="azimuth"]');
    azimuth.value = String(nearestAzimuth(array.azimuth));
    const remove = node.querySelector('.array__remove');
    remove.disabled = arrays.length <= 1;
    remove.addEventListener('click', () => onRemove(index));
    container.appendChild(node);
  });
}

/** Snap a free-form azimuth onto the eight compass options in the form. */
export function nearestAzimuth(value) {
  const options = [0, -45, 45, -90, 90, -135, 135, 180];
  const n = Number(value);
  if (!Number.isFinite(n)) return 0;
  return options.reduce((best, option) => (Math.abs(option - n) < Math.abs(best - n) ? option : best), 0);
}
