#!/usr/bin/env node
/**
 * End-to-end smoke test.
 *
 * Loads the real app in Chromium with stubbed price and weather responses,
 * clicks through all three tabs, changes a setting and checks that the plan
 * follows. Screenshots land next to the path given as the first argument.
 *
 *   npm start                     # in one terminal
 *   npm install --no-save playwright
 *   node tools/smoke.mjs /tmp/laadmoment
 *
 * Set CHROMIUM_PATH if Playwright cannot find a browser itself.
 */
import { chromium } from 'playwright';

const BASE = 'http://localhost:8080';
const OUT = process.argv[2] ?? '/tmp/shot';

// A plausible September day: cheap night, dear evening peak, decent sun.
const PRICES = [];
const base = new Date();
base.setHours(0, 0, 0, 0);
const shape = [
  0.07, 0.06, 0.055, 0.05, 0.052, 0.07, 0.11, 0.14, 0.13, 0.09, 0.05, 0.02,
  0.005, -0.01, 0.0, 0.03, 0.08, 0.13, 0.18, 0.21, 0.17, 0.12, 0.09, 0.075,
];
for (let d = 0; d < 2; d += 1) {
  for (let h = 0; h < 24; h += 1) {
    PRICES.push({
      price: shape[h] * (d === 0 ? 1 : 1.1),
      readingDate: new Date(base.getTime() + (d * 24 + h) * 3600000).toISOString(),
    });
  }
}

function weather() {
  const times = [];
  const gti = [];
  const ghi = [];
  const temp = [];
  const cloud = [];
  const sun = [];
  const offset = -base.getTimezoneOffset() * 60;
  for (let i = 0; i < 72; i += 1) {
    const t = new Date(base.getTime() + i * 3600000);
    const h = t.getHours();
    times.push(
      `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}T${String(h).padStart(2, '0')}:00`,
    );
    const day = Math.max(0, Math.sin(((h - 7) / 12) * Math.PI));
    gti.push(Math.round(day * 780));
    ghi.push(Math.round(day * 650));
    temp.push(12 + day * 8);
    cloud.push(Math.round(40 - day * 25));
    sun.push(day > 0.3 ? 3000 : 0);
  }
  const days = [0, 1, 2].map((d) => {
    const t = new Date(base.getTime() + d * 86400000);
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`;
  });
  return {
    utc_offset_seconds: offset,
    hourly: {
      time: times,
      global_tilted_irradiance: gti,
      shortwave_radiation: ghi,
      temperature_2m: temp,
      cloud_cover: cloud,
      sunshine_duration: sun,
    },
    daily: {
      time: days,
      sunrise: days.map((d) => `${d}T07:12`),
      sunset: days.map((d) => `${d}T19:48`),
      sunshine_duration: [32400, 25200, 18000],
    },
  };
}

const browser = await chromium.launch(
  process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {},
);
const context = await browser.newContext({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
  locale: 'nl-NL',
  timezoneId: 'Europe/Amsterdam',
});

await context.route('**/api.energyzero.nl/**', (route) =>
  route.fulfill({ json: { average: 0.09, Prices: PRICES } }),
);
await context.route('**/api.open-meteo.com/**', (route) => route.fulfill({ json: weather() }));

const page = await context.newPage();
const problems = [];
page.on('console', (msg) => {
  if (msg.type() === 'error') problems.push(`console: ${msg.text()}`);
});
page.on('pageerror', (err) => problems.push(`pageerror: ${err.message}`));

await page.goto(BASE, { waitUntil: 'networkidle' });
await page.waitForTimeout(700);

const report = {
  heroWindow: await page.locator('#hero-window').innerText(),
  heroSub: await page.locator('#hero-sub').innerText(),
  stats: await page.locator('#hero-stats > div').allInnerTexts(),
  status: await page.locator('#status-line').innerText(),
  messages: await page.locator('.message').allInnerTexts(),
  savings: await page.locator('#savings > div').allInnerTexts(),
  solarDays: await page.locator('#solar-days > div').allInnerTexts(),
};
await page.screenshot({ path: `${OUT}-vandaag.png`, fullPage: true });

await page.click('#tab-uren');
await page.waitForTimeout(400);
report.chartBars = await page.locator('#chart svg path').count();
report.tableRows = await page.locator('#hours-table tbody tr').count();
report.selectedRows = await page.locator('#hours-table tbody tr.is-selected').count();
await page.locator('#chart svg rect[role="button"]').nth(6).dispatchEvent('pointerdown');
report.readout = await page.locator('#chart-readout').innerText();
await page.screenshot({ path: `${OUT}-uren.png`, fullPage: true });

await page.click('#tab-instellingen');
await page.waitForTimeout(300);
report.modeCount = await page.locator('.mode').count();
report.arrayCount = await page.locator('.array').count();
await page.screenshot({ path: `${OUT}-instellingen.png`, fullPage: true });

// Change a setting and confirm the plan reacts.
await page.fill('input[name="car.currentSocPct"]', '10');
await page.fill('input[name="car.targetSocPct"]', '90');
await page.click('#settings-form button[type="submit"]');
await page.waitForTimeout(900);
report.afterChangeHero = await page.locator('#hero-window').innerText();
report.afterChangeStats = await page.locator('#hero-stats > div').allInnerTexts();

// Light mode rendering.
await context.close();
const light = await browser.newContext({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
  locale: 'nl-NL',
  timezoneId: 'Europe/Amsterdam',
  colorScheme: 'light',
});
await light.route('**/api.energyzero.nl/**', (route) =>
  route.fulfill({ json: { average: 0.09, Prices: PRICES } }),
);
await light.route('**/api.open-meteo.com/**', (route) => route.fulfill({ json: weather() }));
const lightPage = await light.newPage();
await lightPage.goto(BASE, { waitUntil: 'networkidle' });
await lightPage.waitForTimeout(700);
await lightPage.click('#tab-uren');
await lightPage.waitForTimeout(400);
await lightPage.screenshot({ path: `${OUT}-licht.png`, fullPage: true });

await browser.close();
console.log(JSON.stringify({ report, problems }, null, 2));
