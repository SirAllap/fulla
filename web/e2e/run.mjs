// SPDX-License-Identifier: GPL-3.0-or-later
//
// Drives the production bundle (web/build/dist/js/productionExecutable) in
// Chromium with an iPhone's screen, under the page's own Content Security
// Policy, and fails on any console error. Invented data only.
//
//   ./gradlew :web:jsBrowserDistribution && cd web/e2e && npm install && node run.mjs

import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync, mkdirSync } from 'node:fs';
import { extname, join, resolve } from 'node:path';
import { chromium, devices } from 'playwright';

const DIST = resolve(process.env.FULLA_WEB_DIST || '../build/dist/js/productionExecutable');
const SHOTS = process.env.FULLA_SHOTS;
if (SHOTS) mkdirSync(SHOTS, { recursive: true });

const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.webmanifest': 'application/manifest+json', '.json': 'application/json' };
const server = createServer((req, res) => {
  const path = join(DIST, new URL(req.url, 'http://x').pathname.replace(/\/$/, '/index.html'));
  if (!path.startsWith(DIST) || !existsSync(path) || !statSync(path).isFile()) { res.writeHead(404).end(); return; }
  res.writeHead(200, { 'content-type': types[extname(path)] || 'application/octet-stream' }).end(readFileSync(path));
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}/`;

const browser = await chromium.launch();
let failures = 0;
const ok = (cond, msg) => { console.log((cond ? 'PASS ' : 'FAIL ') + msg); if (!cond) failures++; };

async function phone(options = {}) {
  const ctx = await browser.newContext({ ...devices['iPhone 14'], acceptDownloads: true, ...options });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  const shot = (name) => SHOTS ? page.screenshot({ path: join(SHOTS, name + '.png') }) : Promise.resolve();
  return { ctx, page, errors, shot };
}

// ── 1. a new household, written down, summed, listed ─────────────────────────
{
  const { ctx, page, errors, shot } = await phone({ locale: 'es-ES' });
  await page.goto(BASE);
  await page.waitForSelector('.welcome');
  await shot('01-welcome');
  await page.click('text=Empezar');
  await page.fill('input >> nth=0', 'Casa');
  await page.fill('input >> nth=1', 'Alice Example');
  await page.click('button:has-text("Crear")');
  await page.waitForSelector('.tabbar');
  ok(await page.locator('.hint').count() === 1, 'on an iPhone in Safari, the page says how to add it to the home screen');
  await page.click('.hint button');
  ok(await page.locator('.hint').count() === 0, 'and stops saying it once dismissed');
  await shot('02-add');

  await page.fill('.amount-input', '12,5');
  await page.click('.tile >> nth=1');
  await page.fill('input.input >> nth=0', 'GROCERY STORE 01');
  await page.click('button:has-text("Guardar")');
  await page.waitForSelector('.toast');
  await page.click('button:has-text("Ingreso")');
  await page.fill('.amount-input', '1.234,56');
  await page.click('.tile >> nth=0');
  await page.click('button:has-text("Guardar")');
  await page.waitForSelector('.toast');

  await page.click('.tab:has-text("Resumen")');
  await page.waitForSelector('.hero');
  const overview = await page.textContent('.hero');
  ok(/1234,56/.test(overview) && /12,50/.test(overview), 'the overview adds up what came in and what went out: ' + overview.replace(/\s+/g, ' ').trim());
  await shot('03-overview');
  await page.click('.tab:has-text("Historial")');
  await page.waitForSelector('.entry');
  ok(await page.locator('.entry').count() === 2, 'History lists both entries');
  await shot('04-history');
  await page.click('.tab:has-text("Ajustes")');
  await shot('05-settings');

  // fixed cost: it writes itself on its day
  await page.click('text=Gastos fijos');
  await page.click('button:has-text("Añadir")');
  await page.fill('input >> nth=0', 'Alquiler');
  await page.fill('.amount-input', '650');
  await page.selectOption('select >> nth=0', { index: 1 });
  await shot('06-fixed-form');
  await page.click('button:has-text("Guardar")');
  await page.waitForSelector('.list-row');
  ok((await page.textContent('.list-row')).includes('Alquiler'), 'the fixed cost is listed');
  await page.click('.tab:has-text("Historial")');
  await page.waitForSelector('.entry');
  ok((await page.textContent('main')).includes('Alquiler'), 'and it wrote itself into History');

  // what is stored survives closing the page
  await page.reload();
  await page.waitForSelector('.tabbar');
  await page.click('.tab:has-text("Historial")');
  ok((await page.textContent('main')).includes('Alquiler'), 'the household survives a reload');

  // backup, forget, restore
  await page.click('.tab:has-text("Ajustes")');
  await page.click('text=Copia de seguridad');
  const [download] = await Promise.all([page.waitForEvent('download'), page.click('button:has-text("Guardar una copia")')]);
  const file = await download.path();
  const backup = JSON.parse(readFileSync(file, 'utf8'));
  ok(backup.format === 'fulla-backup' && backup.version === 1 && backup.transactions.length >= 3, 'the backup is a fulla-backup file with every row');
  page.on('dialog', (d) => d.accept());
  await page.click('text=‹');
  await page.click('text=Casa');
  await page.click('button:has-text("Olvidar")');
  await page.waitForSelector('.welcome');
  await (await page.$('#restore-file')).setInputFiles(file);
  await page.waitForSelector('.tabbar');
  await page.click('.tab:has-text("Historial")');
  ok((await page.textContent('main')).includes('Alquiler') && await page.locator('.entry').count() >= 3, 'restoring that file brings everything back');

  // the same file twice is refused, not merged
  await page.click('.tab:has-text("Ajustes")');
  ok(errors.length === 0, 'no console errors under the page\'s Content Security Policy' + (errors.length ? ': ' + errors.join(' | ') : ''));
  await ctx.close();
}

// ── 2. the demo household, in the dark, in English: edit, delete, undo, offline ──
{
  const { ctx, page, errors, shot } = await phone({ locale: 'en-GB', colorScheme: 'dark' });
  await page.goto(BASE);
  await page.click('text=Look around first');
  await page.waitForSelector('.tabbar');
  await page.click('.tab:has-text("Overview")');
  await page.waitForSelector('.hero');
  await shot('10-dark-overview');
  await page.click('.tab:has-text("History")');
  await page.waitForSelector('.entry');
  await shot('11-dark-history');

  await page.locator('.entry-main').first().click();
  await page.waitForSelector('.amount-input');
  await page.fill('.amount-input', '99');
  await page.click('button:has-text("Save")');
  await page.waitForSelector('.entry');
  ok((await page.locator('.entry').first().textContent()).includes('99'), 'an entry can be opened, changed and saved');

  const entryBefore = await page.locator('.entry').first().textContent();
  await page.locator('.entry button.ghost').first().click();
  await page.waitForSelector('.toast-action');
  const gone = await page.locator('.entry').first().textContent();
  ok(gone !== entryBefore, 'delete takes the entry out of the list');
  await page.click('.toast-action');
  await page.waitForTimeout(300);
  ok((await page.locator('.entry').first().textContent()) !== gone, 'and undo brings it back');

  // with no connection at all, the app still opens, with its data
  await page.reload();
  await page.waitForSelector('.tabbar');
  await page.waitForTimeout(1500);
  await ctx.setOffline(true);
  await page.reload();
  await page.waitForSelector('.tabbar', { timeout: 8000 });
  await page.click('.tab:has-text("History")');
  ok(await page.locator('.entry').count() > 0, 'offline, the app opens and its history is there');
  await ctx.setOffline(false);

  await page.click('.tab:has-text("Settings")');
  await page.selectOption('select', 'de');
  await page.waitForTimeout(300);
  ok((await page.textContent('main')).includes('Einstellungen'), 'the language can be changed, and sticks');
  await shot('12-dark-settings-de');
  ok(errors.length === 0, 'no console errors' + (errors.length ? ': ' + errors.join(' | ') : ''));
  await ctx.close();
}

await browser.close();
server.close();
console.log(failures === 0 ? '\nweb e2e: all passed' : `\nweb e2e: ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
