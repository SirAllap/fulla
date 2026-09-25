// Springs with the app's parameters (app/.../ui/theme/Motion.kt), mass 1.
// Compose stiffness: VeryLow 50, Low 200, MediumLow 400.
export const SNAPPY = { damping: 0.9, stiffness: 400 };
export const SETTLE = { damping: 0.72, stiffness: 200 };
export const TRAIL = { damping: 0.85, stiffness: 110 };

export class Spring {
  constructor(value = 0, { damping, stiffness } = SNAPPY) {
    this.x = value;
    this.target = value;
    this.v = 0;
    this.k = stiffness;
    this.c = 2 * damping * Math.sqrt(stiffness);
  }
  set(target) { this.target = target; }
  snap(value) { this.x = this.target = value; this.v = 0; }
  // Semi-implicit Euler in fixed substeps: stable for any frame time, and it keeps
  // velocity across a change of target, so every animation is interruptible.
  step(dt) {
    let t = Math.min(dt, 0.064);
    const h = 1 / 240;
    while (t > 1e-6) {
      const s = Math.min(h, t);
      const a = -this.k * (this.x - this.target) - this.c * this.v;
      this.v += a * s;
      this.x += this.v * s;
      t -= s;
    }
    return this.x;
  }
  resting(eps = 1e-4) { return Math.abs(this.x - this.target) < eps && Math.abs(this.v) < eps * 10; }
}

export const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
export const lerp = (a, b, t) => a + (b - a) * t;
export const smooth = (a, b, x) => { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); };
export const easeInOut = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);

const euro = new Intl.NumberFormat('en-IE', { style: 'currency', currency: 'EUR', minimumFractionDigits: 2, maximumFractionDigits: 2 });
export const money = (x) => euro.format(Math.round(x * 100) / 100);
