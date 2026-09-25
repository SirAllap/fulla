// The liquid inside the golden band: a small GPU fluid (Stam's stable fluids: advect,
// project, advect dye) held inside the circle and under a free surface. Written by hand
// for this page. The surface is a row of 64 coupled columns with its mean kept at zero,
// as the app keeps its liquid surfaces (core/design/Liquid.kt).
// The liquid is cream (ink on paper); stirring it lifts gold from the band into it,
// the gold is heavier than the liquid, sinks, and fades. With nobody touching it,
// everything comes to rest and the loop stops.

import { Spring, SETTLE, SNAPPY } from './spring.js';

const COLS = 64;
const R = 0.41;          // liquid radius in canvas units (the ring's inner edge is 0.408)
const REST = 0.015;      // at rest the band is filled a little more than half (docs/design.md, "The mark")
const S_AMP = 0.03;      // the calm S of the mark

const VS = `#version 300 es
in vec2 aPos; out vec2 vUv;
void main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }`;

const MASK = `
uniform vec2 uG;          // gravity, unit, canvas units (y up)
uniform float uLevel;     // surface height above the centre, along -g
uniform float uH[${COLS}];
uniform float uS;         // amplitude of the resting S
float surfAt(float s){
  float t = clamp((s / ${R.toFixed(3)}) * 0.5 + 0.5, 0.0, 1.0) * ${(COLS - 1).toFixed(1)};
  int i = int(floor(t)); int j = min(i + 1, ${COLS - 1});
  float h = mix(uH[i], uH[j], fract(t));
  return uLevel + h + uS * sin(-3.14159265 * s / ${R.toFixed(3)});
}
// signed distances: >0 inside the liquid
vec2 liquid(vec2 p){
  vec2 d = p - vec2(0.5);
  vec2 gp = vec2(-uG.y, uG.x);
  float s = dot(d, gp);
  float up = dot(d, -uG);
  return vec2(${R.toFixed(3)} - length(d), surfAt(s) - up);
}`;

const FS = {
  advect: `#version 300 es
precision highp float; in vec2 vUv; out vec4 o;
uniform sampler2D uVel, uSrc; uniform vec2 uTexel; uniform float uDt, uDiss;
void main(){
  vec2 c = vUv - uDt * texture(uVel, vUv).xy * uTexel;
  o = uDiss * texture(uSrc, c);
}`,
  splat: `#version 300 es
precision highp float; in vec2 vUv; out vec4 o;
uniform sampler2D uSrc; uniform vec2 uPoint; uniform vec4 uValue; uniform float uRadius;
void main(){
  vec2 d = vUv - uPoint;
  o = texture(uSrc, vUv) + uValue * exp(-dot(d, d) / uRadius);
}`,
  weight: `#version 300 es
precision highp float; in vec2 vUv; out vec4 o;
uniform sampler2D uVel, uDye; uniform vec2 uGv; uniform float uDt;
void main(){
  vec4 v = texture(uVel, vUv);
  float gold = texture(uDye, vUv).r;
  o = vec4(v.xy + uGv * gold * uDt, 0.0, 1.0);
}`,
  divergence: `#version 300 es
precision highp float; in vec2 vUv; out vec4 o;
uniform sampler2D uVel; uniform vec2 uTexel;
void main(){
  float L = texture(uVel, vUv - vec2(uTexel.x, 0.0)).x;
  float Rr = texture(uVel, vUv + vec2(uTexel.x, 0.0)).x;
  float B = texture(uVel, vUv - vec2(0.0, uTexel.y)).y;
  float T = texture(uVel, vUv + vec2(0.0, uTexel.y)).y;
  o = vec4(0.5 * (Rr - L + T - B), 0.0, 0.0, 1.0);
}`,
  pressure: `#version 300 es
precision highp float; in vec2 vUv; out vec4 o;
uniform sampler2D uP, uDiv; uniform vec2 uTexel;
void main(){
  float L = texture(uP, vUv - vec2(uTexel.x, 0.0)).x;
  float Rr = texture(uP, vUv + vec2(uTexel.x, 0.0)).x;
  float B = texture(uP, vUv - vec2(0.0, uTexel.y)).x;
  float T = texture(uP, vUv + vec2(0.0, uTexel.y)).x;
  float d = texture(uDiv, vUv).x;
  o = vec4((L + Rr + B + T - d) * 0.25, 0.0, 0.0, 1.0);
}`,
  scale: `#version 300 es
precision highp float; in vec2 vUv; out vec4 o;
uniform sampler2D uSrc; uniform float uK;
void main(){ o = uK * texture(uSrc, vUv); }`,
  gradient: `#version 300 es
precision highp float; in vec2 vUv; out vec4 o;
uniform sampler2D uP, uVel; uniform vec2 uTexel;
${MASK}
void main(){
  float L = texture(uP, vUv - vec2(uTexel.x, 0.0)).x;
  float Rr = texture(uP, vUv + vec2(uTexel.x, 0.0)).x;
  float B = texture(uP, vUv - vec2(0.0, uTexel.y)).x;
  float T = texture(uP, vUv + vec2(0.0, uTexel.y)).x;
  vec2 v = texture(uVel, vUv).xy - 0.5 * vec2(Rr - L, T - B);
  vec2 m = liquid(vUv);
  // the walls and the surface hold the liquid in
  v *= smoothstep(0.0, 0.02, min(m.x, m.y + 0.01));
  o = vec4(v, 0.0, 1.0);
}`,
  display: `#version 300 es
precision highp float; in vec2 vUv; out vec4 o;
uniform sampler2D uDye; uniform vec3 uLiquid, uDeep, uGold, uSheen; uniform float uPx;
${MASK}
void main(){
  vec2 m = liquid(vUv);
  float a = smoothstep(0.0, uPx, min(m.x, m.y));
  if (a <= 0.0) { o = vec4(0.0); return; }
  float depth = clamp(m.y / (2.0 * ${R.toFixed(3)}), 0.0, 1.0);
  vec3 col = mix(uLiquid, uDeep, depth * 0.55);
  float gold = clamp(texture(uDye, vUv).r, 0.0, 1.0);
  gold = smoothstep(0.02, 0.85, gold);
  col = mix(col, uGold, gold);
  // a thin sheen where light catches the surface
  float sheen = 1.0 - smoothstep(0.0, 0.018, m.y);
  col = mix(col, uSheen, sheen * 0.32);
  // a little shade where the liquid meets the band
  col *= 1.0 - (1.0 - smoothstep(0.0, 0.05, m.x)) * 0.12;
  o = vec4(col * a, a);
}`,
};

function hex3(hex) {
  const n = parseInt(hex.trim().replace('#', ''), 16);
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
}

export function createBandFluid(canvas) {
  const gl = canvas.getContext('webgl2', { alpha: true, premultipliedAlpha: true, antialias: false, depth: false, stencil: false, powerPreference: 'low-power' });
  if (!gl) return null;
  if (!gl.getExtension('EXT_color_buffer_float') && !gl.getExtension('EXT_color_buffer_half_float')) return null;

  const compile = (type, src) => {
    const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
    return s;
  };
  const vs = compile(gl.VERTEX_SHADER, VS);
  const progs = {};
  try {
    for (const [name, src] of Object.entries(FS)) {
      const p = gl.createProgram();
      gl.attachShader(p, vs); gl.attachShader(p, compile(gl.FRAGMENT_SHADER, src));
      gl.bindAttribLocation(p, 0, 'aPos'); gl.linkProgram(p);
      if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
      const u = {};
      const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
      for (let i = 0; i < n; i++) {
        const info = gl.getActiveUniform(p, i);
        const key = info.name.replace('[0]', '');
        u[key] = gl.getUniformLocation(p, info.name);
      }
      progs[name] = { p, u };
    }
  } catch (e) {
    console.warn('Fulla band: falling back to the still mark.', e);
    return null;
  }

  const buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const vao = gl.createVertexArray();
  gl.bindVertexArray(vao);
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

  function target(size) {
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, size, size, 0, gl.RGBA, gl.HALF_FLOAT, null);
    const fb = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    gl.viewport(0, 0, size, size);
    gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT);
    return { tex, fb, size };
  }
  const pair = (size) => {
    const a = target(size), b = target(size);
    return { read: a, write: b, swap() { const t = this.read; this.read = this.write; this.write = t; } };
  };

  const SIM = 128;
  const DYE = 320;
  const vel = pair(SIM), dye = pair(DYE), prs = pair(SIM), div = target(SIM);
  if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) return null;
  const simTexel = [1 / SIM, 1 / SIM];

  // --- the surface: 64 coupled columns ---
  const h = new Float32Array(COLS), hv = new Float32Array(COLS);
  const level = new Spring(-0.46, SETTLE);
  const angle = new Spring(0, SETTLE);
  let prevAngle = 0;
  let sAmp = S_AMP;

  function stepSurface(dt) {
    const ds = (2 * R) / (COLS - 1);
    const c2 = 0.075 / (ds * ds);
    const sub = 1 / 240;
    let t = Math.min(dt, 0.05);
    while (t > 1e-6) {
      const s = Math.min(sub, t);
      for (let i = 0; i < COLS; i++) {
        const l = h[i > 0 ? i - 1 : 0], r = h[i < COLS - 1 ? i + 1 : COLS - 1];
        const vl = hv[i > 0 ? i - 1 : 0], vr = hv[i < COLS - 1 ? i + 1 : COLS - 1];
        // tension, a little viscosity along the surface (no teeth), damping, and a pull back to level
        const a = c2 * (l + r - 2 * h[i]) + 18 * (vl + vr - 2 * hv[i]) - 2.4 * hv[i] - 6 * h[i];
        hv[i] += a * s;
      }
      let mean = 0;
      for (let i = 0; i < COLS; i++) { h[i] = Math.max(-0.08, Math.min(0.08, h[i] + hv[i] * s)); mean += h[i]; }
      mean /= COLS;
      for (let i = 0; i < COLS; i++) h[i] -= mean;   // the mean stays at zero
      t -= s;
    }
  }

  // --- state ---
  let lastInput = -1e9, now = 0, running = false, raf = 0, visible = true, enabled = true;
  let colors = null;
  let pouring = 0;
  const splats = [];
  let gx = 0, gy = -1;

  function readColors() {
    const cs = getComputedStyle(document.documentElement);
    const dark = matchMedia('(prefers-color-scheme: dark)').matches;
    const liquid = hex3(cs.getPropertyValue('--liquid'));
    colors = {
      liquid,
      deep: dark ? hex3('#D9D0BE') : hex3('#0E0C1C'),
      gold: hex3('#E9B949'),
      sheen: dark ? hex3('#FFFFFF') : hex3('#5A5474'),
    };
  }
  readColors();

  function use(name) { const pr = progs[name]; gl.useProgram(pr.p); return pr.u; }
  function bindTex(unit, tex, loc) { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex); gl.uniform1i(loc, unit); }
  function draw(fbo) {
    if (fbo) { gl.bindFramebuffer(gl.FRAMEBUFFER, fbo.fb); gl.viewport(0, 0, fbo.size, fbo.size); }
    else { gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.viewport(0, 0, canvas.width, canvas.height); }
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }
  function maskUniforms(u) {
    gl.uniform2f(u.uG, gx, gy);
    gl.uniform1f(u.uLevel, level.x);
    gl.uniform1fv(u.uH, h);
    gl.uniform1f(u.uS, sAmp);
  }

  function splat(x, y, fx, fy, gold, radius = 0.0016) {
    let u = use('splat');
    bindTex(0, vel.read.tex, u.uSrc);
    gl.uniform2f(u.uPoint, x, y);
    gl.uniform4f(u.uValue, fx, fy, 0, 0);
    gl.uniform1f(u.uRadius, radius);
    draw(vel.write); vel.swap();
    if (gold > 0) {
      u = use('splat');
      bindTex(0, dye.read.tex, u.uSrc);
      gl.uniform2f(u.uPoint, x, y);
      gl.uniform4f(u.uValue, gold, 0, 0, 0);
      gl.uniform1f(u.uRadius, radius * 0.7);
      draw(dye.write); dye.swap();
    }
  }

  function simulate(dt) {
    let u;
    for (const s of splats.splice(0)) splat(s.x, s.y, s.fx, s.fy, s.gold, s.r);

    // the gold is heavier than the liquid: it sinks
    u = use('weight');
    bindTex(0, vel.read.tex, u.uVel); bindTex(1, dye.read.tex, u.uDye);
    gl.uniform2f(u.uGv, gx * 90, gy * 90); gl.uniform1f(u.uDt, dt);
    draw(vel.write); vel.swap();

    u = use('divergence');
    bindTex(0, vel.read.tex, u.uVel); gl.uniform2fv(u.uTexel, simTexel);
    draw(div);

    u = use('scale');
    bindTex(0, prs.read.tex, u.uSrc); gl.uniform1f(u.uK, 0.8);
    draw(prs.write); prs.swap();

    u = use('pressure');
    gl.uniform2fv(u.uTexel, simTexel);
    for (let i = 0; i < 18; i++) {
      bindTex(0, prs.read.tex, u.uP); bindTex(1, div.tex, u.uDiv);
      draw(prs.write); prs.swap();
    }

    u = use('gradient');
    bindTex(0, prs.read.tex, u.uP); bindTex(1, vel.read.tex, u.uVel);
    gl.uniform2fv(u.uTexel, simTexel); maskUniforms(u);
    draw(vel.write); vel.swap();

    u = use('advect');
    bindTex(0, vel.read.tex, u.uVel); bindTex(1, vel.read.tex, u.uSrc);
    gl.uniform2fv(u.uTexel, simTexel); gl.uniform1f(u.uDt, dt);
    gl.uniform1f(u.uDiss, Math.exp(-dt / 1.6));
    draw(vel.write); vel.swap();

    u = use('advect');
    bindTex(0, vel.read.tex, u.uVel); bindTex(1, dye.read.tex, u.uSrc);
    gl.uniform2fv(u.uTexel, simTexel); gl.uniform1f(u.uDt, dt);
    gl.uniform1f(u.uDiss, Math.exp(-dt / 2.6));
    draw(dye.write); dye.swap();
  }

  function render() {
    const u = use('display');
    bindTex(0, dye.read.tex, u.uDye);
    maskUniforms(u);
    gl.uniform3fv(u.uLiquid, colors.liquid);
    gl.uniform3fv(u.uDeep, colors.deep);
    gl.uniform3fv(u.uGold, colors.gold);
    gl.uniform3fv(u.uSheen, colors.sheen);
    gl.uniform1f(u.uPx, 1.6 / canvas.width);
    gl.clearColor(0, 0, 0, 0);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, canvas.width, canvas.height);
    gl.clear(gl.COLOR_BUFFER_BIT);
    draw(null);
  }

  function energy() {
    let e = 0;
    for (let i = 0; i < COLS; i++) e += Math.abs(hv[i]) + Math.abs(h[i]) * 4;
    return e + Math.abs(level.v) + Math.abs(level.x - level.target) + Math.abs(angle.v) * 0.2;
  }

  let last = 0;
  function frame(t) {
    raf = 0;
    const dt = last ? Math.min((t - last) / 1000, 1 / 30) : 1 / 60;
    last = t; now += dt;

    // gravity follows the tilt, through a spring, and the change of angle sloshes the surface
    angle.step(dt);
    const th = angle.x;
    gx = Math.sin(th); gy = -Math.cos(th);
    const dA = th - prevAngle; prevAngle = th;
    if (Math.abs(dA) > 1e-5) {
      for (let i = 0; i < COLS; i++) hv[i] += ((i / (COLS - 1)) * 2 - 1) * dA * -9;
    }

    const lv = level.v;
    level.step(dt);
    // a rising level pushes the middle up and the walls down: the slosh
    if (Math.abs(lv) > 0.01) for (let i = 0; i < COLS; i++) { const x = (i / (COLS - 1)) * 2 - 1; hv[i] += (1 - 2 * x * x) * lv * dt * -2.2; }
    stepSurface(dt);

    if (pouring > 0) {
      pouring -= dt;
      const k = Math.random();
      splats.push({ x: 0.5 + (k - 0.5) * 0.18, y: 0.5 + level.x - 0.18, fx: (Math.random() - 0.5) * 220, fy: 360 + Math.random() * 200, gold: 0.9, r: 0.0012 });
    }

    simulate(dt);
    render();

    const quiet = now - lastInput > 10 && pouring <= 0 && energy() < 0.02;
    if (running && visible && enabled && !quiet) raf = requestAnimationFrame(frame);
    else { running = false; last = 0; }
  }

  function wake() {
    lastInput = now;
    if (!running && visible && enabled) { running = true; raf = requestAnimationFrame(frame); }
  }

  // --- input: the pointer stirs; a finger near the surface makes a wave ---
  let px = null, py = null, pt = 0;
  function onMove(e) {
    const r = canvas.getBoundingClientRect();
    const x = (e.clientX - r.left) / r.width;
    const y = 1 - (e.clientY - r.top) / r.height;
    const t = performance.now();
    if (px !== null && t - pt < 120) {
      const dtp = Math.max((t - pt) / 1000, 1 / 240);
      const vx = (x - px) / dtp, vy = (y - py) / dtp;
      const speed = Math.hypot(vx, vy);
      if (speed > 0.02) {
        splats.push({ x, y, fx: vx * SIM * 0.55, fy: vy * SIM * 0.55, gold: Math.min(0.9, speed * 0.35), r: 0.0018 });
        // near the surface, the pointer pushes it
        const d = { x: x - 0.5, y: y - 0.5 };
        const s = d.x * -gy + d.y * gx;
        const up = d.x * -gx + d.y * -gy;
        if (Math.abs(up - level.x) < 0.1 && Math.abs(s) < R) {
          const i = Math.round(((s / R) * 0.5 + 0.5) * (COLS - 1));
          const push = Math.max(-0.5, Math.min(0.5, vy * 0.12 + Math.abs(vx) * 0.05 * Math.sign(level.x - up + 1e-3)));
          for (let j = -8; j <= 8; j++) {
            const k = i + j;
            if (k >= 0 && k < COLS) hv[k] += push * Math.exp(-(j * j) / 18);
          }
        }
        wake();
      }
    }
    px = x; py = y; pt = t;
  }
  canvas.addEventListener('pointermove', onMove, { passive: true });
  canvas.addEventListener('pointerdown', (e) => { px = null; onMove(e); }, { passive: true });
  canvas.addEventListener('pointerleave', () => { px = null; }, { passive: true });

  return {
    intro() {
      level.snap(-0.46);
      level.set(REST);
      pouring = 0.55;
      for (let i = 0; i < COLS; i++) hv[i] = 0;
      wake();
    },
    rest() { level.snap(REST); render(); },
    tilt(rad) { angle.set(Math.max(-0.7, Math.min(0.7, rad))); wake(); },
    setVisible(v) { visible = v; if (v) wake(); },
    setEnabled(v) { if (v === enabled) return; enabled = v; if (v) wake(); },
    theme() { readColors(); if (!running) render(); },
    resize() {
      const r = canvas.getBoundingClientRect();
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const w = Math.max(2, Math.round(r.width * dpr));
      if (canvas.width !== w) { canvas.width = w; canvas.height = w; }
      if (!running) render();
    },
  };
}
