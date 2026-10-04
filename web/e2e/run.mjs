// SPDX-License-Identifier: GPL-3.0-or-later
//
// Drives the production bundle (web/build/dist/js/productionExecutable) in
// Chromium with an iPhone's screen, under the page's own Content Security
// Policy, and fails on any console error. Invented data only.
//
//   ./gradlew :web:jsBrowserDistribution && cd web/e2e && npm install && node run.mjs

import { createHash } from 'node:crypto';
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
// The getting-started guide: setup sheets, then a tour. Leaves it by its Skip button.
async function skipGuide(page) {
  for (let i = 0; i < 5; i++) {
    const skip = page.locator('.sheet .btn.quiet:last-child, .tour-skip');
    try { await skip.first().waitFor({ timeout: 1500 }); } catch { break; }
    await skip.first().click();
    await page.waitForTimeout(150);
  }
  // The tour leaves the app on whichever tab it last named.
  await page.click('.tabbar .tab:first-child');
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
  await page.waitForSelector('.sheet');
  ok(await page.locator('.sheet .row').count() >= 3, 'a new household starts with the setup guide: when does the month start');
  await shot('02a-guide-month');
  await page.click('.sheet .btn.primary');
  await page.waitForSelector('.sheet input');
  await shot('02b-guide-accounts');
  await page.click('.sheet .btn.quiet:last-child');
  await page.waitForSelector('.tour-card');
  ok(await page.locator('.tour-hole').count() === 1, 'then a tour, with a hole over the part it names');
  await shot('02c-guide-tour');
  await page.click('.tour-skip');
  await page.waitForSelector('.tour', { state: 'detached' });
  await page.click('.tabbar .tab:first-child');
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
  ok(/1\.234,56/.test(figures) && /12,50/.test(figures), 'the overview adds up what came in and what went out: ' + figures.replace(/\s+/g, ' ').trim());
  await shot('03-overview');
  await page.click('.tab:has-text("Historial")');
  await page.waitForSelector('.history .row');
  ok(await page.locator('.history .row').count() === 2, 'History lists both entries');
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

  // a custom field, a trip: forms made of sheets
  await row(page, 'Campos').click();
  await page.click('text=Añadir un campo');
  await page.fill('.sheet input >> nth=0', 'Tienda');
  await page.click('.sheet .btn.primary');
  await page.waitForSelector('.row:has-text("Tienda")');
  ok(true, 'a custom field can be added');
  await back(page);
  await row(page, 'Viajes').click();
  await page.click('main .btn.primary');
  await page.fill('.sheet input >> nth=0', 'Lisboa');
  await page.click('.sheet .btn.primary');
  await page.waitForSelector('.row:has-text("Lisboa")');
  ok(true, 'a trip can be added');
  await back(page);

  // an account in debt: the amount and a switch (a phone's decimal keypad has no minus key); what is not an amount is said so, not kept
  await row(page, 'Cuentas').click();
  await page.click('text=Añadir una cuenta');
  await page.fill('.sheet input >> nth=0', 'Tarjeta');
  await page.fill('.sheet input >> nth=1', 'abc');
  ok(await page.locator('.sheet .problem').first().isVisible() && await page.locator('.sheet .btn.primary[disabled]').count() === 1, 'an opening balance that is not an amount is said so, and Save waits');
  await page.fill('.sheet input >> nth=1', '300.5');
  await page.locator('.sheet .row:has-text("en deuda") input').check();
  ok(await page.locator('.sheet .btn.primary[disabled]').count() === 0 && !(await page.locator('.sheet .problem').first().isVisible()), 'a dot is a decimal on a page that writes commas, and the debt switch lets Save through');
  await page.click('.sheet .btn.primary');
  await page.waitForSelector('.row:has-text("Tarjeta")');
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
  await page.waitForSelector('.history .row');
  ok((await page.textContent('main')).includes('Alquiler'), 'and it wrote itself into History');

  // the card is below zero, and renaming it leaves what it holds alone
  await page.click('.tab:has-text("Saldos")');
  const card = await row(page, 'Tarjeta').textContent();
  ok(/[-−]\s*300,50/.test(card), 'an account in debt holds a negative balance: ' + card.replace(/\s+/g, ' ').trim());
  await gear(page); await row(page, 'Cuentas').click(); await row(page, 'Tarjeta').click();
  ok(await page.inputValue('.sheet input >> nth=1') === '300,50' && await page.locator('.sheet .row:has-text("en deuda") input').isChecked(), 'reopened, it shows 300,50 with the debt switch on');
  await page.fill('.sheet input >> nth=0', 'Tarjeta azul');
  await page.click('.sheet .btn.primary');
  await page.waitForSelector('.row:has-text("Tarjeta azul")');
  await back(page); await back(page);
  await page.click('.tab:has-text("Saldos")');
  ok(/[-−]\s*300,50/.test(await row(page, 'Tarjeta azul').textContent()), 'renamed, it still holds minus 300,50');
  await page.click('.tab:has-text("Historial")');

  // what is stored survives closing the page
  await page.reload();
  await page.waitForSelector('.tabbar');
  await page.click('.tab:has-text("Historial")');
  ok((await page.textContent('main')).includes('Alquiler'), 'the household survives a reload');

  // backup, forget, restore
  await gear(page);
  await row(page, 'Copia de seguridad').click();
  const [download] = await Promise.all([page.waitForEvent('download'), page.click('.row:has-text("Guardar una copia")')]);
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
  ok((await page.textContent('main')).includes('Alquiler') && await page.locator('.history .row').count() >= 3, 'restoring that file brings everything back');
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
  await skipGuide(page);
  await page.click('.tab:has-text("Overview")');
  await page.waitForSelector('.figures');
  await shot('10-dark-overview');
  await page.click('.tab:has-text("History")');
  await page.waitForSelector('.history .row');
  await shot('11-dark-history');

  await page.locator('.history .row.tap').first().click();
  await page.waitForSelector('.keypad');
  await page.click('.key[aria-label="Delete a digit"]');
  await type(page, '9');
  await page.click('.key.save');
  await page.waitForSelector('.history .row');
  ok((await page.locator('.history .row').first().textContent()).includes('9'), 'an entry can be opened, changed and saved');

  const entryBefore = await page.locator('.history .row').first().textContent();
  await page.locator('.history .row.tap').first().click();
  await page.waitForSelector('.keypad');
  await page.click('.header .icon-btn[aria-label="Delete"]');
  await page.waitForSelector('.toast-action');
  const gone = await page.locator('.history .row').first().textContent();
  ok(gone !== entryBefore, 'delete takes the entry out of the list');
  await page.click('.toast-action');
  await page.waitForTimeout(300);
  ok((await page.locator('.history .row').first().textContent()) !== gone, 'and undo brings it back');

  // with no connection at all, the app still opens, with its data
  await page.reload();
  await page.waitForSelector('.tabbar');
  await page.waitForTimeout(1500);
  await ctx.setOffline(true);
  await page.reload();
  await page.waitForSelector('.tabbar', { timeout: 8000 });
  await page.click('.tab:has-text("History")');
  ok(await page.locator('.history .row').count() > 0, 'offline, the app opens and its history is there');
  await ctx.setOffline(false);

  // a bank statement: read here, listed, imported once, and the same file again adds nothing
  const today = new Date().toISOString().slice(0, 10);
  const csv = `Date,Amount,Description\n${today},-12.50,GROCERY STORE 01\n${today},-30.00,POWER UTILITY\n`;
  await gear(page);
  await row(page, 'Import').click();
  await page.setInputFiles('input[type=file]', { name: 'statement.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await page.waitForSelector('text=Import 2');
  ok(await page.locator('.row:has-text("GROCERY STORE 01")').count() >= 1, 'a statement file is read and its lines are listed');
  await page.click('button:has-text("Import 2")');
  await page.waitForSelector('.empty');
  await page.setInputFiles('input[type=file]', { name: 'statement.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await page.waitForSelector('text=Import 0');
  ok(true, 'the same file again has nothing new to import');
  await back(page);
  await row(page, 'Appearance').click();
  await row(page, 'language').click();
  await page.click('.sheet .row:has-text("Deutsch")');
  await page.waitForTimeout(300);
  ok((await page.textContent('main')).includes('Sprache'), 'the language can be changed, and sticks');
  await shot('12-dark-settings-de');
  ok(errors.length === 0, 'no console errors' + (errors.length ? ': ' + errors.join(' | ') : ''));
  await ctx.close();
}

// ── 2b. a period in progress, restored from a backup: the figures add up and say what they are ──
// A salary-started month on its tenth day, four fixed costs (one charged, three to come) and nine everyday expenses.
function periodInProgress() {
  const config = JSON.parse(readFileSync(new URL('./household.config.json', import.meta.url), 'utf8'));
  const day = (d, n) => { const x = new Date(d.getTime() + n * 864e5); return x.toISOString().slice(0, 10); };
  const today = new Date(new Date().toISOString().slice(0, 10) + 'T00:00:00Z');
  const start = new Date(today.getTime() - 9 * 864e5);
  const me = config.me_member_id;
  const cat = Object.fromEntries(config.categories.map((c) => [c.name, c.id]));
  const income = config.categories.find((c) => c.applies_to === 'income').id;
  const account = config.accounts.find((a) => a.type === 'checking').id;
  const uuid5 = (ns, name) => {
    const h = createHash('sha1').update(Buffer.concat([Buffer.from(ns.replace(/-/g, ''), 'hex'), Buffer.from(name)])).digest();
    h[6] = (h[6] & 0x0f) | 0x50; h[8] = (h[8] & 0x3f) | 0x80;
    const x = h.subarray(0, 16).toString('hex');
    return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20, 32)}`;
  };
  let n = 0;
  const id = () => `00000000-0000-4000-9000-${String(++n).padStart(12, '0')}`;
  const stamp = '2030-01-01T00:00:00.000Z';
  const tx = (kind, date, amount, category, note, recurrence = 'variable', extra = {}) => ({
    id: id(), kind, date, amount_minor: amount, category_id: category, account_id: account, to_account_id: null, paid_by_member_id: me, to_member_id: null,
    split: null, recurrence, note, tags: [], extras: {}, status: 'active', recurring_rule_id: null, occurrence_date: null, import_fingerprint: null,
    trip_id: null, created_at: stamp, client_updated_at: '2030-01-02T00:00:00.000Z', server_seq: 0, ...extra,
  });
  const rule = (name, amount, onDay, category, k) => {
    const rid = `00000000-0000-4000-8000-0000000007${String(k).padStart(2, '0')}`;
    config.recurring_rules.push({
      id: rid, name, schedule: { freq: 'monthly', interval: 1, by_weekday: [], by_month_day: new Date(onDay).getUTCDate(), by_month: null },
      template: { kind: 'expense', amount_minor: amount, category_id: category, account_id: account, to_account_id: null, paid_by_member_id: me, to_member_id: null,
        split: null, recurrence: 'fixed', note: name, tags: [], extras: {}, original_currency: null, trip_id: null, created_at: stamp },
      start_date: day(start, -100), end_date: null, auto_create: true, active: true, archived: false,
    });
    return rid;
  };
  const rows = [tx('income', day(start, 0), 271600, income, 'Nómina', 'fixed', { tags: ['fulla:starts-period'] })];
  const rentDay = day(start, 3);
  const rent = rule('Alquiler', 50000, rentDay, cat['Vivienda'], 1);
  rule('Internet', 3322, day(start, 11), cat['Suministros'], 2);
  rule('Préstamo coche', 35000, day(start, 14), cat['Transporte'], 3);
  rule('Seguro', 12000, day(start, 19), cat['Otros gastos'], 4);
  rows.push(tx('expense', rentDay, 50000, cat['Vivienda'], 'Alquiler', 'fixed', { id: uuid5(rent, rentDay), recurring_rule_id: rent, occurrence_date: rentDay }));
  for (const [off, amount, c, note] of [[1, 4520, 'Supermercado', 'Mercadona'], [2, 3810, 'Supermercado', 'Mercadona'], [3, 2990, 'Supermercado', 'Frutería'],
    [4, 1850, 'Comer fuera', 'Bar'], [5, 2240, 'Comer fuera', 'Menú'], [6, 1200, 'Transporte', 'Bus'], [7, 850, 'Transporte', 'Metro'],
    [8, 3557, 'Ocio', 'Cine'], [9, 3500, 'Salud', 'Farmacia']]) rows.push(tx('expense', day(start, off), amount, cat[c], note));
  return JSON.stringify({ format: 'fulla-backup', version: 1, created_at: stamp, config, transactions: rows });
}
{
  const { ctx, page, errors, shot } = await phone({ locale: 'es-ES' });
  await page.goto(BASE);
  await page.click('text=Más opciones');
  await (await page.$('#restore-file')).setInputFiles({ name: 'fulla-casa.json', mimeType: 'application/json', buffer: Buffer.from(periodInProgress()) });
  await page.waitForSelector('.tabbar');
  await skipGuide(page);
  await page.click('.tab:has-text("Resumen")');
  await page.waitForSelector('.figures');
  const figures = (await page.textContent('.figures')).replace(/\s+/g, ' ');
  ok(/2\.716,00/.test(figures) && /745,17/.test(figures) && /Te queda/.test(figures) && /73 %/.test(figures),
    'a month still running says "Te queda", not "Ahorro": ' + figures.trim());
  ok(/Al final del periodo/.test(await page.textContent('main')) && /≈/.test(await page.textContent('main')), 'the overview answers how the month will end, in one line');
  await shot('30-overview-running');
  await page.click('text=Análisis');
  await page.waitForSelector('.insights');
  const text = (await page.textContent('.insights')).replace(/\s+/g, ' ');
  ok(/Al final del periodo/.test(text) && text.indexOf('Al final del periodo') < text.indexOf('El periodo en cifras'), 'the analysis opens with how the period will end, before what already happened');
  ok(text.indexOf('Te quedarían') < text.indexOf('Gastarías'), 'and what would be kept comes before what would be spent');
  ok(/Ingresos\s*2\.716,00/.test(text) && /Gastado hasta hoy\s*−745,17/.test(text) && /Fijos por cobrar[^−]*−503,22/.test(text), 'the sums are in view: income, spent so far, fixed costs still to come');
  ok(/Te quedaría si no gastas nada más[^0-9]*1\.467,61/.test(text), 'and what would be left if nothing more is spent adds up: 2.716,00 − 745,17 − 503,22 = 1.467,61');
  ok(/Media diaria\s*24,51/.test(text) && /Sin los fijos/.test(text), 'the daily average leaves the fixed costs out: 245,17 over 10 days');
  ok(/Te queda\s*73 %/.test(text), 'the share of the income left is not called savings while the period runs');
  ok(!/\b[a-z]+(_[a-z]+)+\b/.test(text), 'no untranslated key shows on the analysis' + (text.match(/\b[a-z]+(_[a-z]+)+\b/) ? ': ' + text.match(/\b[a-z]+(_[a-z]+)+\b/)[0] : ''));
  if (process.env.DEBUG_TEXT) console.log(text);
  await shot('31-insights-running');
  ok(errors.length === 0, 'no console errors on either screen' + (errors.length ? ': ' + errors.join(' | ') : ''));
  await ctx.close();
}

// ── 3. two people, one household, through a Supabase project (the real migrations, in a throwaway PostgreSQL) ──
if (process.env.FULLA_SKIP_DB !== '1') {
  const { createRequire } = await import('node:module');
  const fake = createRequire(import.meta.url)('./fake-supabase.cjs').start();
  const open = async (opts) => {
    const p = await phone(opts);
    await p.ctx.route('https://abcdefghijklmnopqrst.supabase.co/**', (route) => fake.handle(route));
    return p;
  };
  const fakeUrl = 'https://abcdefghijklmnopqrst.supabase.co';
  try {
    const a = await open({ locale: 'en-GB' });
    await a.page.goto(BASE);
    await a.page.click('text=Get started');
    await a.page.fill('input >> nth=0', 'Casa');
    await a.page.fill('input >> nth=1', 'Alice Example');
    await a.page.click('button:has-text("Create")');
    await a.page.waitForSelector('.tabbar');
    await skipGuide(a.page);
    await type(a.page, '12,5'.replace(',', '.'));
    await a.page.click('.tile >> nth=1');
    await a.page.click('.key.save');
    await a.page.waitForSelector('.toast');

    await gear(a.page);
    await row(a.page, 'Sync').click();
    ok(await a.page.locator('.setup-step').count() === 4 && await a.page.locator('input[type=url]').count() === 1, 'without a project of its own, Sync explains the four steps and asks for the address and the key');
    await a.shot('19-setup-guide');
    await a.page.fill('input[type=url]', fakeUrl);
    await a.page.fill('input >> nth=1', 'anon-key-for-tests');
    await a.page.fill('input[type=email]', 'alice@example.com');
    await a.page.fill('input[type=password]', 'correct horse battery');
    await a.page.click('.switch');
    await a.shot('20-share');
    await a.page.click('main .btn.primary');
    await a.page.waitForSelector('.row:has-text("Signed in as")', { timeout: 20000 });
    ok(true, 'Alice shares her household through the project, with an account she creates');

    await back(a.page);
    await row(a.page, 'People').click();
    await a.page.click('button:has-text("Create an invite")');
    // An admin whose household has not chosen how money works is asked first.
    await a.page.waitForSelector('.sheet .row');
    await a.page.click('.sheet .row >> nth=0');
    await a.page.waitForSelector('.code', { timeout: 20000 });
    const code = (await a.page.textContent('.code')).trim();
    ok(code.length >= 6 && await a.page.locator('.qr svg path').count() === 1, 'and invites someone, with a code and a QR: ' + code);

    const b = await open({ locale: 'en-GB' });
    await b.page.goto(BASE);
    await b.page.click('text=I have an invite');
    await b.page.fill('input >> nth=0', code);
    await b.page.fill('input[type=url]', fakeUrl);
    await b.page.fill('input >> nth=2', 'anon-key-for-tests');
    await b.page.fill('input >> nth=3', 'Bob Example');
    await b.page.fill('input[type=email]', 'bob@example.com');
    await b.page.fill('input[type=password]', 'another long phrase');
    await b.page.click('.switch');
    await b.shot('21-join');
    await b.page.click('main .btn.primary');
    await b.page.waitForSelector('.tabbar', { timeout: 30000 });
    await skipGuide(b.page);
    await b.page.click('.tab:has-text("History")');
    await b.page.waitForSelector('.history .row', { timeout: 30000 });
    ok(await b.page.locator('.history .row').count() === 1, 'Bob joins with the invite and finds what Alice wrote down');

    await b.page.click('.tab:has-text("Add")');
    await type(b.page, '30');
    await b.page.click('.tile >> nth=0');
    await b.page.click('.key.save');
    await b.page.waitForSelector('.toast');
    await b.page.waitForTimeout(3500);
    await a.page.click('.header .icon-btn[aria-label="Back"]');
    await a.page.click('.header .icon-btn[aria-label="Back"]');
    await a.page.click('.tab:has-text("Balances")');
    await a.page.click('.tab:has-text("History")');
    await gear(a.page);
    await row(a.page, 'Sync').click();
    await row(a.page, 'Sync now').click();
    await a.page.waitForTimeout(2500);
    await back(a.page);
    await a.page.click('.header .icon-btn[aria-label="Back"]');
    await a.page.click('.tab:has-text("History")');
    await a.page.waitForFunction(() => document.querySelectorAll('.history .row').length === 2, null, { timeout: 15000 });
    ok(true, 'what Bob writes reaches Alice');
    await a.shot('22-alice-history');
    ok(a.errors.length === 0 && b.errors.length === 0, 'no console errors on either phone' + [...a.errors, ...b.errors].join(' | '));
    await a.ctx.close(); await b.ctx.close();
  } catch (e) {
    ok(false, 'two people through the project: ' + e.message.split('\n').slice(0, 4).join(' / '));
  } finally {
    fake.stop();
  }
}

await browser.close();
server.close();
console.log(failures === 0 ? '\nweb e2e: all passed' : `\nweb e2e: ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
