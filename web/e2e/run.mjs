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

// Presses the on-screen keys that spell a number: "1234,5".
async function type(page, digits) {
  for (const ch of digits) await page.locator('.keys .key', { hasText: new RegExp('^' + ch.replace(/[+.,]/g, '\\$&') + '$') }).first().click();
}
const gear = (page) => page.click('.header .icon-btn[aria-label]:last-child');
const back = (page) => page.click('.header.back .icon-btn');
const row = (page, text) => page.locator('.row', { hasText: text }).first();

// ── 1. a new household, written down, summed, listed ─────────────────────────
{
  const { ctx, page, errors, shot } = await phone({ locale: 'es-ES' });
  await page.goto(BASE);
  await page.waitForSelector('.welcome');
  await shot('01-welcome');
  await page.click('text=Empezar');
  await page.fill('input >> nth=0', 'Casa');
  await page.fill('input >> nth=1', 'Alice Example');
  await shot('01b-create');
  await page.click('button:has-text("Crear")');
  await page.waitForSelector('.tabbar');
  await shot('02-add');

  await type(page, '12,5');
  await page.click('.tile >> nth=1');
  await page.click('.key.save');
  await page.waitForSelector('.toast');
  await page.click('button.chip:has-text("Ingreso")');
  await type(page, '1234,56');
  await page.click('.tile >> nth=0');
  await page.click('.key.save');
  await page.waitForSelector('.toast');

  await page.click('.tab:has-text("Resumen")');
  await page.waitForSelector('.figures');
  ok(await page.locator('.hint').count() === 1, 'on an iPhone in Safari, the overview says how to add the page to the home screen');
  await page.click('.hint button');
  ok(await page.locator('.hint').count() === 0, 'and stops saying it once dismissed');
  const figures = await page.textContent('.figures');
  ok(/1234,56/.test(figures) && /12,50/.test(figures), 'the overview adds up what came in and what went out: ' + figures.replace(/\s+/g, ' ').trim());
  await shot('03-overview');
  await page.click('.tab:has-text("Historial")');
  await page.waitForSelector('.wrap-row');
  ok(await page.locator('.wrap-row').count() === 2, 'History lists both entries');
  await shot('04-history');
  await gear(page);
  await shot('05-settings');
  const sections = await page.locator('.rows .row .title').allTextContents();
  ok(['Hogar', 'Personas', 'Categorías', 'Cuentas', 'Presupuestos', 'Gastos fijos', 'Copia de seguridad', 'Sincronización'].every((n) => sections.includes(n)), 'Settings has the sections of the Android app: ' + sections.join(', '));

  // a category with a subcategory
  await row(page, 'Categorías').click();
  await page.click('text=Añadir subcategoría');
  await page.click('.sheet .row >> nth=0');
  await page.fill('.sheet input >> nth=0', 'Fruta');
  await page.click('.sheet button:has-text("Guardar")');
  await page.waitForSelector('.glyph.sub');
  ok((await page.textContent('main')).includes('Fruta'), 'a subcategory can be added under a category');
  await back(page);

  // fixed cost with an end: it writes itself on its day, and stops
  await row(page, 'Gastos fijos').click();
  await page.click('text=Añadir un gasto fijo');
  await page.fill('input >> nth=0', 'Alquiler');
  await page.fill('input >> nth=1', '650');
  await page.click('.tile >> nth=0');
  await page.click('button.chip:has-text("Tras varios pagos")');
  await page.fill('input >> nth=2', '12');
  await shot('06-fixed-form');
  ok(await page.locator('.summary').count() === 1, 'the form says what saving will do');
  await page.click('main .btn.primary');
  await page.waitForSelector('.row:has-text("Alquiler")');
  ok((await page.textContent('main')).includes('Alquiler'), 'the fixed cost is listed, with how far it is');
  await back(page); await back(page);
  await page.click('.tab:has-text("Historial")');
  await page.waitForSelector('.wrap-row');
  ok((await page.textContent('main')).includes('Alquiler'), 'and it wrote itself into History');

  // what is stored survives closing the page
  await page.reload();
  await page.waitForSelector('.tabbar');
  await page.click('.tab:has-text("Historial")');
  ok((await page.textContent('main')).includes('Alquiler'), 'the household survives a reload');

  // backup, forget, restore
  await gear(page);
  await row(page, 'Copia de seguridad').click();
  const [download] = await Promise.all([page.waitForEvent('download'), page.click('button:has-text("Guardar una copia")')]);
  const file = await download.path();
  const backup = JSON.parse(readFileSync(file, 'utf8'));
  ok(backup.format === 'fulla-backup' && backup.version === 1 && backup.transactions.length >= 3, 'the backup is a fulla-backup file with every row');
  await back(page);
  await row(page, 'Sincronización').click();
  await row(page, 'Olvidar este hogar').click();
  await page.click('.sheet button:has-text("Olvidar")');
  await page.waitForSelector('.welcome');
  await page.click('text=Más opciones');
  await (await page.$('#restore-file')).setInputFiles(file);
  await page.waitForSelector('.tabbar');
  await page.click('.tab:has-text("Historial")');
  ok((await page.textContent('main')).includes('Alquiler') && await page.locator('.wrap-row').count() >= 3, 'restoring that file brings everything back');
  ok(errors.length === 0, 'no console errors under the page\'s Content Security Policy' + (errors.length ? ': ' + errors.join(' | ') : ''));
  await ctx.close();
}

// ── 2. the demo household, in the dark, in English: edit, delete, undo, offline ──
{
  const { ctx, page, errors, shot } = await phone({ locale: 'en-GB', colorScheme: 'dark' });
  await page.goto(BASE);
  await page.click('text=More options');
  await page.click('text=Look around first');
  await page.waitForSelector('.tabbar');
  await page.click('.tab:has-text("Overview")');
  await page.waitForSelector('.figures');
  await shot('10-dark-overview');
  await page.click('.tab:has-text("History")');
  await page.waitForSelector('.wrap-row');
  await shot('11-dark-history');

  await page.locator('.wrap-row .row.tap').first().click();
  await page.waitForSelector('.keypad');
  await page.click('.key[aria-label="Delete a digit"]');
  await type(page, '9');
  await page.click('.key.save');
  await page.waitForSelector('.wrap-row');
  ok((await page.locator('.wrap-row').first().textContent()).includes('9'), 'an entry can be opened, changed and saved');

  const entryBefore = await page.locator('.wrap-row').first().textContent();
  await page.locator('.wrap-row .icon-btn').first().click();
  await page.waitForSelector('.toast-action');
  const gone = await page.locator('.wrap-row').first().textContent();
  ok(gone !== entryBefore, 'delete takes the entry out of the list');
  await page.click('.toast-action');
  await page.waitForTimeout(300);
  ok((await page.locator('.wrap-row').first().textContent()) !== gone, 'and undo brings it back');

  // with no connection at all, the app still opens, with its data
  await page.reload();
  await page.waitForSelector('.tabbar');
  await page.waitForTimeout(1500);
  await ctx.setOffline(true);
  await page.reload();
  await page.waitForSelector('.tabbar', { timeout: 8000 });
  await page.click('.tab:has-text("History")');
  ok(await page.locator('.wrap-row').count() > 0, 'offline, the app opens and its history is there');
  await ctx.setOffline(false);

  await gear(page);
  await row(page, 'Appearance').click();
  await page.selectOption('select', 'de');
  await page.waitForTimeout(300);
  ok((await page.textContent('main')).includes('Sprache'), 'the language can be changed, and sticks');
  await shot('12-dark-settings-de');
  ok(errors.length === 0, 'no console errors' + (errors.length ? ': ' + errors.join(' | ') : ''));
  await ctx.close();
}

await browser.close();
server.close();
console.log(failures === 0 ? '\nweb e2e: all passed' : `\nweb e2e: ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
