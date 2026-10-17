#!/usr/bin/env node
/**
 * End-to-end check of the coach app in a real Chromium, without network:
 * the GitHub data file and the Claude API are both answered by this script.
 *
 *   npm install --no-save playwright
 *   npm run coach:sdk
 *   node coach/tools/smoke.mjs [screenshot-folder]
 *
 * It sets the app up like a user would, checks every tab, has a short
 * conversation in which the coach calls a tool, and verifies what was sent
 * to Claude: model, beta features, the tool list and an append-only history.
 */

import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

import { encryptEnvelope } from '../js/crypto.js';
import { localDate, addDays } from '../js/format.js';
import { sampleBundle } from '../../tests/coach-fixtures.js';

const OUT = resolve(process.argv[2] ?? 'coach-smoke');
const PORT = 8093;
const BASE = `http://localhost:${PORT}/coach/`;
const KEY = 'smoke-test-sleutel-1234567890';
const FIXTURE_DAY = '2026-10-17';

mkdirSync(OUT, { recursive: true });

/** The fixture is pinned to 17 October 2026; move it to today. */
function bundleForToday() {
  const today = localDate();
  const shift = Math.round((Date.parse(today) - Date.parse(FIXTURE_DAY)) / 86400000);
  const text = JSON.stringify(sampleBundle()).replace(/2026-10-(\d{2})/g, (match) => addDays(match, shift));
  const bundle = JSON.parse(text);
  bundle.generatedAt = new Date(Date.now() - 40 * 60000).toISOString();
  return bundle;
}

function sse(message) {
  const lines = [];
  const send = (type, data) => lines.push(`event: ${type}\ndata: ${JSON.stringify({ type, ...data })}\n`);
  send('message_start', { message: { ...message, content: [], stop_reason: null, usage: { input_tokens: 900, output_tokens: 1 } } });
  message.content.forEach((block, index) => {
    if (block.type === 'text') {
      send('content_block_start', { index, content_block: { type: 'text', text: '' } });
      for (const piece of block.text.match(/.{1,24}/gs)) send('content_block_delta', { index, delta: { type: 'text_delta', text: piece } });
    } else if (block.type === 'thinking') {
      send('content_block_start', { index, content_block: { type: 'thinking', thinking: '', signature: '' } });
      send('content_block_delta', { index, delta: { type: 'thinking_delta', thinking: block.thinking } });
      send('content_block_delta', { index, delta: { type: 'signature_delta', signature: block.signature } });
    } else if (block.type === 'tool_use') {
      send('content_block_start', { index, content_block: { ...block, input: {} } });
      send('content_block_delta', { index, delta: { type: 'input_json_delta', partial_json: JSON.stringify(block.input) } });
    }
    send('content_block_stop', { index });
  });
  send('message_delta', { delta: { stop_reason: message.stop_reason, stop_sequence: null }, usage: { output_tokens: 120 } });
  send('message_stop', {});
  // Every event, the last one included, ends with a blank line.
  return `${lines.join('\n')}\n`;
}

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': 'GET, POST, OPTIONS',
};

const claudeCalls = [];

function claudeAnswer(body) {
  const base = { id: `msg_${claudeCalls.length}`, type: 'message', role: 'assistant', model: body.model };
  if (body.output_config?.format) {
    const estimate = {
      description: 'Twee boterhammen met kaas',
      kind: 'lunch',
      items: [{ name: 'Volkorenbrood', amount: '2 sneetjes', kcal: 180 }, { name: 'Jonge kaas', amount: '2 plakken', kcal: 150 }],
      kcal: 330,
      proteinG: 18,
      carbsG: 30,
      fatG: 14,
      fluidMl: 0,
      alcoholUnits: 0,
      caffeineMg: 0,
      confidence: 'redelijk',
      note: 'Uitgegaan van volkorenbrood en 48+ kaas.',
    };
    return { json: { ...base, content: [{ type: 'text', text: JSON.stringify(estimate) }], stop_reason: 'end_turn', usage: { input_tokens: 300, output_tokens: 90 } } };
  }
  const last = body.messages.at(-1);
  const toolResult = Array.isArray(last.content) && last.content.find((b) => b.type === 'tool_result');
  if (toolResult) {
    const days = JSON.parse(toolResult.content).days;
    const slept = days.filter((d) => d.sleepH);
    return {
      sse: sse({
        ...base,
        content: [
          {
            type: 'text',
            text: `Je sliep de afgelopen dagen gemiddeld **${(slept.reduce((s, d) => s + d.sleepH, 0) / slept.length).toFixed(1).replace('.', ',')} uur** (Garmin).\n\n- Je HRV is in balans.\n- Je rusthartslag is laag.\n\nHoud je bedtijd vast, dan blijft dat zo.`,
          },
        ],
        stop_reason: 'end_turn',
      }),
    };
  }
  const today = localDate();
  return {
    sse: sse({
      ...base,
      content: [
        { type: 'thinking', thinking: 'Ik bekijk je slaap van de afgelopen dagen.', signature: 'sig-smoke-1' },
        { type: 'tool_use', id: 'toolu_smoke_1', name: 'get_wellness', input: { from: addDays(today, -6), to: today } },
      ],
      stop_reason: 'tool_use',
    }),
  };
}

const server = spawn(process.execPath, ['tools/serve.js'], { env: { ...process.env, PORT: String(PORT) }, stdio: 'ignore' });
await new Promise((r) => setTimeout(r, 600));

const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, timezoneId: 'Europe/Amsterdam', locale: 'nl-NL', colorScheme: 'dark' });
const page = await context.newPage();
const problems = [];
page.on('pageerror', (err) => problems.push(`pageerror: ${err.message}`));
page.on('console', (msg) => {
  if (msg.type() === 'error') problems.push(`console: ${msg.text()}`);
});

const envelope = await encryptEnvelope(bundleForToday(), KEY, { iterations: 1000 });
let cdnHits = 0;
await context.route('https://raw.githubusercontent.com/**', (route) =>
  route.fulfill({ status: 200, headers: { ...CORS, 'content-type': 'application/json' }, body: JSON.stringify(envelope) }),
);
await context.route('https://cdn.jsdelivr.net/**', (route) => {
  cdnHits += 1;
  return route.abort();
});
await context.route('https://api.anthropic.com/**', async (route) => {
  const request = route.request();
  if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });
  const body = request.postDataJSON();
  claudeCalls.push({ body, headers: request.headers() });
  const answer = claudeAnswer(body);
  if (answer.json) return route.fulfill({ status: 200, headers: { ...CORS, 'content-type': 'application/json' }, body: JSON.stringify(answer.json) });
  return route.fulfill({ status: 200, headers: { ...CORS, 'content-type': 'text/event-stream' }, body: answer.sse });
});

const shot = (name) => page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true });
const tab = (name) => page.click(`#tab-${name}`);

try {
  await page.goto(BASE);
  await page.waitForSelector('#setup-card:not([hidden])');
  await shot('1-eerste-keer');

  await tab('instellingen');
  await page.fill('[data-path="profile.name"]', 'Sam');
  await page.fill('[data-path="profile.birthYear"]', '1988');
  await page.fill('[data-path="profile.goals"]', 'Halve marathon onder 1:45 in april');
  await page.fill('[data-path="targets.proteinG"]', '130');
  await page.fill('[data-path="claude.apiKey"]', 'sk-ant-smoke');
  await page.fill('#sync-owner', 'sam');
  await page.fill('#sync-repo', 'App-lading-car');
  await page.fill('#sync-key', KEY);
  await page.locator('#sync-key').blur();
  await shot('5-instellingen');

  await tab('vandaag');
  await page.click('#refresh');
  await page.waitForFunction(() => document.querySelector('#recovery-grid')?.textContent.includes('Rusthartslag'));
  await page.waitForSelector('#setup-card[hidden]', { state: 'attached' });
  await page.waitForFunction(() => /Gesynchroniseerd 40 min geleden/.test(document.querySelector('#status-line')?.textContent));
  assert.match(await page.textContent('#movement-grid'), /3×/);
  await page.click('#quick-today [data-index="0"]');
  await page.waitForFunction(() => document.querySelector('#intake-bars')?.textContent.includes('250 ml'));
  await shot('2-vandaag');

  await tab('activiteiten');
  await page.waitForSelector('.activity');
  assert.equal(await page.locator('.activity').count(), 3);
  await page.click('.activity >> nth=0');
  await page.waitForSelector('.activity__detail table');
  await shot('3-activiteiten');

  await tab('voeding');
  await page.fill('#intake-description', 'twee boterhammen met kaas');
  await page.click('#intake-estimate');
  await page.waitForFunction(() => document.querySelector('#field-kcal')?.value === '330');
  assert.equal(await page.inputValue('#intake-kind'), 'lunch');
  await shot('4a-voeding-schatting');
  await page.click('#intake-form button[type="submit"]');
  await page.waitForFunction(() => document.querySelectorAll('#entry-list .entry').length === 2);
  assert.match(await page.textContent('#entry-list'), /twee boterhammen met kaas.*330 kcal.*geschat/s);
  await shot('4-voeding');

  await tab('coach');
  await page.waitForSelector('#chat-suggestions .suggestion');
  await page.fill('#chat-input', 'Hoe sliep ik deze week?');
  await page.click('#chat-send');
  await page.waitForFunction(() => document.querySelector('#chat-log')?.textContent.includes('Houd je bedtijd vast'));
  await page.waitForSelector('#chat-send:not([hidden])');
  const log = await page.textContent('#chat-log');
  assert.match(log, /Hoe sliep ik deze week\?/);
  assert.match(log, /Slaap en herstel bekeken/);
  assert.match(log, /Ik bekijk je slaap/);
  assert.match(log, /gemiddeld 7,3 uur/);
  await shot('6-coach');

  // What went to Claude.
  const chat = claudeCalls.filter((c) => c.body.stream);
  assert.equal(chat.length, 2);
  const [first, second] = chat;
  assert.equal(first.body.model, 'claude-opus-5-5');
  assert.equal(first.headers['x-api-key'], 'sk-ant-smoke');
  assert.equal(first.headers['anthropic-dangerous-direct-browser-access'], 'true');
  assert.equal(
    first.headers['anthropic-beta'],
    'server-side-fallback-2026-07-01,thinking-display-updates-2026-08-18,thinking-binding-controls-2026-08-01',
  );
  assert.equal(first.body.fallbacks, 'default');
  assert.equal(first.body.thinking.display, 'updates');
  assert.equal(first.body.output_config.effort, 'medium');
  assert.equal(first.body.tools.length, 7);
  assert.ok(first.body.tools.every((t) => t.eager_input_streaming));
  assert.match(first.body.system, /Naam: Sam/);
  assert.match(first.body.system, /Doelen: Halve marathon/);
  assert.match(first.body.messages[0].content[0].text, /^<moment>/);
  assert.deepEqual(second.body.messages.slice(0, first.body.messages.length), first.body.messages, 'append-only');
  assert.equal(second.body.system, first.body.system);
  const replayed = second.body.messages[1].content[0];
  assert.equal(replayed.type, 'thinking');
  assert.equal(replayed.signature, 'sig-smoke-1', 'thinking blocks go back unchanged');
  assert.equal(second.body.messages[2].content[0].type, 'tool_result');
  const estimateCall = claudeCalls.find((c) => !c.body.stream);
  assert.equal(estimateCall.body.output_config.format.type, 'json_schema');
  assert.equal(estimateCall.body.output_config.effort, 'low');

  // Everything survives a reload.
  await page.reload();
  await page.waitForFunction(() => document.querySelector('#chat-log')?.textContent.includes('Houd je bedtijd vast'));
  await tab('voeding');
  assert.equal(await page.locator('#entry-list .entry').count(), 2);

  // Light mode.
  await page.emulateMedia({ colorScheme: 'light' });
  await tab('vandaag');
  await shot('7-vandaag-licht');

  assert.equal(cdnHits, 0, 'the bundled SDK is used, not the CDN');
  assert.deepEqual(problems, []);
  console.log(`Alles in orde. Schermafbeeldingen in ${OUT}`);
} catch (err) {
  await shot('fout').catch(() => {});
  console.error(err);
  console.error(problems.join('\n'));
  process.exitCode = 1;
} finally {
  await browser.close();
  server.kill();
}
