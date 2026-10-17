#!/usr/bin/env node
/**
 * Connect your own Strava API application once and print the refresh token
 * for the GitHub secret STRAVA_REFRESH_TOKEN.
 *
 *   npm run coach:strava-login
 *
 * The refresh token on strava.com/settings/api only carries the `read` scope,
 * which does not include your activities. This walks through the real OAuth
 * flow with a tiny server on localhost, so the token gets
 * `activity:read_all` and `profile:read_all` as well.
 *
 * In the Strava app settings, "Authorization Callback Domain" must be
 * `localhost` for this to work.
 */

import { createServer } from 'node:http';
import { createInterface } from 'node:readline/promises';
import { stdin, stdout, env, exit } from 'node:process';
import { spawn } from 'node:child_process';

const PORT = Number(env.STRAVA_LOGIN_PORT) || 8721;
const REDIRECT = `http://localhost:${PORT}/callback`;
const SCOPES = ['read', 'activity:read_all', 'profile:read_all'];

async function ask(rl, question, fallback) {
  if (fallback) return fallback;
  const answer = (await rl.question(question)).trim();
  if (!answer) {
    console.error('Geen waarde ingevuld, gestopt.');
    exit(1);
  }
  return answer;
}

function openBrowser(url) {
  const command = process.platform === 'darwin' ? 'open' : process.platform === 'win32' ? 'cmd' : 'xdg-open';
  const args = process.platform === 'win32' ? ['/c', 'start', '""', url] : [url];
  try {
    spawn(command, args, { stdio: 'ignore', detached: true }).on('error', () => {}).unref();
  } catch {
    /* printing the link is enough */
  }
}

function waitForCode() {
  return new Promise((resolve, reject) => {
    const server = createServer((req, res) => {
      const url = new URL(req.url, REDIRECT);
      if (url.pathname !== '/callback') {
        res.writeHead(404).end();
        return;
      }
      const error = url.searchParams.get('error');
      const code = url.searchParams.get('code');
      const granted = (url.searchParams.get('scope') || '').split(',');
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(
        error || !code
          ? '<p>Strava gaf geen toestemming. Je kunt dit tabblad sluiten.</p>'
          : '<p>Gelukt. Ga terug naar de terminal; dit tabblad mag dicht.</p>',
      );
      server.close();
      if (error || !code) reject(new Error(`Strava weigerde: ${error || 'geen code ontvangen'}`));
      else resolve({ code, granted });
    });
    server.on('error', reject);
    server.listen(PORT, '127.0.0.1');
  });
}

async function main() {
  const rl = createInterface({ input: stdin, output: stdout });
  console.log('Strava koppelen voor de coach-app.\n');
  console.log('Je hebt de Client ID en het Client Secret nodig van https://www.strava.com/settings/api');
  console.log('Zet daar "Authorization Callback Domain" op: localhost\n');
  const clientId = await ask(rl, 'Client ID: ', env.STRAVA_CLIENT_ID);
  const clientSecret = await ask(rl, 'Client Secret: ', env.STRAVA_CLIENT_SECRET);
  rl.close();

  const authorize = new URL('https://www.strava.com/oauth/authorize');
  authorize.search = new URLSearchParams({
    client_id: clientId,
    response_type: 'code',
    redirect_uri: REDIRECT,
    approval_prompt: 'force',
    scope: SCOPES.join(','),
  }).toString();

  const pending = waitForCode();
  console.log(`\nOpen deze link als je browser niet vanzelf opengaat:\n\n  ${authorize}\n`);
  console.log('Laat alle vinkjes aan staan en klik op Autoriseren.\n');
  openBrowser(authorize.toString());

  const { code, granted } = await pending;
  const missing = SCOPES.filter((scope) => !granted.includes(scope));
  if (missing.includes('activity:read_all')) {
    console.error('Je hebt geen toegang tot je activiteiten gegeven. Probeer het opnieuw en laat alle vinkjes aan.');
    exit(1);
  }

  const response = await fetch('https://www.strava.com/oauth/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      client_id: clientId,
      client_secret: clientSecret,
      code,
      grant_type: 'authorization_code',
    }),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok || !data.refresh_token) {
    console.error(`Token ophalen mislukt (${response.status}): ${JSON.stringify(data)}`);
    exit(1);
  }

  const who = [data.athlete?.firstname, data.athlete?.lastname].filter(Boolean).join(' ');
  console.log(`Gekoppeld${who ? ` als ${who}` : ''}.`);
  if (missing.length) console.log(`Let op, niet toegestaan: ${missing.join(', ')}. De coach mist dan je hartslagzones.`);
  console.log('\nMaak in GitHub deze drie secrets aan (Settings → Secrets and variables → Actions):\n');
  console.log(`  STRAVA_CLIENT_ID      ${clientId}`);
  console.log('  STRAVA_CLIENT_SECRET  (het Client Secret dat je net invulde)');
  console.log(`  STRAVA_REFRESH_TOKEN  ${data.refresh_token}\n`);
}

main().catch((err) => {
  console.error(err.message || err);
  exit(1);
});
