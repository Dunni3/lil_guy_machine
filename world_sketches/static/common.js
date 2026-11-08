'use strict';
// Shared drawing kit: palette (from tools/workshop/guy.html), seeded noise, crayon strokes and the guy.

const TAU = Math.PI * 2;
const W = 720, H = 440;
const INK = '#4a3a4f', BUTTER = '#ffe3a1', BUTTER_SHADE = '#f0c27a', BLUSH = '#ff9fb4';
const LEAF = '#a8dcb0', STEM = '#8ec79c', PAPER = '#fbf4ea', WHITE = '#fffdf8';
const PAL = { apricot: '#ffd8c0', rose: '#f8bfd0', lavender: '#cdbaf0', sky: '#b8d7f4', mint: '#b5e5cd', butter: '#ffe3a1' };
const HAND = 'Gaegu, "Comic Sans MS", cursive';

function rng(seed) {
  let a = (Math.imul(Math.floor(seed * 1000) | 0, 2654435761) + 1013904223) >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function hash2(x, y, s = 0) {
  let h = (Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263) + Math.imul(s | 0, 982451653)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}
/** The line boil: a frame number that ticks seven times a second, mixed into stroke seeds. */
const boil = t => Math.floor(t * 7) % 3;
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const lerp = (a, b, u) => a + (b - a) * u;
const ease = u => { u = clamp(u, 0, 1); return u * u * (3 - 2 * u); };
const easeBack = u => { const c = 1.9; u = clamp(u, 0, 1) - 1; return 1 + u * u * ((c + 1) * u + c); };

function hexRgb(h) {
  h = h.replace('#', '');
  if (h.length === 3) h = h.split('').map(c => c + c).join('');
  const n = parseInt(h, 16);
  return [n >> 16 & 255, n >> 8 & 255, n & 255];
}
function mix(a, b, u) {
  const A = hexRgb(a), B = hexRgb(b);
  return '#' + A.map((v, i) => Math.round(lerp(v, B[i], clamp(u, 0, 1))).toString(16).padStart(2, '0')).join('');
}
const shade = (c, amt) => amt < 0 ? mix(c, '#2a2030', -amt) : mix(c, '#ffffff', amt);
function rgba(c, a) { const [r, g, b] = hexRgb(c); return `rgba(${r},${g},${b},${a})`; }

// --- paths -------------------------------------------------------------------------------------

function smoothClosed(ctx, pts) {
  const n = pts.length;
  const mid = i => { const a = pts[i % n], b = pts[(i + 1) % n]; return [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]; };
  ctx.beginPath();
  const s = mid(0);
  ctx.moveTo(s[0], s[1]);
  for (let i = 1; i <= n; i++) { const p = pts[i % n], q = mid(i); ctx.quadraticCurveTo(p[0], p[1], q[0], q[1]); }
  ctx.closePath();
}
function wellipse(ctx, cx, cy, rx, ry, seed, wob = 0.05, n = 16, rot = 0) {
  const r = rng(seed), pts = [], cr = Math.cos(rot), sr = Math.sin(rot);
  for (let i = 0; i < n; i++) {
    const a = i / n * TAU, k = 1 + (r() - 0.5) * 2 * wob;
    const ex = Math.cos(a) * rx * k, ey = Math.sin(a) * ry * k;
    pts.push([cx + ex * cr - ey * sr, cy + ex * sr + ey * cr]);
  }
  smoothClosed(ctx, pts);
}
/** A hand-drawn polygon: edges subdivided and jittered. */
function wpoly(ctx, pts, seed, j = 1.2, closed = true) {
  const r = rng(seed), n = pts.length, m = closed ? n : n - 1;
  ctx.beginPath();
  for (let i = 0; i < m; i++) {
    const a = pts[i], b = pts[(i + 1) % n];
    const k = Math.max(1, Math.round(Math.hypot(b[0] - a[0], b[1] - a[1]) / 22));
    for (let q = 0; q < k; q++) {
      const u = q / k, px = a[0] + (b[0] - a[0]) * u + (r() - 0.5) * j * 2, py = a[1] + (b[1] - a[1]) * u + (r() - 0.5) * j * 2;
      if (i === 0 && q === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    }
  }
  if (closed) ctx.closePath();
  else { const b = pts[n - 1]; ctx.lineTo(b[0] + (r() - 0.5) * j, b[1] + (r() - 0.5) * j); }
}
const wrect = (ctx, x, y, w, h, seed, j = 1.2) => wpoly(ctx, [[x, y], [x + w, y], [x + w, y + h], [x, y + h]], seed, j);
function wline(ctx, x1, y1, x2, y2, seed, j = 1.2) { wpoly(ctx, [[x1, y1], [x2, y2]], seed, j, false); ctx.stroke(); }
function roundRectPath(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
}

/** Diagonal crayon hatching across a box (clip to a shape first). */
function hatch(ctx, x, y, w, h, color, gap, seed, alpha = 0.55, lw = 1.4) {
  const r = rng(seed * 13 + 5);
  ctx.save();
  ctx.strokeStyle = color; ctx.globalAlpha *= alpha; ctx.lineWidth = lw; ctx.lineCap = 'round';
  ctx.beginPath();
  for (let d = -h; d < w; d += gap) {
    const jt = (r() - 0.5) * gap * 0.6;
    ctx.moveTo(x + d + jt, y + h);
    ctx.lineTo(x + d + h + jt + (r() - 0.5) * 2, y);
  }
  ctx.stroke();
  ctx.restore();
}
/** Fill, optional hatch, ink outline: the crayon look in one call. pathFn rebuilds the same path. */
function crayon(ctx, pathFn, fill, o = {}) {
  ctx.save();
  ctx.lineJoin = 'round'; ctx.lineCap = 'round';
  if (fill) { pathFn(); ctx.fillStyle = fill; ctx.fill(); }
  if (o.hatch) {
    const b = o.bounds || [0, 0, W, H];
    ctx.save(); pathFn(); ctx.clip();
    hatch(ctx, b[0], b[1], b[2], b[3], o.hatch, o.gap || 6, o.seed || 1, o.ha ?? 0.55, o.hlw || 1.4);
    ctx.restore();
  }
  if (o.stroke !== false) { pathFn(); ctx.strokeStyle = o.stroke || INK; ctx.lineWidth = o.lw || 2; ctx.stroke(); }
  ctx.restore();
}
function cloudPath(ctx, cx, cy, s, seed) {
  const pts = [], r = rng(seed);
  for (let i = 0; i < 18; i++) {
    const a = i / 18 * TAU, top = Math.sin(a) < 0;
    const k = (top ? 1 + 0.32 * Math.abs(Math.sin(a * 2.5)) : 0.9) * (1 + (r() - 0.5) * 0.06);
    pts.push([cx + Math.cos(a) * 46 * s * k, cy + Math.sin(a) * 17 * s * k]);
  }
  smoothClosed(ctx, pts);
}

let _grain = null;
/** Paper grain over the whole canvas. */
function grain(ctx, alpha = 1) {
  if (!_grain) {
    const c = document.createElement('canvas');
    c.width = c.height = 160;
    const g = c.getContext('2d'), im = g.createImageData(160, 160), r = rng(7);
    for (let i = 0; i < im.data.length; i += 4) {
      const v = r() < 0.5 ? 70 : 255;
      im.data[i] = im.data[i + 1] = im.data[i + 2] = v;
      im.data[i + 3] = r() < 0.35 ? Math.floor(r() * 26) : 0;
    }
    g.putImageData(im, 0, 0);
    _grain = c;
  }
  ctx.save();
  ctx.globalAlpha = alpha;
  ctx.fillStyle = ctx.createPattern(_grain, 'repeat');
  ctx.fillRect(0, 0, W, H);
  ctx.restore();
}

function text(ctx, s, x, y, o = {}) {
  ctx.save();
  ctx.font = o.font || `700 ${o.size || 20}px ${HAND}`;
  ctx.fillStyle = o.color || INK;
  ctx.textAlign = o.align || 'center';
  ctx.textBaseline = o.base || 'middle';
  if (o.alpha != null) ctx.globalAlpha = o.alpha;
  ctx.fillText(s, x, y);
  ctx.restore();
}
function bubble(ctx, x, y, s, o = {}) {
  ctx.save();
  ctx.globalAlpha = o.alpha ?? 1;
  ctx.font = `700 ${o.size || 19}px ${HAND}`;
  const w = ctx.measureText(s).width + 24, h = 32, bx = x - w / 2, by = y - h - 14;
  ctx.lineWidth = 2; ctx.strokeStyle = INK; ctx.fillStyle = WHITE;
  roundRectPath(ctx, bx, by, w, h, 15); ctx.fill(); ctx.stroke();
  for (const [dx, dy, r] of [[-4, 5, 4], [-9, 12, 2.5]]) { ctx.beginPath(); ctx.arc(x + dx, by + h + dy, r, 0, TAU); ctx.fill(); ctx.stroke(); }
  ctx.fillStyle = INK; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.fillText(s, x, by + h / 2 + 1);
  ctx.restore();
}

// --- the guy -----------------------------------------------------------------------------------

/**
 * The butter-yellow tater, feet at (x, y), about 60*s tall with his sprout.
 * o: t, walk, dir (1 right, -1 left), style ('crayon' boils and hatches, 'flat', 'paper'), face ('sleep'), lantern.
 */
function drawGuy(ctx, x, y, s, o = {}) {
  const t = o.t || 0, walk = !!o.walk, dir = o.dir || 1, style = o.style || 'crayon';
  const crayonish = style === 'crayon', paper = style === 'paper';
  const bf = crayonish ? boil(t) : 0, wob = crayonish ? 0.04 : 0;
  const outline = paper ? null : (o.outline || INK);
  const lw = Math.max(1.2, 2.3 * s);
  const ph = t * 9;
  const hop = walk ? Math.abs(Math.sin(ph)) * 7 * s : 0;
  const sq = walk ? 1 + 0.06 * Math.cos(ph * 2) : 1 + 0.025 * Math.sin(t * 2.2);
  const rx = 20 * s / Math.sqrt(sq), ry = 24 * s * sq;
  const cx = x, cy = y - hop - 5 * s - ry;
  ctx.save();
  ctx.lineJoin = 'round'; ctx.lineCap = 'round';
  if (o.shadow !== false) {
    ctx.fillStyle = 'rgba(60,40,70,0.16)';
    ctx.beginPath(); ctx.ellipse(x, y, 17 * s * (1 - hop / (40 * s)), 4 * s, 0, 0, TAU); ctx.fill();
  }
  const shape = (pathFn, fill, seed, hatchSide) => {
    ctx.save();
    if (paper) { ctx.shadowColor = 'rgba(40,20,50,0.3)'; ctx.shadowBlur = 6; ctx.shadowOffsetY = 3; }
    pathFn(seed); ctx.fillStyle = fill; ctx.fill();
    ctx.restore();
    if (crayonish && hatchSide) {
      ctx.save();
      pathFn(seed); ctx.clip();
      ctx.beginPath(); ctx.rect(cx + rx * 0.15, cy - ry * 2, rx * 3, ry * 4); ctx.clip();
      hatch(ctx, cx - rx, cy - ry * 1.2, rx * 2.4, ry * 2.6, BUTTER_SHADE, 3.6 * s + 1.2, seed, 0.85, 1.3 * s + 0.4);
      ctx.restore();
    }
    if (outline) { pathFn(seed); ctx.strokeStyle = outline; ctx.lineWidth = lw; ctx.stroke(); }
    if (paper) { pathFn(seed); ctx.strokeStyle = 'rgba(255,255,255,0.8)'; ctx.lineWidth = 1.3; ctx.stroke(); }
  };
  const lift = walk ? Math.sin(ph) : 0;
  for (const k of [-1, 1]) {
    const fl = walk ? Math.max(0, k * lift) * 4 * s : 0;
    shape(sd => wellipse(ctx, x + k * 8 * s, y - 3.5 * s - hop * 0.7 - fl, 7 * s, 4.2 * s, sd, wob, 10), BUTTER, 40 + k + bf * 3, false);
  }
  const swing = walk ? Math.sin(ph) * 3 * s : Math.sin(t * 2.2) * 0.6 * s;
  for (const k of [-1, 1]) {
    shape(sd => wellipse(ctx, cx + k * (rx + 1.5 * s), cy + ry * 0.25 + k * swing, 4.6 * s, 6.5 * s, sd, wob, 10, k * 0.45), BUTTER, 50 + k + bf * 3, false);
  }
  shape(sd => wellipse(ctx, cx, cy, rx, ry, sd, wob, 18), BUTTER, 60 + bf * 3, true);

  // face
  const fx = cx + dir * 3.5 * s, fy = cy - 2 * s;
  ctx.fillStyle = rgba(BLUSH, 0.75);
  for (const k of [-1, 1]) { ctx.beginPath(); ctx.ellipse(fx + k * 11.5 * s, fy + 6 * s, 3.6 * s, 2.3 * s, 0, 0, TAU); ctx.fill(); }
  ctx.strokeStyle = INK; ctx.fillStyle = INK; ctx.lineWidth = 1.6 * s + 0.3;
  const blink = ((t + (o.blinkOffset || 0)) % 3.7) < 0.12;
  for (const k of [-1, 1]) {
    const ex = fx + k * 6.5 * s;
    if (o.face === 'sleep') { ctx.beginPath(); ctx.arc(ex, fy, 2.6 * s, 0.15 * Math.PI, 0.85 * Math.PI); ctx.stroke(); }
    else if (blink) { ctx.beginPath(); ctx.moveTo(ex - 2.5 * s, fy); ctx.lineTo(ex + 2.5 * s, fy); ctx.stroke(); }
    else {
      ctx.fillStyle = INK; ctx.beginPath(); ctx.ellipse(ex, fy, 2.5 * s, 3.1 * s, 0, 0, TAU); ctx.fill();
      ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.arc(ex + 0.9 * s, fy - 1.1 * s, 0.9 * s, 0, TAU); ctx.fill();
    }
  }
  ctx.strokeStyle = '#b05a6e'; ctx.lineWidth = 1.5 * s + 0.3;
  ctx.beginPath(); ctx.arc(fx, fy + 3.5 * s, 3 * s, 0.2 * Math.PI, 0.8 * Math.PI); ctx.stroke();

  // sprout
  const sway = Math.sin(t * 2.6) * 0.18 + (walk ? Math.sin(ph) * 0.1 : 0);
  ctx.save();
  ctx.translate(cx, cy - ry + 1.5 * s); ctx.rotate(sway);
  ctx.strokeStyle = outline || '#6a9b77'; ctx.lineWidth = lw * 0.85;
  ctx.beginPath(); ctx.moveTo(0, 0); ctx.quadraticCurveTo(-1 * s, -5 * s, 1.5 * s, -9 * s); ctx.stroke();
  shape(sd => wellipse(ctx, -4 * s, -10 * s, 5 * s, 2.6 * s, sd, wob, 10, -0.5), LEAF, 70 + bf, false);
  shape(sd => wellipse(ctx, 6.5 * s, -11 * s, 5 * s, 2.6 * s, sd, wob, 10, 0.45), LEAF, 71 + bf, false);
  ctx.restore();

  if (o.lantern) {
    const hx = cx + dir * (rx + 6 * s), hy = cy + ry * 0.2;
    ctx.strokeStyle = INK; ctx.lineWidth = 1.2 * s + 0.4;
    ctx.beginPath(); ctx.moveTo(hx, hy - 4 * s); ctx.lineTo(hx, hy + 2 * s); ctx.stroke();
    roundRectPath(ctx, hx - 4.5 * s, hy + 2 * s, 9 * s, 11 * s, 2.5 * s);
    ctx.fillStyle = '#ffcf6b'; ctx.fill(); ctx.stroke();
    ctx.fillStyle = INK; ctx.fillRect(hx - 5 * s, hy + 1 * s, 10 * s, 2 * s);
  }
  ctx.restore();
  return { cx, cy, rx, ry, hand: [cx + dir * (rx + 6 * s), cy + ry * 0.2 + 8 * s] };
}

// --- sketch registry ---------------------------------------------------------------------------

const SKETCHES = {};
/** def: init(S), frame(S, t, dt), click(S, x, y), hover(S, x, y) -> true for a pointer cursor. */
function sketch(id, def) { SKETCHES[id] = def; }
