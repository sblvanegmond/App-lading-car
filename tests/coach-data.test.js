process.env.TZ = 'Europe/Amsterdam';

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { decryptEnvelope, encryptEnvelope, generateKey, CryptoError } from '../coach/js/crypto.js';
import { repoFromLocation, resolveRepo, mergeSettings, defaultSettings } from '../coach/js/config.js';
import {
  dataSource,
  loadBundle,
  mergeActivities,
  sportGroup,
  weeklySummary,
  wellnessSummary,
  wellnessInRange,
  DataError,
} from '../coach/js/data.js';
import { formatClock, formatHours, formatPace, formatDistance, weekStart, addDays, formatDay } from '../coach/js/format.js';
import { renderMarkdown } from '../coach/js/markdown.js';
import { sampleBundle } from './coach-fixtures.js';

const KEY = 'test-sleutel-van-voldoende-lengte';

test('decrypts what the Python sync job encrypted', async () => {
  const envelope = JSON.parse(readFileSync(new URL('./fixtures/coach-bundle.enc.json', import.meta.url), 'utf8'));
  const value = await decryptEnvelope(envelope, 'fixture-sleutel-voor-tests');
  assert.equal(value.tekst, 'Hardlopen in Grootegast — €5, ü');
  assert.deepEqual(value.getallen, [1, 2.5, null]);
});

test('a wrong key is a clear error, not garbage', async () => {
  const envelope = await encryptEnvelope({ a: 1 }, KEY, { iterations: 1000 });
  await assert.rejects(decryptEnvelope(envelope, `${KEY}x`), CryptoError);
  await assert.rejects(decryptEnvelope(envelope, 'kort'), /minstens 16/);
});

test('generated keys are long enough and url-safe', () => {
  const key = generateKey();
  assert.equal(key.length, 24);
  assert.match(key, /^[A-Za-z0-9_-]+$/);
  assert.notEqual(key, generateKey());
});

test('the repository follows from the GitHub Pages address', () => {
  assert.deepEqual(repoFromLocation({ hostname: 'sam.github.io', pathname: '/App-lading-car/coach/' }), {
    owner: 'sam',
    repo: 'App-lading-car',
  });
  assert.equal(repoFromLocation({ hostname: 'localhost', pathname: '/coach/' }), null);
  assert.deepEqual(resolveRepo({ owner: 'a', repo: 'b' }, { hostname: 'sam.github.io', pathname: '/x/' }), { owner: 'a', repo: 'b' });
});

test('without a token data comes from raw, with a token from the API', () => {
  const location = { hostname: 'sam.github.io', pathname: '/App-lading-car/coach/' };
  const sync = mergeSettings(defaultSettings(), {}).sync;
  assert.equal(dataSource(sync, location).url, 'https://raw.githubusercontent.com/sam/App-lading-car/coach-data/bundle.enc.json');
  const withToken = dataSource({ ...sync, githubToken: 'ghp_x' }, location);
  assert.match(withToken.url, /^https:\/\/api\.github\.com\/repos\/sam\/App-lading-car\/contents\/bundle\.enc\.json\?ref=coach-data$/);
  assert.equal(withToken.headers.Authorization, 'Bearer ghp_x');
});

test('loadBundle decrypts once and skips unchanged data', async () => {
  const envelope = await encryptEnvelope(sampleBundle(), KEY, { iterations: 1000 });
  let fetches = 0;
  const fetchImpl = async () => {
    fetches += 1;
    return new Response(JSON.stringify(envelope), { status: 200 });
  };
  const sync = { owner: 'sam', repo: 'r', branch: 'coach-data', key: KEY };
  const first = await loadBundle({ sync, fetchImpl });
  assert.equal(first.changed, true);
  assert.equal(first.bundle.version, 1);
  const second = await loadBundle({ sync, fetchImpl, cached: first });
  assert.equal(second.changed, false);
  assert.equal(fetches, 2);

  const missing = async () => new Response('Not Found', { status: 404 });
  await assert.rejects(loadBundle({ sync, fetchImpl: missing }), (err) => err instanceof DataError && /Coach synchroniseren/.test(err.message));
});

test('Strava and Garmin copies of one session are merged', () => {
  const activities = mergeActivities(sampleBundle());
  const run = activities.find((a) => a.id === 'strava:101');
  assert.deepEqual(run.sources, ['strava', 'garmin']);
  assert.equal(run.name, 'Ochtendloop');
  assert.equal(run.trainingLoad, 95, 'Garmin load is added to the Strava activity');
  assert.equal(run.aerobicTE, 3.1);
  const strength = activities.find((a) => a.id === 'garmin:902');
  assert.equal(strength.sport, 'strength', 'a watch-only session still shows up');
  assert.equal(activities.length, 3);
  assert.deepEqual(
    activities.map((a) => a.date),
    [...activities.map((a) => a.date)].sort().reverse(),
  );
});

test('sport groups cover Strava and Garmin names', () => {
  assert.equal(sportGroup('TrailRun'), 'run');
  assert.equal(sportGroup('treadmill_running'), 'run');
  assert.equal(sportGroup('GravelRide'), 'ride');
  assert.equal(sportGroup('indoor_cycling'), 'ride');
  assert.equal(sportGroup('lap_swimming'), 'swim');
  assert.equal(sportGroup('Hike'), 'walk');
  assert.equal(sportGroup('WeightTraining'), 'strength');
  assert.equal(sportGroup('strength_training'), 'strength');
  assert.equal(sportGroup('Golf'), 'other');
});

test('weekly summary counts per Monday-to-Sunday week', () => {
  const weeks = weeklySummary(mergeActivities(sampleBundle()), { weeks: 2, today: '2026-10-17' });
  assert.equal(weeks[0].week, '2026-10-12');
  assert.equal(weeks[0].count, 3);
  assert.equal(weeks[0].bySport.run.count, 1);
  assert.equal(weeks[0].load, 95 + 40);
  assert.equal(weeks[1].count, 0);
});

test('wellness averages ignore missing values', () => {
  const days = wellnessInRange(sampleBundle(), '2026-10-15', '2026-10-17');
  const summary = wellnessSummary(days);
  assert.equal(summary.days, 3);
  assert.equal(summary.rhr, 49);
  assert.equal(Math.round(summary.sleepS), Math.round((25200 + 27000) / 2));
});

test('Dutch formatting', () => {
  assert.equal(formatClock(3012), '50:12');
  assert.equal(formatClock(3723), '1:02:03');
  assert.equal(formatHours(26100), '7u15');
  assert.equal(formatHours(2700), '45 min');
  assert.equal(formatPace(1000 / 300), '5:00 /km');
  assert.equal(formatDistance(10012), '10,0 km');
  assert.equal(formatDistance(850), '850 m');
  assert.equal(weekStart('2026-10-17'), '2026-10-12');
  assert.equal(addDays('2026-10-31', 1), '2026-11-01');
  assert.equal(formatDay('2026-10-17'), 'za 17 okt');
});

test('markdown is escaped before it is formatted', () => {
  assert.equal(renderMarkdown('**Goed** <script>x</script>'), '<p><strong>Goed</strong> &lt;script&gt;x&lt;/script&gt;</p>');
  assert.equal(renderMarkdown('Doen:\n- rust\n- *slapen*'), '<p>Doen:</p><ul><li>rust</li><li><em>slapen</em></li></ul>');
  assert.equal(renderMarkdown('1. een\n2. twee'), '<ol><li>een</li><li>twee</li></ol>');
});
