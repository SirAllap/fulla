// The app's hero, drawn on a 2D canvas: income as a light liquid floating at the top,
// spending as a heavy one settled at the bottom, and the gap between them is what stays.
// As in core/design/Liquid.kt, every surface has a mean of exactly zero across the
// samples drawn, so a wave never quietly draws a different amount.

const N = 56;
const ys = new Float32Array(N);

export function palette() {
  const cs = getComputedStyle(document.documentElement);
  const v = (n) => cs.getPropertyValue(n).trim();
  return {
    inSurface: v('--in-surface'), inBody: v('--in-body'),
    outSurface: v('--out-surface'), outBody: v('--out-body'),
    gap: v('--gap'), gold: v('--gold'), paper: v('--paper'),
    liquid: v('--liquid'), ink: v('--ink'), ring: v('--ring'),
    dark: matchMedia('(prefers-color-scheme: dark)').matches,
  };
}

function surface(W, level, base, phase0, slosh, t, mirror) {
  let mean = 0;
  for (let i = 0; i < N; i++) {
    const x = i / (N - 1);
    const y = base * Math.sin(Math.PI * 2 * (x + phase0)) +
      slosh * Math.sin(Math.PI * 2 * (1.5 * x) * (mirror ? -1 : 1) + t * 5.2);
    ys[i] = y;
  }
  // trapezoid mean, as Liquid.kt
  mean = 0;
  for (let i = 0; i < N - 1; i++) mean += (ys[i] + ys[i + 1]) / 2;
  mean /= (N - 1);
  for (let i = 0; i < N; i++) ys[i] = level + ys[i] - mean;
  return ys;
}

function trace(ctx, W, pts, reverse) {
  if (!reverse) for (let i = 0; i < N; i++) ctx.lineTo((i / (N - 1)) * W, pts[i]);
  else for (let i = N - 1; i >= 0; i--) ctx.lineTo((i / (N - 1)) * W, pts[i]);
}

/**
 * st: { inY, outY, inSlosh, outSlosh, t, glow, base }
 * All lengths in CSS pixels; the context is already scaled for the device.
 */
export function drawVessel(ctx, W, H, st, pal) {
  ctx.clearRect(0, 0, W, H);
  ctx.fillStyle = pal.gap;
  ctx.fillRect(0, 0, W, H);
  const base = st.base ?? H * 0.012;

  // what stays: a soft glow in the gap, stronger the more there is
  if (st.glow > 0.001) {
    const gy = (st.inY + st.outY) / 2;
    const gh = Math.max(1, st.outY - st.inY);
    const r = Math.max(W * 0.55, gh * 0.9);
    const g = ctx.createRadialGradient(W / 2, gy, 0, W / 2, gy, r);
    const a = pal.dark ? 0.34 : 0.42;
    g.addColorStop(0, rgba(pal.gold, Math.min(0.6, a * 1.35) * st.glow));
    g.addColorStop(0.22, rgba(pal.gold, a * st.glow));
    g.addColorStop(0.5, rgba(pal.gold, a * 0.45 * st.glow));
    g.addColorStop(1, rgba(pal.gold, 0));
    ctx.fillStyle = g;
    ctx.fillRect(0, Math.max(0, st.inY - 40), W, gh + 80);
  }

  // income: the light liquid, floating at the top
  if (st.inY > 0.5) {
    const pts = surface(W, st.inY, base, 0.08, st.inSlosh, st.t, false);
    ctx.beginPath();
    ctx.moveTo(0, 0); ctx.lineTo(W, 0);
    trace(ctx, W, pts, true);
    ctx.closePath();
    const g = ctx.createLinearGradient(0, 0, 0, Math.max(st.inY, 1));
    g.addColorStop(0, pal.inBody);
    g.addColorStop(1, pal.inSurface);
    ctx.fillStyle = g;
    ctx.fill();
    ctx.beginPath();
    trace(ctx, W, pts, false);
    ctx.strokeStyle = 'rgba(255,255,255,0.38)';
    ctx.lineWidth = 1.5;
    ctx.stroke();
  }

  // spending: the heavy liquid, settled at the bottom
  if (st.outY < H - 0.5) {
    const pts = surface(W, st.outY, base * 0.9, 0.58, st.outSlosh, st.t + 1.3, true);
    ctx.beginPath();
    ctx.moveTo(0, H);
    trace(ctx, W, pts, false);
    ctx.lineTo(W, H);
    ctx.closePath();
    const g = ctx.createLinearGradient(0, Math.min(st.outY, H - 1), 0, H);
    g.addColorStop(0, pal.outSurface);
    g.addColorStop(pal.dark ? 1 : 0.85, pal.outBody);
    ctx.fillStyle = g;
    ctx.fill();
    ctx.beginPath();
    trace(ctx, W, pts, false);
    ctx.strokeStyle = pal.dark ? 'rgba(255,255,255,0.35)' : 'rgba(255,255,255,0.45)';
    ctx.lineWidth = 1.25;
    ctx.stroke();
  }
}

export function rgba(hex, a) {
  const h = hex.replace('#', '');
  const n = parseInt(h.length === 3 ? h.split('').map((c) => c + c).join('') : h, 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

export function fitCanvas(canvas, maxDpr = 2) {
  const r = canvas.getBoundingClientRect();
  const dpr = Math.min(maxDpr, window.devicePixelRatio || 1);
  const w = Math.max(1, Math.round(r.width * dpr));
  const h = Math.max(1, Math.round(r.height * dpr));
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  return { ctx, W: r.width, H: r.height };
}
