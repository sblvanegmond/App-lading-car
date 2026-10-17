process.env.TZ = 'Europe/Amsterdam';

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  TOOLS,
  buildRequest,
  buildSystemPrompt,
  contentForHistory,
  conversationView,
  createToolbox,
  requestOptions,
  runTurn,
  userMessage,
  validateInput,
} from '../coach/js/coach.js';
import { defaultSettings, mergeSettings } from '../coach/js/config.js';
import { createEntry, totals, entriesOn } from '../coach/js/nutrition.js';
import { sampleBundle } from './coach-fixtures.js';

const NOW = new Date(2026, 9, 17, 14, 32);

class FakeApiError extends Error {}
class FakeBadRequest extends FakeApiError {}

/** Turns a final message into the stream events the SDK would yield. */
function eventsFor(message) {
  const events = [{ type: 'message_start', message: { ...message, content: [] } }];
  message.content.forEach((block, index) => {
    if (block.type === 'text') {
      events.push({ type: 'content_block_start', index, content_block: { type: 'text', text: '' } });
      events.push({ type: 'content_block_delta', index, delta: { type: 'text_delta', text: block.text } });
    } else if (block.type === 'thinking') {
      events.push({ type: 'content_block_start', index, content_block: { type: 'thinking', thinking: '' } });
      events.push({ type: 'content_block_delta', index, delta: { type: 'thinking_delta', thinking: block.thinking } });
    } else {
      events.push({ type: 'content_block_start', index, content_block: block });
    }
    events.push({ type: 'content_block_stop', index });
  });
  events.push({ type: 'message_delta', delta: { stop_reason: message.stop_reason } });
  events.push({ type: 'message_stop' });
  return events;
}

/** A client whose beta.messages.stream() plays back scripted answers. */
function fakeClient(script) {
  const calls = [];
  const client = {
    beta: {
      messages: {
        stream(params) {
          calls.push(structuredClone(params));
          const next = script.shift();
          if (!next) throw new Error('script exhausted');
          return {
            async *[Symbol.asyncIterator]() {
              if (next instanceof Error) throw next;
              yield* eventsFor(next);
            },
            async finalMessage() {
              if (next instanceof Error) throw next;
              return next;
            },
          };
        },
      },
    },
  };
  return { client, calls };
}

function answer(content, stop_reason = 'end_turn') {
  return { id: 'msg', type: 'message', role: 'assistant', model: 'claude-opus-5-5', content, stop_reason };
}

function setup(overrides = {}) {
  const settings = mergeSettings(defaultSettings(), {
    profile: { name: 'Sam', birthYear: 1988, goals: 'Halve marathon onder 1:45 in april' },
    targets: { waterMl: 2000, proteinG: 130 },
    claude: { model: 'claude-opus-5-5' },
  });
  const state = { bundle: sampleBundle(), entries: [], memory: [], ...overrides };
  const execute = createToolbox({
    getBundle: () => state.bundle,
    getEntries: () => state.entries,
    addEntry: async (entry) => state.entries.push(entry),
    addMemory: async (text) => {
      const item = { id: 'm1', text, date: '2026-10-17' };
      state.memory.push(item);
      return item;
    },
    getSettings: () => settings,
    now: () => NOW,
  });
  return { settings, state, execute };
}

test('Opus and Sonnet get the optional features, Haiku a plain request', () => {
  const opus = requestOptions('claude-opus-5-5');
  assert.equal(opus.fallbacks, 'default');
  assert.deepEqual(opus.betas, [
    'server-side-fallback-2026-07-01',
    'thinking-display-updates-2026-08-18',
    'thinking-binding-controls-2026-08-01',
  ]);
  assert.deepEqual(opus.thinking, {
    type: 'adaptive',
    display: 'updates',
    block_binding: { prefix_mismatch_behavior: 'drop_block' },
  });
  assert.deepEqual(opus.output_config, { effort: 'medium' });

  const haiku = requestOptions('claude-haiku-5-5');
  assert.equal(haiku.betas, undefined, 'no empty beta header');
  assert.equal(haiku.fallbacks, undefined);
  assert.deepEqual(haiku.thinking, { type: 'adaptive' });

  const compat = requestOptions('claude-opus-5-5', { compat: true });
  assert.equal(compat.betas, undefined);
});

test('every tool streams its input and has a strict-looking schema', () => {
  for (const tool of TOOLS) {
    assert.equal(tool.eager_input_streaming, true, tool.name);
    assert.equal(tool.input_schema.additionalProperties, false, tool.name);
  }
  const request = buildRequest({ model: 'claude-opus-5-5', system: 'x' });
  assert.equal(request.max_tokens, 64000);
  assert.deepEqual(request.cache_control, { type: 'ephemeral' });
});

test('the system prompt carries profile, data snapshot and memory', () => {
  const { settings, state } = setup();
  state.entries.push(createEntry({ description: 'Glas water', kind: 'drinken', fluidMl: 250 }, { now: NOW }));
  const system = buildSystemPrompt({ settings, bundle: state.bundle, entries: state.entries, memory: [{ text: 'Heeft last van de linkerknie', date: '2026-10-01' }], now: NOW });
  assert.match(system, /Naam: Sam/);
  assert.match(system, /Leeftijd: 38/);
  assert.match(system, /Doelen: Halve marathon/);
  assert.match(system, /Gewicht: 77,1 kg \(Garmin, vr 16 okt\)/);
  assert.match(system, /Heeft last van de linkerknie/);
  assert.match(system, /Garmin za 17 okt: .*trainingsgereedheid 72/);
  assert.match(system, /Ochtendloop, hardlopen, 10,0 km, 50:12, hr 151 \[strava:101\]/);
  assert.match(system, /VO2max 52,4/);
  assert.match(system, /Voedingslog vandaag: 1 items, 250 ml vocht/);
  assert.equal(system, buildSystemPrompt({ settings, bundle: state.bundle, entries: state.entries, memory: [{ text: 'Heeft last van de linkerknie', date: '2026-10-01' }], now: NOW }));
  assert.match(system, /<overzicht moment="zaterdag 17 oktober 2026 om 14:32">/, 'the snapshot says when it was taken');

  const empty = buildSystemPrompt({ settings: defaultSettings(), bundle: null, now: NOW });
  assert.match(empty, /nog geen profiel/);
  assert.match(empty, /nog geen Strava- of Garmin-data/);
});

test('a turn with a tool call: append-only, thinking kept, events for the UI', async () => {
  const { execute } = setup();
  const first = answer(
    [
      { type: 'thinking', thinking: 'Ik bekijk je slaap van deze week.', signature: 'sig-1' },
      { type: 'tool_use', id: 'toolu_1', name: 'get_wellness', input: { from: '2026-10-15', to: '2026-10-17' } },
    ],
    'tool_use',
  );
  const second = answer([{ type: 'text', text: 'Je sliep gemiddeld 7u12.' }]);
  const { client, calls } = fakeClient([first, second]);

  const messages = [userMessage('Hoe sliep ik?', { now: NOW })];
  const appended = [];
  const events = [];
  const request = buildRequest({ model: 'claude-opus-5-5', system: 'SYSTEEM' });
  const result = await runTurn({
    client,
    isApiError: (e) => e instanceof FakeApiError,
    request,
    messages,
    execute,
    append: async (m) => appended.push(m),
    onEvent: (e) => events.push(e),
  });

  assert.equal(result.stopReason, 'end_turn');
  assert.equal(messages.length, 4);
  assert.deepEqual(appended.map((m) => m.role), ['assistant', 'user', 'assistant']);
  assert.deepEqual(messages[1].content, first.content, 'assistant content is stored exactly as returned');
  const toolResult = messages[2].content[0];
  assert.equal(toolResult.type, 'tool_result');
  assert.equal(toolResult.tool_use_id, 'toolu_1');
  const payload = JSON.parse(toolResult.content);
  assert.deepEqual(payload.days.map((d) => d.date), ['2026-10-15', '2026-10-16', '2026-10-17']);
  assert.equal(payload.days[0].hrvNormalRange, '52-68');
  assert.equal(payload.days[1].sleepH, 7.5);

  // The second request starts with exactly the first request's messages.
  assert.equal(calls.length, 2);
  assert.deepEqual(calls[1].messages.slice(0, calls[0].messages.length), calls[0].messages);
  assert.equal(calls[0].system, 'SYSTEEM');
  assert.deepEqual(calls[1].tools, calls[0].tools);

  assert.ok(events.some((e) => e.type === 'update' && /slaap/.test(e.text)));
  assert.ok(events.some((e) => e.type === 'tool_done' && e.name === 'get_wellness' && /3 dagen/.test(e.summary)));
  assert.equal(events.filter((e) => e.type === 'text').map((e) => e.text).join(''), 'Je sliep gemiddeld 7u12.');
});

test('invalid tool input is answered with an error result, not executed', async () => {
  const { execute, state } = setup();
  const { client } = fakeClient([
    answer([{ type: 'tool_use', id: 'toolu_x', name: 'log_intake', input: { kcal: 300 } }], 'tool_use'),
    answer([{ type: 'text', text: 'Wat heb je gegeten?' }]),
  ]);
  const messages = [userMessage('Log mijn lunch', { now: NOW })];
  await runTurn({ client, isApiError: () => false, request: { model: 'm' }, messages, execute });
  const result = messages[2].content[0];
  assert.equal(result.is_error, true);
  assert.match(result.content, /INVALID_JSON/);
  assert.equal(state.entries.length, 0);

  assert.equal(validateInput('get_wellness', { from: '2026-13-01', to: '2026-10-17' }).ok, false);
  assert.equal(validateInput('get_activities', { from: '2026-10-01', to: '2026-10-17', sport: 'tennis' }).ok, false);
  assert.equal(validateInput('get_activities', { from: '2026-10-01', to: '2026-10-17', sport: 'hardlopen', limit: 5 }).ok, true);
});

test('the coach can log food, with its own key names mapped', async () => {
  const { execute, state } = setup();
  const result = await execute('log_intake', {
    description: 'Twee boterhammen met kaas',
    kind: 'lunch',
    time: '12:30',
    kcal: 380,
    protein_g: 20,
    fluid_ml: 0,
  });
  assert.equal(result.effect.type, 'nutrition');
  const [entry] = state.entries;
  assert.equal(entry.date, '2026-10-17');
  assert.equal(entry.proteinG, 20);
  assert.equal(entry.source, 'coach');
  const t = totals(entriesOn(state.entries, '2026-10-17'));
  assert.equal(t.kcal, 380);
  assert.equal(t.kcalComplete, true);

  const remembered = await execute('remember', { note: 'Traint op dinsdag en donderdag' });
  assert.equal(remembered.effect.item.text, 'Traint op dinsdag en donderdag');
});

test('activity tools: list, filter and details', async () => {
  const { execute } = setup();
  const list = JSON.parse((await execute('get_activities', { from: '2026-10-01', to: '2026-10-17' })).content);
  assert.equal(list.total, 3);
  assert.equal(list.activities[0].pace, '5:01 /km');
  assert.equal(list.activities[0].trainingLoad, 95);
  const runs = JSON.parse((await execute('get_activities', { from: '2026-10-01', to: '2026-10-17', sport: 'hardlopen' })).content);
  assert.equal(runs.total, 1);
  const detail = JSON.parse((await execute('get_activity_details', { id: 'strava:101' })).content);
  assert.equal(detail.splits.length, 2);
  assert.equal(detail.splits[1].pace, '5:00 /km');
  const missing = await execute('get_activity_details', { id: 'strava:999' });
  assert.equal(missing.isError, true);

  const summary = JSON.parse((await execute('get_training_summary', { weeks: 2 })).content);
  assert.equal(summary.weeks[0].count, 3);
  assert.equal(summary.garminStatus.vo2max, 52.4);
});

test('without synced data the tools say so instead of failing', async () => {
  const { execute } = setup({ bundle: null });
  const result = await execute('get_wellness', { from: '2026-10-10', to: '2026-10-17' });
  assert.equal(result.isError, undefined);
  assert.match(result.content, /geen Garmin-gegevens/);
});

test('after a fallback, reasoning and tool calls before the switch are not sent back', () => {
  const content = [
    { type: 'thinking', thinking: '', signature: 'a' },
    { type: 'text', text: 'Begin van het ' },
    { type: 'tool_use', id: 't1', name: 'get_wellness', input: {} },
    { type: 'fallback', from: { model: 'claude-opus-5-5' }, to: { model: 'claude-opus-5' } },
    { type: 'text', text: 'antwoord.' },
  ];
  assert.deepEqual(contentForHistory(content).map((b) => b.type), ['text', 'text']);
  const plain = [{ type: 'thinking', thinking: '', signature: 'a' }, { type: 'text', text: 'x' }];
  assert.equal(contentForHistory(plain), plain);
});

test('a refusal keeps nothing of the partial answer', async () => {
  const { execute } = setup();
  const { client } = fakeClient([{ ...answer([{ type: 'text', text: 'Hal' }], 'refusal'), stop_details: { category: 'bio' } }]);
  const messages = [userMessage('Vraag', { now: NOW })];
  const events = [];
  const result = await runTurn({ client, isApiError: () => false, request: {}, messages, execute, onEvent: (e) => events.push(e) });
  assert.equal(result.stopReason, 'refusal');
  assert.equal(messages.length, 1);
  assert.equal(events.at(-1).details.category, 'bio');
});

test('a 400 on the full request falls back once to the plain request', async () => {
  const { execute } = setup();
  const { client, calls } = fakeClient([new FakeBadRequest('unknown beta'), answer([{ type: 'text', text: 'Hoi' }])]);
  const messages = [userMessage('Hoi', { now: NOW })];
  const result = await runTurn({
    client,
    isApiError: (e) => e instanceof FakeApiError,
    isBadRequest: (e) => e instanceof FakeBadRequest,
    request: buildRequest({ model: 'claude-opus-5-5', system: 's' }),
    compatRequest: buildRequest({ model: 'claude-opus-5-5', system: 's', compat: true }),
    messages,
    execute,
  });
  assert.equal(result.compat, true);
  assert.ok(calls[0].betas);
  assert.equal(calls[1].betas, undefined);
  assert.equal(messages.length, 2);
});

test('an unparseable tool input is retried, API errors are not', async () => {
  const { execute } = setup();
  const { client, calls } = fakeClient([new SyntaxError('bad json'), answer([{ type: 'text', text: 'Oké' }])]);
  const messages = [userMessage('x', { now: NOW })];
  await runTurn({ client, isApiError: (e) => e instanceof FakeApiError, request: {}, messages, execute });
  assert.equal(calls.length, 2);

  const failing = fakeClient([new FakeApiError('rate limited')]);
  await assert.rejects(
    runTurn({ client: failing.client, isApiError: (e) => e instanceof FakeApiError, request: {}, messages: [userMessage('x')], execute }),
    /rate limited/,
  );
  assert.equal(failing.calls.length, 1);
});

test('the chat view hides the moment block and tool traffic', () => {
  const messages = [
    userMessage('Hoe sliep ik?', { now: NOW, images: [{ mediaType: 'image/jpeg', data: 'AAAA' }] }),
    { role: 'assistant', content: [{ type: 'thinking', thinking: 'Even kijken.', signature: 's' }, { type: 'tool_use', id: 't', name: 'get_wellness', input: {} }] },
    { role: 'user', content: [{ type: 'tool_result', tool_use_id: 't', content: '{}' }] },
    { role: 'assistant', content: [{ type: 'thinking', thinking: '', signature: 's' }, { type: 'text', text: 'Goed!' }] },
  ];
  assert.deepEqual(conversationView(messages), [
    { role: 'user', text: 'Hoe sliep ik?', images: 1 },
    {
      role: 'assistant',
      parts: [
        { type: 'update', text: 'Even kijken.' },
        { type: 'tool', name: 'get_wellness', input: {} },
        { type: 'text', text: 'Goed!' },
      ],
    },
  ]);
  assert.match(messages[0].content[0].text, /^<moment>zaterdag 17 oktober 2026 om 14:32<\/moment>$/);
});
