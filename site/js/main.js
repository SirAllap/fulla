import { Spring, SNAPPY, SETTLE, TRAIL, clamp, lerp, smooth, easeInOut, money } from './spring.js';
import { drawVessel, palette, fitCanvas } from './vessel.js';

const html = document.documentElement;
const MOVES = html.classList.contains('moves');
const $ = (id) => document.getElementById(id);
let pal = palette();

/* ------------------------------------------------------------------ */
/* An example month (synthetic, the same demo household as the app)   */
/* ------------------------------------------------------------------ */
const IN = 6000;
const SPENT = [
  { day: 3, name: 'Rent', amount: 1500.0 },
  { day: 5, name: 'Groceries', amount: 61.4 },
  { day: 7, name: 'Electricity and water', amount: 119.96 },
  { day: 12, name: 'Groceries', amount: 84.2 },
  { day: 15, name: 'Eating out', amount: 56.14 },
  { day: 18, name: 'Streaming', amount: 12.99 },
  { day: 21, name: 'Groceries', amount: 108.25 },
  { day: 24, name: 'Bus pass top-up', amount: 11.27 },
];
const spentBy = (d) => SPENT.reduce((s, e) => (e.day <= d ? s + e.amount : s), 0);

/* ------------------------------------------------------------------ */
/* The band                                                            */
/* ------------------------------------------------------------------ */
const stage = $('stage');
const hero = $('hero');
const heroText = hero.querySelector('.hero-text');
const band = $('band');
const slot = $('bandSlot');
const bar = $('bar');
let rest = { L: 0, T: 0, S: 300 };
let fluid = null;

function placeBand() {
  const s = slot.getBoundingClientRect();
  const g = stage.getBoundingClientRect();
  rest = { L: s.left - g.left, T: s.top - g.top, S: s.width };
  Object.assign(band.style, { left: '0px', top: '0px', width: `${rest.S}px`, height: `${rest.S}px`, transform: `translate(${rest.L}px, ${rest.T}px)` });
}

async function startBand() {
  placeBand();
  if (!MOVES) return;
  requestAnimationFrame(() => band.classList.add('drawn'));
  // once drawn, the ring is closed for good: no seam where the stroke began
  setTimeout(() => band.classList.add('closed'), 700);
  let ok = false;
  try {
    const { createBandFluid } = await import('./fluid.js');
    fluid = createBandFluid($('bandFluid'));
    ok = !!fluid;
  } catch (e) { console.warn(e); }
  if (ok) {
    fluid.resize();
    fluid.rest();
    band.classList.add('live');
    setTimeout(() => fluid.intro(), 160);
    setupTilt();
    const io = new IntersectionObserver(([en]) => fluid.setVisible(en.isIntersecting && !document.hidden));
    io.observe(band);
    document.addEventListener('visibilitychange', () => fluid.setVisible(!document.hidden));
  }
  setTimeout(() => hero.classList.add('on'), ok ? 520 : 360);
}

function setupTilt() {
  if (!('DeviceOrientationEvent' in window) || !matchMedia('(pointer: coarse)').matches) return;
  const listen = () => window.addEventListener('deviceorientation', (e) => {
    if (e.gamma == null) return;
    const o = (screen.orientation && screen.orientation.angle) || 0;
    const deg = o === 90 ? e.beta : o === 270 || o === -90 ? -e.beta : e.gamma;
    fluid.tilt((deg * Math.PI) / 180);
  }, { passive: true });
  const D = window.DeviceOrientationEvent;
  if (typeof D.requestPermission === 'function') {
    const b = $('tiltBtn');
    b.hidden = false;
    b.addEventListener('click', async () => {
      try { if ((await D.requestPermission()) === 'granted') listen(); } catch (_) { /* declined */ }
      b.hidden = true;
    }, { once: true });
  } else listen();
}

/* ------------------------------------------------------------------ */
/* Falling into your month                                             */
/* ------------------------------------------------------------------ */
const intro = document.querySelector('.intro');
const monthLayer = $('monthLayer');
const monthCanvas = $('monthCanvas');
const copy = $('monthCopy');
const els = { day: $('monthDay'), line: $('monthLine'), stay: $('monthStay'), row: $('monthRow'), inL: $('monthIn'), outL: $('monthOut'), inF: $('monthInFig'), outF: $('monthOutFig') };
const scrollCue = $('scrollCue');
const ringCanvas = document.createElement('canvas');
ringCanvas.className = 'ring-canvas';
ringCanvas.setAttribute('aria-hidden', 'true');
if (MOVES) stage.insertBefore(ringCanvas, band.nextSibling);

const scene = {
  p: -1, top: 0, len: 1, vw: 1, vh: 1, W: 1, H: 1, ctx: null, rctx: null, copyH: 200,
  inS: new Spring(0, SETTLE), outS: new Spring(0, SETTLE), t: 0, last: 0, raf: 0, line: '', dirty: true,
};

function measureScene() {
  scene.vw = window.innerWidth; scene.vh = stage.clientHeight || window.innerHeight;
  scene.top = intro.getBoundingClientRect().top + window.scrollY;
  scene.len = Math.max(1, intro.offsetHeight - scene.vh);
  const m = fitCanvas(monthCanvas); scene.ctx = m.ctx; scene.W = m.W; scene.H = m.H;
  const r = fitCanvas(ringCanvas); scene.rctx = r.ctx;
  scene.copyH = copy.offsetHeight;
  scene.dirty = true;
}

function setLine(text) {
  if (scene.line === text) return;
  scene.line = text;
  els.line.classList.add('swap');
  setTimeout(() => { els.line.textContent = text; els.line.classList.remove('swap'); }, 150);
}

function updateScene(ts) {
  scene.raf = 0;
  const dt = scene.last ? Math.min((ts - scene.last) / 1000, 1 / 30) : 1 / 60;
  scene.last = ts;
  const p = clamp((window.scrollY - scene.top) / scene.len, 0, 1);
  const moved = p !== scene.p;
  scene.p = p;
  const { vw, vh } = scene;

  // 1. the band grows until you are inside it
  const e = easeInOut(clamp(p / 0.18, 0, 1));
  const { L, T, S } = rest;
  const inner = 0.41 * S;
  const endScale = (Math.hypot(vw, vh) / 2 * 1.04) / inner;
  const scale = Math.pow(endScale, e);
  const cx = lerp(L + S / 2, vw / 2, e), cy = lerp(T + S / 2, vh / 2, e);
  const falling = p > 0.0005;

  if (moved || scene.dirty) {
    band.style.transform = falling
      ? `translate(${cx - (S / 2) * scale}px, ${cy - (S / 2) * scale}px) scale(${scale})`
      : `translate(${L}px, ${T}px)`;
    band.style.opacity = falling ? String(1 - smooth(0.02, 0.16, e)) : '';
    band.classList.toggle('falling', falling);
    if (fluid) fluid.setEnabled(e < 0.2);

    const fade = smooth(0, 0.35, e);
    heroText.style.opacity = String(1 - fade);
    heroText.style.filter = fade > 0.001 ? `blur(${(fade * 8).toFixed(2)}px)` : '';
    heroText.style.transform = `translateY(${(-24 * e).toFixed(1)}px)`;
    hero.style.pointerEvents = e > 0.4 ? 'none' : '';

    monthLayer.style.visibility = falling ? 'visible' : 'hidden';
    monthLayer.style.clipPath = e >= 0.999 ? 'none' : `circle(${(inner * scale).toFixed(1)}px at ${cx.toFixed(1)}px ${cy.toFixed(1)}px)`;

    // the ring itself, drawn crisp at any size
    const rc = scene.rctx;
    rc.clearRect(0, 0, vw, vh);
    ringCanvas.style.visibility = falling && e < 0.999 ? 'visible' : 'hidden';
    if (falling && e < 0.999) {
      rc.beginPath();
      rc.arc(cx, cy, (27 / 60) * S * scale, 0, Math.PI * 2);
      rc.lineWidth = (5 / 60) * S * scale;
      rc.strokeStyle = pal.ring;
      rc.stroke();
    }

    const dock = p >= 1 ? 1 : smooth(0.1, 0.2, p);
    bar.style.setProperty('--dock', dock.toFixed(3));
    bar.style.setProperty('--dock-pe', dock > 0.5 ? 'auto' : 'none');
    bar.classList.toggle('dock-on', dock > 0.02);
    bar.classList.toggle('solid', p >= 1);
    if (!falling) bar.classList.remove('over-gold');

    // the scroll cue: gone as soon as the pinned scene starts moving
    if (scrollCue) {
      const cueGone = smooth(0.01, 0.09, p);
      scrollCue.style.opacity = String(1 - cueGone);
      scrollCue.style.visibility = cueGone >= 1 ? 'hidden' : 'visible';
    }
  }

  // 2. the month, scrubbed by the scroll
  const dayF = clamp((p - 0.24) / 0.7, 0, 1) * 30;
  const pour = clamp((dayF - 0.15) / 0.85, 0, 1);
  scene.inS.set(0.5 * pour);
  scene.outS.set(spentBy(dayF) / (2 * IN));
  scene.inS.step(dt); scene.outS.step(dt);
  const motion = Math.abs(scene.inS.v) + Math.abs(scene.outS.v);
  scene.t += dt * Math.min(1, motion * 10);

  if (falling) {
    const H = scene.H, W = scene.W;
    const inY = scene.inS.x * H;
    const outY = H - scene.outS.x * H;
    const inShown = (scene.inS.x / 0.5) * IN;
    const outShown = scene.outS.x * 2 * IN;
    const stay = Math.max(0, inShown - outShown);
    drawVessel(scene.ctx, W, H, {
      inY, outY,
      inSlosh: clamp(scene.inS.v * H * 0.05, -H * 0.03, H * 0.03),
      outSlosh: clamp(scene.outS.v * H * 0.08, -H * 0.03, H * 0.03),
      t: scene.t,
      glow: smooth(0.3, 1, pour) * (0.55 + 0.45 * stay / IN),
      base: Math.min(H * 0.008, 7),
    }, pal);

    const gapC = (inY + outY) / 2;
    const y = clamp(gapC - scene.copyH / 2, 72, Math.max(72, outY - scene.copyH - 8));
    copy.style.transform = `translateY(${y.toFixed(1)}px)`;
    copy.style.opacity = String(smooth(0.35, 0.8, e));
    $('monthStayWrap').style.opacity = String(smooth(0.05, 0.4, pour));
    els.day.textContent = dayF < 0.15 ? 'September' : `September, day ${clamp(Math.ceil(dayF), 1, 30)}`;
    setLine(dayF < 0.15 ? 'Your month is a vessel.' : dayF < 2.6 ? 'What comes in fills it.' : dayF < 25.5 ? 'What goes out settles at the bottom.' : 'The space between is what you keep.');
    els.stay.textContent = money(stay);
    let row = ' ';
    const lastE = [...SPENT].reverse().find((x) => x.day <= dayF);
    if (lastE) row = `${lastE.name}, day ${lastE.day}: −${money(lastE.amount)}`;
    else if (pour > 0.4) row = `Salary, day 1: +${money(IN)}`;
    els.row.textContent = row;
    els.inF.textContent = `+${money(inShown)}`;
    els.outF.textContent = money(outShown);
    els.inL.style.transform = `translateY(${(inY - 40).toFixed(1)}px)`;
    els.inL.style.opacity = String(smooth(56, 96, inY));
    els.outL.style.transform = `translateY(${(outY + 14).toFixed(1)}px)`;
    els.outL.style.opacity = String(smooth(44, 84, H - outY));
    bar.classList.toggle('over-gold', p < 1 && inY > 64);
  }

  scene.dirty = false;
  const settling = !scene.inS.resting(1e-4) || !scene.outS.resting(1e-4);
  if (settling) scene.raf = requestAnimationFrame(updateScene);
  else {
    scene.last = 0;
    // at rest the figures are exact, never a spring's approximation
    if (scene.inS.x !== scene.inS.target || scene.outS.x !== scene.outS.target) {
      scene.inS.snap(scene.inS.target); scene.outS.snap(scene.outS.target); scene.dirty = true; kickScene();
    }
  }
}

function kickScene() { if (!scene.raf) scene.raf = requestAnimationFrame(updateScene); }

/* ------------------------------------------------------------------ */
/* The still month, for reduced motion                                 */
/* ------------------------------------------------------------------ */
function drawStill() {
  if (MOVES) return;
  const c = $('monthStillCanvas');
  const { ctx, W, H } = fitCanvas(c);
  const out = spentBy(30);
  drawVessel(ctx, W, H, { inY: H * 0.5, outY: H - (out / (2 * IN)) * H, inSlosh: 0, outSlosh: 0, t: 0, glow: 1, base: H * 0.008 }, pal);
  ctx.fillStyle = pal.ink;
  ctx.textAlign = 'center';
  ctx.font = `500 ${Math.round(Math.min(W * 0.1, 64))}px Archivo, system-ui, sans-serif`;
  const gapC = (H * 0.5 + H - (out / (2 * IN)) * H) / 2;
  ctx.fillText(money(IN - out), W / 2, gapC + 10);
  ctx.font = `500 15px Archivo, system-ui, sans-serif`;
  ctx.fillStyle = pal.dark ? '#A8A2B5' : '#625C6E';
  ctx.fillText('What stays', W / 2, gapC - Math.min(W * 0.1, 64) * 0.75);
  ctx.textAlign = 'left';
  ctx.fillStyle = '#1B1830';
  ctx.fillText(`In +${money(IN)}`, 20, H * 0.5 - 24);
  ctx.fillStyle = '#F6F1E7';
  ctx.fillText(`Out ${money(out)}`, 20, H - 22);
}

/* ------------------------------------------------------------------ */
/* The drop: the app's liquid tab bar                                  */
/* ------------------------------------------------------------------ */
function setupTabs() {
  const list = $('tabs');
  const tabs = [...list.querySelectorAll('[role="tab"]')];
  const drop = $('drop');
  const edge = { l: new Spring(0, SNAPPY), r: new Spring(0, SNAPPY), t: new Spring(0, SNAPPY) };
  let boxes = [], cur = 0, raf = 0, last = 0, h = 52, W = 0, Hh = 0;

  const measure = () => {
    const lr = list.getBoundingClientRect();
    W = lr.width; Hh = lr.height;
    boxes = tabs.map((t) => { const r = t.getBoundingClientRect(); return { l: r.left - lr.left, r: r.right - lr.left, t: r.top - lr.top, h: r.height }; });
    h = boxes[cur].h;
    Object.assign(drop.style, { width: `${W}px`, height: `${Hh}px`, borderRadius: '0' });
  };
  const paint = () => {
    const l = edge.l.x, r = Math.max(edge.r.x, l + 8), t = edge.t.x;
    drop.style.clipPath = `inset(${t.toFixed(1)}px ${(W - r).toFixed(1)}px ${(Hh - t - h).toFixed(1)}px ${l.toFixed(1)}px round 16px)`;
  };
  const tick = (ts) => {
    raf = 0;
    const dt = last ? Math.min((ts - last) / 1000, 1 / 30) : 1 / 60; last = ts;
    edge.l.step(dt); edge.r.step(dt); edge.t.step(dt);
    paint();
    if (!edge.l.resting(0.05) || !edge.r.resting(0.05) || !edge.t.resting(0.05)) raf = requestAnimationFrame(tick);
    else last = 0;
  };
  const go = (i, focus) => {
    const prev = cur; cur = i;
    tabs.forEach((t, k) => {
      const on = k === i;
      t.setAttribute('aria-selected', String(on));
      t.tabIndex = on ? 0 : -1;
      const panel = $(t.getAttribute('aria-controls'));
      panel.hidden = !on;
      const art = document.querySelector(`.art[data-art="${k}"]`);
      if (art) art.classList.toggle('on', on);
      if (on && prev !== i) { panel.classList.remove('enter'); void panel.offsetWidth; panel.classList.add('enter'); }
    });
    if (focus) tabs[i].focus();
    const b = boxes[i];
    const right = i > prev || (b.t === boxes[prev].t && b.l > boxes[prev].l);
    // the leading edge is quick, the trailing edge lazy: the drop stretches, then gathers
    Object.assign(edge.l, right ? { k: TRAIL.stiffness, c: 2 * TRAIL.damping * Math.sqrt(TRAIL.stiffness) } : { k: SNAPPY.stiffness, c: 2 * SNAPPY.damping * Math.sqrt(SNAPPY.stiffness) });
    Object.assign(edge.r, right ? { k: SNAPPY.stiffness, c: 2 * SNAPPY.damping * Math.sqrt(SNAPPY.stiffness) } : { k: TRAIL.stiffness, c: 2 * TRAIL.damping * Math.sqrt(TRAIL.stiffness) });
    edge.l.set(b.l); edge.r.set(b.r); edge.t.set(b.t);
    if (!MOVES) { edge.l.snap(b.l); edge.r.snap(b.r); edge.t.snap(b.t); paint(); return; }
    if (!raf) raf = requestAnimationFrame(tick);
  };
  tabs.forEach((t, i) => t.addEventListener('click', () => go(i, false)));
  list.addEventListener('keydown', (e) => {
    const n = tabs.length;
    const k = { ArrowRight: (cur + 1) % n, ArrowLeft: (cur - 1 + n) % n, ArrowDown: (cur + 1) % n, ArrowUp: (cur - 1 + n) % n, Home: 0, End: n - 1 }[e.key];
    if (k === undefined) return;
    e.preventDefault(); go(k, true);
  });
  const reset = () => { measure(); const b = boxes[cur]; edge.l.snap(b.l); edge.r.snap(b.r); edge.t.snap(b.t); paint(); };
  reset();
  return reset;
}

/* ------------------------------------------------------------------ */
/* The rail of screens                                                 */
/* ------------------------------------------------------------------ */
function setupRail() {
  const rail = $('rail');
  const phones = [...rail.querySelectorAll('.phone')];
  let centres = [], drag = null, anim = null, suppress = false, raf = 0;

  const measure = () => { centres = phones.map((p) => p.offsetLeft + p.offsetWidth / 2); lift(); };
  const lift = () => {
    if (!MOVES) return;
    const mid = rail.scrollLeft + rail.clientWidth / 2;
    const span = phones[0] ? phones[0].offsetWidth * 1.2 : 300;
    phones.forEach((p, i) => { const d = Math.abs(centres[i] - mid) / span; p.style.transform = `translateY(${(-12 * (1 - Math.min(1, d))).toFixed(1)}px)`; });
  };
  rail.addEventListener('scroll', () => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; lift(); }); }, { passive: true });

  // Mouse: drag 1:1, then throw with Apple's momentum projection and snap on a spring.
  const project = (v, d = 0.998) => ((v / 1000) * d) / (1 - d);
  rail.addEventListener('pointerdown', (e) => {
    if (e.pointerType !== 'mouse' || e.button !== 0) return;
    anim && cancelAnimationFrame(anim.raf); anim = null;
    drag = { x: e.clientX, s: rail.scrollLeft, hist: [{ x: e.clientX, t: e.timeStamp }], moved: false, id: e.pointerId };
  });
  rail.addEventListener('pointermove', (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    const dx = e.clientX - drag.x;
    if (!drag.moved && Math.abs(dx) > 6) { drag.moved = true; rail.setPointerCapture(e.pointerId); rail.classList.add('dragging'); }
    if (!drag.moved) return;
    rail.scrollLeft = drag.s - dx;
    drag.hist.push({ x: e.clientX, t: e.timeStamp });
    if (drag.hist.length > 6) drag.hist.shift();
  });
  const end = (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    const d = drag; drag = null;
    if (!d.moved) return;
    suppress = true; setTimeout(() => { suppress = false; }, 0);
    const a = d.hist[0], b = d.hist[d.hist.length - 1];
    const v = b.t > a.t ? (-(b.x - a.x) / (b.t - a.t)) * 1000 : 0;   // px/s of scroll
    const aim = rail.scrollLeft + project(v);
    let best = 0, bd = Infinity;
    const pad = parseFloat(getComputedStyle(rail).scrollPaddingInlineStart) || 0;
    phones.forEach((p) => { const t = p.offsetLeft - pad; const dd = Math.abs(t - aim); if (dd < bd) { bd = dd; best = t; } });
    best = clamp(best, 0, rail.scrollWidth - rail.clientWidth);
    const s = new Spring(rail.scrollLeft, { damping: 1, stiffness: 240 });
    s.v = v; s.set(best);
    let last = 0;
    const run = (ts) => {
      const dt = last ? Math.min((ts - last) / 1000, 1 / 30) : 1 / 60; last = ts;
      s.step(dt); rail.scrollLeft = s.x;
      if (!s.resting(0.5)) anim.raf = requestAnimationFrame(run);
      else { rail.scrollLeft = best; rail.classList.remove('dragging'); anim = null; }
    };
    if (!MOVES) { rail.scrollLeft = best; rail.classList.remove('dragging'); return; }
    anim = { raf: requestAnimationFrame(run) };
  };
  rail.addEventListener('pointerup', end);
  rail.addEventListener('pointercancel', end);
  rail.addEventListener('click', (e) => { if (suppress) { e.preventDefault(); e.stopPropagation(); } }, true);
  measure();
  return measure;
}

/* ------------------------------------------------------------------ */
/* Steps that gather into a check                                      */
/* ------------------------------------------------------------------ */
// Why Fulla: the band's liquid settles once, the first time it is seen
function setupName() {
  const el = document.getElementById('name');
  if (!el || !MOVES) return;
  // fires as soon as the section is ~15% into the viewport, not once it is well inside it
  const io = new IntersectionObserver(([en]) => { if (en.isIntersecting) { el.classList.add('seen'); io.disconnect(); } }, { rootMargin: '0px 0px -15% 0px', threshold: 0 });
  io.observe(el);
}
setupName();

function setupSteps() {
  const steps = [...document.querySelectorAll('.step')];
  let tops = [];
  // a step is "done" once its top has crossed ~15% into the viewport, not once
  // it has travelled well past the middle of the screen
  const measure = () => { tops = steps.map((s) => s.getBoundingClientRect().top + window.scrollY); check(); };
  const check = () => {
    const line = window.scrollY + window.innerHeight * 0.85;
    steps.forEach((s, i) => s.classList.toggle('done', !MOVES || tops[i] < line));
  };
  return { measure, check };
}

/* ------------------------------------------------------------------ */
/* The app, alive: the Overview hero, live                            */
/* ------------------------------------------------------------------ */
const ICONS = {
  Housing: '<path d="M4 11.5 12 5l8 6.5M6.5 10v9h11v-9M10 19v-5h4v5"/>',
  Groceries: '<path d="M3.5 5h2.2l2 10h10l1.8-7H7"/><circle cx="9.5" cy="19" r="1.3"/><circle cx="16.5" cy="19" r="1.3"/>',
  Utilities: '<path d="M13 3 6 13.5h5L10 21l7-10.5h-5z"/>',
  'Eating out': '<path d="M7 3v8m-2.5-8v4.5a2.5 2.5 0 0 0 5 0V3M7 11v10M16.5 3c-2 1.5-2.5 4-2.5 7h3v11"/>',
  Subscriptions: '<rect x="4" y="8" width="16" height="12" rx="2"/><path d="M7 5h10M10.5 11.5v5l4-2.5z"/>',
  Transport: '<rect x="5" y="4" width="14" height="13" rx="3"/><path d="M5 11h14M8.5 20l-1-3m8 3 1-3"/><circle cx="8.5" cy="14" r=".8"/><circle cx="15.5" cy="14" r=".8"/>',
};
const USUAL = { Housing: 1500, Groceries: 464.14, Utilities: 130.53, 'Eating out': 181.66, Subscriptions: 12.99, Transport: 111.13 };
const MONTHS = [
  { name: 'July', rows: { Housing: 1500, Groceries: 512.4, Utilities: 140.22, 'Eating out': 301.18, Subscriptions: 12.99, Transport: 243.56 } },
  { name: 'August', rows: { Housing: 1500, Groceries: 402.18, Utilities: 131.4, 'Eating out': 187.66, Subscriptions: 12.99, Transport: 78.25 } },
  { name: 'September', rows: { Housing: 1500, Groceries: 253.85, Utilities: 119.96, 'Eating out': 56.14, Subscriptions: 12.99, Transport: 11.27 } },
];

function setupApp() {
  const canvas = $('appJarCanvas');
  const jar = $('appJar');
  const fig = $('appSaved');
  const rowsEl = $('appRows');
  const inS = new Spring(0, SETTLE), outS = new Spring(0, SETTLE);
  let idx = 2, shown = 0, from = 0, to = 0, countT = 1, raf = 0, last = 0, t = 0, seen = false, ctx = null, W = 1, H = 1;
  const cats = Object.keys(ICONS);

  const measure = () => { const f = fitCanvas(canvas); ctx = f.ctx; W = f.W; H = f.H; draw(); };
  const totals = (m) => Object.values(m.rows).reduce((a, b) => a + b, 0);

  function render() {
    const m = MONTHS[idx];
    const out = totals(m);
    $('appMonth').textContent = m.name;
    $('prevMonth').disabled = idx === 0;
    $('nextMonth').disabled = idx === MONTHS.length - 1;
    $('appIn').textContent = `+${money(IN)}`;
    $('appOut').textContent = money(out);
    $('appPct').textContent = `${Math.round(((IN - out) / IN) * 100)} %`;
    rowsEl.innerHTML = cats.map((c, i) => `<li><svg viewBox="0 0 24 24" aria-hidden="true" style="stroke:var(--cat-${i % 5})">${ICONS[c]}</svg><span class="n">${c}<span class="u">Usually ${money(USUAL[c])}</span></span><span class="a">${money(m.rows[c])}</span></li>`).join('');
    inS.set(0.5);
    outS.set(out / (2 * IN));
    from = shown; to = IN - out; countT = 0;
    kick();
  }

  function draw() {
    if (!ctx) return;
    const inY = inS.x * H, outY = H - outS.x * H;
    drawVessel(ctx, W, H, {
      inY, outY,
      inSlosh: clamp(inS.v * H * 0.05, -H * 0.04, H * 0.04),
      outSlosh: clamp(outS.v * H * 0.07, -H * 0.04, H * 0.04),
      t, glow: 0, base: H * 0.012,
    }, pal);
    const fh = fig.offsetHeight || 40;
    fig.style.transform = `translateY(${((inY + outY) / 2 - fh / 2).toFixed(1)}px)`;
    fig.textContent = money(shown);
  }

  function tick(ts) {
    raf = 0;
    const dt = last ? Math.min((ts - last) / 1000, 1 / 30) : 1 / 60; last = ts;
    inS.step(dt); outS.step(dt);
    t += dt * Math.min(1, (Math.abs(inS.v) + Math.abs(outS.v)) * 10);
    // the figure counts over 300 ms, as in the app
    if (countT < 1) { countT = Math.min(1, countT + dt / 0.3); const k = 1 - Math.pow(1 - countT, 3); shown = lerp(from, to, k); }
    draw();
    if (!inS.resting() || !outS.resting() || countT < 1) raf = requestAnimationFrame(tick);
    else { last = 0; inS.snap(inS.target); outS.snap(outS.target); shown = to; draw(); }
  }
  const kick = () => {
    if (!MOVES) { inS.snap(inS.target); outS.snap(outS.target); shown = to; countT = 1; draw(); return; }
    if (!raf) raf = requestAnimationFrame(tick);
  };

  $('prevMonth').addEventListener('click', () => { if (idx > 0) { idx--; render(); } });
  $('nextMonth').addEventListener('click', () => { if (idx < MONTHS.length - 1) { idx++; render(); } });
  jar.addEventListener('pointerdown', () => { inS.v -= 0.5; outS.v += 0.35; kick(); });

  measure();
  if (!MOVES) { render(); return measure; }
  // the one orchestrated moment: the first time it is seen, the liquids settle and the figure counts
  const io = new IntersectionObserver(([en]) => {
    if (en.isIntersecting && !seen) { seen = true; render(); io.disconnect(); }
  }, { threshold: 0.45 });
  io.observe(jar);
  // before it is seen, show the month at rest without animating
  const m = MONTHS[idx];
  $('appMonth').textContent = m.name;
  rowsEl.innerHTML = cats.map((c, i) => `<li><svg viewBox="0 0 24 24" aria-hidden="true" style="stroke:var(--cat-${i % 5})">${ICONS[c]}</svg><span class="n">${c}<span class="u">Usually ${money(USUAL[c])}</span></span><span class="a">${money(m.rows[c])}</span></li>`).join('');
  $('nextMonth').disabled = true;
  draw();
  return measure;
}

/* ------------------------------------------------------------------ */
/* Wiring                                                              */
/* ------------------------------------------------------------------ */
const resetTabs = setupTabs();
const measureRail = setupRail();
const steps = setupSteps();
const measureApp = setupApp();

function measureAll() {
  placeBand();
  if (MOVES) { measureScene(); scene.p = -1; kickScene(); }
  if (fluid) fluid.resize();
  resetTabs(); measureRail(); steps.measure(); measureApp(); drawStill();
}

let rz = 0;
window.addEventListener('resize', () => { cancelAnimationFrame(rz); rz = requestAnimationFrame(measureAll); });
window.addEventListener('scroll', () => { if (MOVES) kickScene(); steps.check(); }, { passive: true });
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
  pal = palette(); if (fluid) fluid.theme(); measureAll();
});

document.fonts.ready.then(() => { measureAll(); });
if (MOVES) measureScene();
drawStill();
startBand();
if (MOVES) kickScene();


/* Rail arrows, progress bar and edge fades */
(() => {
  const rail = document.getElementById('rail');
  if (!rail) return;
  const prev = document.getElementById('railPrev'), next = document.getElementById('railNext'), bar = document.getElementById('railBar');
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const step = () => { const p = rail.querySelector('.phone'); return p ? p.getBoundingClientRect().width + 28 : 300; };
  prev.addEventListener('click', () => rail.scrollBy({ left: -step(), behavior: reduce ? 'auto' : 'smooth' }));
  next.addEventListener('click', () => rail.scrollBy({ left: step(), behavior: reduce ? 'auto' : 'smooth' }));
  const update = () => {
    const max = rail.scrollWidth - rail.clientWidth;
    const f = max > 0 ? rail.scrollLeft / max : 0;
    const shown = rail.scrollWidth > 0 ? rail.clientWidth / rail.scrollWidth : 1;
    bar.style.width = (Math.max(shown, 0.12) * 100) + '%';
    bar.style.transform = 'translateX(' + (f * (1 / Math.max(shown, 0.12) - 1) * 100) + '%)';
    prev.disabled = rail.scrollLeft <= 2;
    next.disabled = rail.scrollLeft >= max - 2;
    rail.style.setProperty('--fade-l', rail.scrollLeft > 2 ? '96px' : '0px');
    rail.style.setProperty('--fade-r', rail.scrollLeft < max - 2 ? '96px' : '0px');
  };
  let raf = 0;
  rail.addEventListener('scroll', () => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; update(); }); }, { passive: true });
  addEventListener('resize', update);
  update();
})();
