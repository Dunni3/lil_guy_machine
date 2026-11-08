'use strict';
// Out the front door: meadow walk, pixel island, paper puppet street, lantern night walk, tiny planet.

// ================================================================================================
// Crayon meadow walk
// ================================================================================================

const SKY_KEYS = [
  [0.00, '#2a2547', '#4b3f73', 1], [0.18, '#2a2547', '#4b3f73', 1], [0.25, '#f6b9a8', '#ffe0c2', 0.3],
  [0.33, '#a9d3f2', '#e8f3fa', 0], [0.65, '#a9d3f2', '#e8f3fa', 0], [0.74, '#c3a6e0', '#ffc4b0', 0.4],
  [0.82, '#2a2547', '#4b3f73', 1], [1.00, '#2a2547', '#4b3f73', 1],
];
function skyAt(u) {
  for (let i = 1; i < SKY_KEYS.length; i++) {
    const a = SKY_KEYS[i - 1], b = SKY_KEYS[i];
    if (u <= b[0]) {
      const k = (u - a[0]) / (b[0] - a[0] || 1);
      return { top: mix(a[1], b[1], k), low: mix(a[2], b[2], k), night: lerp(a[3], b[3], k) };
    }
  }
  return { top: SKY_KEYS[0][1], low: SKY_KEYS[0][2], night: 1 };
}

const LANDMARKS = {
  home: {
    say: 'home sweet home',
    draw(ctx, x, y, B, t, n) {
      crayon(ctx, () => wrect(ctx, x + 22, y - 112, 14, 34, 1 + B, 0.8), '#c97b7f', { lw: 2 });
      ctx.strokeStyle = 'rgba(130,120,140,0.5)'; ctx.lineWidth = 2.5;
      for (let i = 0; i < 3; i++) {
        const p = (t * 0.35 + i / 3) % 1;
        ctx.globalAlpha = 1 - p;
        ctx.beginPath(); ctx.arc(x + 29 + Math.sin(p * 6) * 5, y - 118 - p * 34, 3 + p * 7, 0, TAU); ctx.stroke();
      }
      ctx.globalAlpha = 1;
      crayon(ctx, () => wrect(ctx, x - 48, y - 62, 96, 62, 2 + B), BUTTER, { hatch: BUTTER_SHADE, bounds: [x - 48, y - 62, 96, 62], lw: 2.2 });
      crayon(ctx, () => wpoly(ctx, [[x - 60, y - 60], [x, y - 108], [x + 60, y - 60]], 3 + B), '#e9a3a0', { hatch: '#c97b7f', bounds: [x - 60, y - 108, 120, 50], lw: 2.2 });
      crayon(ctx, () => wrect(ctx, x - 12, y - 38, 24, 38, 4 + B, 0.8), '#e46f7a', { lw: 2 });
      crayon(ctx, () => wrect(ctx, x + 20, y - 48, 20, 20, 5 + B, 0.8), n > 0.4 ? '#ffe28a' : '#dff0ff', { lw: 1.8 });
      crayon(ctx, () => wrect(ctx, x - 40, y - 48, 20, 20, 6 + B, 0.8), n > 0.4 ? '#ffe28a' : '#dff0ff', { lw: 1.8 });
    },
    lights: [[30, -38, 46], [-30, -38, 46]],
  },
  pond: {
    say: 'frogs?',
    draw(ctx, x, y, B, t) {
      crayon(ctx, () => wellipse(ctx, x, y - 4, 70, 15, 1 + B, 0.04), PAL.sky, { hatch: '#7fa9d6', bounds: [x - 72, y - 20, 144, 32], gap: 7, lw: 2 });
      ctx.strokeStyle = 'rgba(255,255,255,0.8)'; ctx.lineWidth = 1.5;
      for (let i = 0; i < 2; i++) { const p = (t * 0.4 + i / 2) % 1; ctx.globalAlpha = 1 - p; ctx.beginPath(); ctx.ellipse(x - 18 + i * 34, y - 5, 4 + p * 16, 1.5 + p * 4, 0, 0, TAU); ctx.stroke(); }
      ctx.globalAlpha = 1;
      for (const [dx, dy] of [[-34, -6], [24, -2], [40, -8]]) crayon(ctx, () => wellipse(ctx, x + dx, y + dy, 8, 3, dx + B), LEAF, { lw: 1.4 });
      ctx.strokeStyle = '#5f8f6a'; ctx.lineWidth = 2;
      for (let i = 0; i < 5; i++) { const rx = x + 58 + i * 5; wline(ctx, rx, y - 2, rx + Math.sin(t + i) * 2, y - 26 - (i % 3) * 7, i + B, 0.6); }
    },
  },
  tree: {
    say: 'good climbing tree',
    draw(ctx, x, y, B, t) {
      crayon(ctx, () => wpoly(ctx, [[x - 13, y], [x + 13, y], [x + 8, y - 88], [x - 8, y - 88]], 1 + B, 1), '#b98560', { hatch: '#916647', bounds: [x - 14, y - 90, 28, 90], gap: 5 });
      crayon(ctx, () => wellipse(ctx, x, y - 132, 76, 56, 2 + B, 0.08), '#9fd3a7', { hatch: '#6fae80', bounds: [x - 78, y - 190, 156, 116], gap: 6 });
      const sw = Math.sin(t * 1.5) * 7;
      ctx.strokeStyle = INK; ctx.lineWidth = 1.6;
      wline(ctx, x + 44, y - 86, x + 44 + sw, y - 26, 3 + B, 0.4); wline(ctx, x + 66, y - 86, x + 66 + sw, y - 26, 4 + B, 0.4);
      crayon(ctx, () => wrect(ctx, x + 40 + sw, y - 28, 30, 5, 5 + B, 0.6), '#d7a982', { lw: 1.8 });
    },
  },
  shop: {
    say: 'ooh, snacks',
    draw(ctx, x, y, B) {
      crayon(ctx, () => wrect(ctx, x - 52, y - 44, 104, 44, 1 + B), '#d7a982', { hatch: '#b98560', bounds: [x - 52, y - 44, 104, 44], lw: 2 });
      ctx.strokeStyle = INK; ctx.lineWidth = 2.2;
      wline(ctx, x - 48, y - 44, x - 48, y - 96, 2 + B, 0.8); wline(ctx, x + 48, y - 44, x + 48, y - 96, 3 + B, 0.8);
      for (let i = 0; i < 6; i++) {
        const sx = x - 60 + i * 20;
        crayon(ctx, () => wpoly(ctx, [[sx + i * 0, y - 112], [sx + 20, y - 112], [sx + 20, y - 92], [sx, y - 92]], 4 + i + B, 0.7), i % 2 ? WHITE : '#e46f7a', { lw: 1.6 });
      }
      for (let i = 0; i < 4; i++) crayon(ctx, () => wellipse(ctx, x - 34 + i * 22, y - 50, 8, 7, 12 + i + B), ['#e46f7a', '#ffc65c', '#8ec79c', '#cdbaf0'][i], { lw: 1.5 });
      text(ctx, 'SHOP', x, y - 22, { size: 22 });
    },
  },
  busstop: {
    say: 'town is that way',
    draw(ctx, x, y, B) {
      ctx.strokeStyle = INK; ctx.lineWidth = 3; wline(ctx, x, y, x, y - 92, 1 + B, 0.7);
      crayon(ctx, () => wpoly(ctx, [[x - 34, y - 110], [x + 30, y - 110], [x + 46, y - 94], [x + 30, y - 78], [x - 34, y - 78]], 2 + B, 0.8), PAL.sky, { lw: 2 });
      text(ctx, 'TOWN', x + 2, y - 93, { size: 19 });
      crayon(ctx, () => wrect(ctx, x + 20, y - 24, 62, 7, 3 + B, 0.7), '#d7a982', { lw: 2 });
      ctx.lineWidth = 2.2; wline(ctx, x + 28, y - 18, x + 28, y, 4 + B, 0.5); wline(ctx, x + 74, y - 18, x + 74, y, 5 + B, 0.5);
    },
  },
  well: {
    say: 'make a wish',
    draw(ctx, x, y, B) {
      crayon(ctx, () => wrect(ctx, x - 28, y - 32, 56, 32, 1 + B), '#c6c0cc', { hatch: '#97909f', bounds: [x - 28, y - 32, 56, 32], gap: 6, lw: 2 });
      ctx.strokeStyle = INK; ctx.lineWidth = 2.6; wline(ctx, x - 24, y - 32, x - 24, y - 70, 2 + B, 0.6); wline(ctx, x + 24, y - 32, x + 24, y - 70, 3 + B, 0.6);
      crayon(ctx, () => wpoly(ctx, [[x - 38, y - 66], [x, y - 92], [x + 38, y - 66]], 4 + B), '#e9a3a0', { lw: 2 });
      ctx.lineWidth = 1.4; wline(ctx, x, y - 66, x, y - 40, 5 + B, 0.3);
      crayon(ctx, () => wrect(ctx, x - 5, y - 44, 10, 10, 6 + B, 0.4), '#d7a982', { lw: 1.5 });
    },
  },
  mailbox: {
    say: 'any letters?',
    draw(ctx, x, y, B) {
      ctx.strokeStyle = INK; ctx.lineWidth = 3; wline(ctx, x, y, x, y - 40, 1 + B, 0.6);
      crayon(ctx, () => wrect(ctx, x - 18, y - 60, 36, 22, 2 + B, 0.8), PAL.rose, { lw: 2 });
      ctx.lineWidth = 2; wline(ctx, x + 18, y - 52, x + 18, y - 70, 3 + B, 0.3);
      crayon(ctx, () => wrect(ctx, x + 18, y - 72, 12, 7, 4 + B, 0.4), '#e46f7a', { lw: 1.5 });
    },
  },
};
const ROUTE = ['home', 'mailbox', 'pond', 'shop', 'tree', 'well', 'busstop'];

sketch('meadow', {
  init(S) { S.scroll = 0; S.pause = 0; S.visited = -1; S.say = null; S.clock = 0.3; },
  frame(S, t, dt) {
    const { ctx } = S, B = boil(t), GX = 240, GY = 392, GROUND = 352, SP = 380, X0 = 420;
    S.clock = (S.clock + dt / 60) % 1;
    const sky = skyAt(S.clock), n = sky.night;
    if (S.pause > 0) S.pause -= dt; else S.scroll += 46 * dt;
    const i = Math.floor((S.scroll + GX - X0 + SP / 2) / SP);
    const lmx = X0 + i * SP;
    if (i >= 0 && i !== S.visited && S.scroll + GX >= lmx) {
      S.visited = i; S.pause = 2.4;
      S.say = { s: LANDMARKS[ROUTE[i % ROUTE.length]].say, t };
    }
    const tint = c => mix(c, '#3b3560', n * 0.55);

    const g = ctx.createLinearGradient(0, 0, 0, 300);
    g.addColorStop(0, sky.top); g.addColorStop(1, sky.low);
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
    for (let k = 0; k < 40 && n > 0.05; k++) {
      ctx.fillStyle = `rgba(255,245,210,${n * (0.4 + 0.6 * Math.abs(Math.sin(t * 1.5 + k)))})`;
      ctx.beginPath(); ctx.arc(hash2(k, 1) * W, hash2(k, 2) * 200, 1.3, 0, TAU); ctx.fill();
    }
    // sun by day, moon by night
    const sunU = (S.clock - 0.2) / 0.6;
    if (sunU > 0 && sunU < 1) {
      const a = Math.PI * sunU, sx = W / 2 - Math.cos(a) * 320, sy = 230 - Math.sin(a) * 190;
      crayon(ctx, () => wellipse(ctx, sx, sy, 26, 26, 3 + B, 0.06), BUTTER, { hatch: '#f5b971', bounds: [sx - 30, sy - 30, 60, 60] });
    } else {
      const mu = ((S.clock + 0.2) % 1) / 0.4, a = Math.PI * mu, mx = W / 2 - Math.cos(a) * 320, my = 230 - Math.sin(a) * 190;
      crayon(ctx, () => wellipse(ctx, mx, my, 21, 21, 4 + B, 0.04), '#fff3c4', { stroke: '#cfc7e0' });
    }
    for (let k = 0; k < 3; k++) {
      const cx = ((k * 280 + t * 7 - S.scroll * 0.08) % (W + 200) + W + 200) % (W + 200) - 100;
      crayon(ctx, () => cloudPath(ctx, cx, 54 + k * 26, 0.9, k * 7 + B), tint(WHITE), { lw: 1.5, stroke: '#b9adc6' });
    }
    // hills
    const ridge = (base, a1, f1, a2, f2, par, fill, hatchC, seed) => {
      const pts = [[-20, H]];
      for (let sx = -20; sx <= W + 20; sx += 16) {
        const wx = sx + S.scroll * par;
        pts.push([sx, base + a1 * Math.sin(wx * f1) + a2 * Math.sin(wx * f2 + 1)]);
      }
      pts.push([W + 20, H]);
      crayon(ctx, () => wpoly(ctx, pts, seed + B, 0.8), tint(fill), { hatch: tint(hatchC), bounds: [0, base - 40, W, H - base + 40], gap: 8, seed, lw: 2 });
    };
    ridge(232, 24, 0.006, 12, 0.017, 0.22, PAL.lavender, '#a993d6', 10);
    ridge(284, 18, 0.009, 10, 0.023, 0.5, '#b5e5cd', '#7fc2a2', 20);
    // far trees sitting on the mid ridge
    for (let k = Math.floor(S.scroll * 0.5 / 90) - 1; k < Math.floor((S.scroll * 0.5 + W) / 90) + 2; k++) {
      if (hash2(k, 7) < 0.45) continue;
      const wx = k * 90 + hash2(k, 8) * 50, sx = wx - S.scroll * 0.5;
      const ty = 284 + 18 * Math.sin(wx * 0.009) + 10 * Math.sin(wx * 0.023 + 1);
      ctx.strokeStyle = INK; ctx.lineWidth = 1.8; wline(ctx, sx, ty + 4, sx, ty - 18, k + B, 0.4);
      crayon(ctx, () => wellipse(ctx, sx, ty - 28, 14, 16, k * 3 + B, 0.08), tint('#8fcb9c'), { lw: 1.6 });
    }
    // near ground and path
    crayon(ctx, () => wpoly(ctx, [[-20, GROUND], [W + 20, GROUND - 3], [W + 20, H + 20], [-20, H + 20]], 30 + B, 1.2), tint('#bfe3b4'),
      { hatch: tint(STEM), bounds: [0, GROUND - 6, W, H - GROUND + 6], gap: 7 });

    // landmarks behind the path
    for (let k = Math.max(0, i - 1); k <= i + 2; k++) {
      const sx = X0 + k * SP - S.scroll, name = ROUTE[k % ROUTE.length];
      if (sx > -220 && sx < W + 220) LANDMARKS[name].draw(ctx, sx, GROUND + 6, B + k * 10, t, n);
    }
    const sc = S.scroll;
    crayon(ctx, () => wpoly(ctx, [[-20, 376], [W + 20, 374], [W + 20, 402], [-20, 404]], 31 + B, 1), tint(PAL.apricot),
      { hatch: tint('#ecb995'), bounds: [0, 372, W, 34], gap: 9, ha: 0.4, lw: 1.6 });
    // tufts and flowers scroll with the ground
    for (let k = Math.floor(sc / 34) - 1; k < Math.floor((sc + W) / 34) + 2; k++) {
      const sx = k * 34 + hash2(k, 11) * 20 - sc, fy = 410 + hash2(k, 12) * 26;
      ctx.strokeStyle = tint('#6fae80'); ctx.lineWidth = 1.8;
      ctx.beginPath(); ctx.moveTo(sx - 4, fy - 6); ctx.lineTo(sx, fy); ctx.lineTo(sx + 4, fy - 7); ctx.stroke();
      if (hash2(k, 13) > 0.7) {
        ctx.fillStyle = tint([PAL.rose, BUTTER, PAL.lavender, WHITE][k & 3]);
        ctx.beginPath(); ctx.arc(sx + 8, fy - 8, 3.4, 0, TAU); ctx.fill();
        ctx.strokeStyle = INK; ctx.lineWidth = 1; ctx.stroke();
      }
    }
    drawGuy(ctx, GX, GY, 0.9, { t, walk: S.pause <= 0 });
    if (S.say && t - S.say.t < 2.4) bubble(ctx, GX + 8, GY - 70, S.say.s, { alpha: clamp((S.say.t + 2.4 - t) / 0.3, 0, 1) });

    // dusk and night wash, with window light and fireflies on top
    if (n > 0.02) {
      ctx.save();
      ctx.globalCompositeOperation = 'multiply';
      const wash = ctx.createLinearGradient(0, 170, 0, 290);
      wash.addColorStop(0, '#ffffff'); wash.addColorStop(1, mix('#ffffff', '#6e65a8', n * 0.75));
      ctx.fillStyle = wash;
      ctx.fillRect(0, 170, W, H - 170);
      ctx.globalCompositeOperation = 'lighter';
      for (let k = Math.max(0, i - 1); k <= i + 2; k++) {
        const def = LANDMARKS[ROUTE[k % ROUTE.length]], sx = X0 + k * SP - S.scroll;
        for (const [dx, dy, r] of def.lights || []) {
          const gr = ctx.createRadialGradient(sx + dx, GROUND + 6 + dy, 2, sx + dx, GROUND + 6 + dy, r);
          gr.addColorStop(0, `rgba(255,205,110,${0.5 * n})`); gr.addColorStop(1, 'rgba(255,205,110,0)');
          ctx.fillStyle = gr; ctx.fillRect(sx + dx - r, GROUND + 6 + dy - r, r * 2, r * 2);
        }
      }
      for (let k = 0; k < 18; k++) {
        const fx = (hash2(k, 21) * W + Math.sin(t * 0.7 + k) * 40 - sc * 0.3 % W + W) % W;
        const fy = 300 + hash2(k, 22) * 110 + Math.cos(t * 0.9 + k * 2) * 18;
        const on = Math.max(0, Math.sin(t * 1.8 + k * 1.7));
        const gr = ctx.createRadialGradient(fx, fy, 0, fx, fy, 9);
        gr.addColorStop(0, `rgba(240,255,140,${0.9 * on * n})`); gr.addColorStop(1, 'rgba(240,255,140,0)');
        ctx.fillStyle = gr; ctx.fillRect(fx - 9, fy - 9, 18, 18);
      }
      ctx.restore();
    }
    grain(ctx, 0.9);
  },
});

// ================================================================================================
// Pocket island (pixel)
// ================================================================================================

const LW = 180, LH = 110;
const PX = {
  deep: '#3b6ea8', shallow: '#5fa8d3', foam: '#e8f4f8', sand: '#f2d49b', sandDark: '#e6c284',
  grass: '#7fc17a', grassDark: '#6cb06b', path: '#d9b77e', pathEdge: '#c9a46a',
  trunk: '#7a5040', pine: '#2f7a4a', pineLight: '#47a061', leaf: '#3f8f55', leafLight: '#6dbb6a',
  wall: '#fff1d6', roof: '#e86a5c', roofDark: '#c9534a', ink: '#4a3a4f',
};
const LOCS = [
  { name: 'Home', x: 92, y: 52 },
  { name: 'Frog pond', x: 62, y: 62 },
  { name: 'Pine woods', x: 122, y: 40 },
  { name: 'Corner shop', x: 114, y: 74 },
  { name: 'The dock', x: 148, y: 60 },
  { name: 'Lookout rock', x: 52, y: 36 },
  { name: 'Beach', x: 84, y: 88 },
].map((l, i) => ({ ...l, i }));

/** Spokes bend a little; the same curve from either end, so paths and walking agree. */
function spoke(a, b, u) {
  const len = Math.hypot(b.x - a.x, b.y - a.y) || 1, sgn = a.i < b.i ? 1 : -1;
  const w = (hash2(Math.min(a.i, b.i), Math.max(a.i, b.i), 3) * 2 - 1) * 7 * sgn;
  const nx = -(b.y - a.y) / len, ny = (b.x - a.x) / len, off = Math.sin(u * Math.PI) * w;
  return [lerp(a.x, b.x, u) + nx * off, lerp(a.y, b.y, u) + ny * off];
}
function pxEllipse(g, cx, cy, rx, ry, c) {
  g.fillStyle = c;
  for (let yy = Math.floor(cy - ry); yy <= Math.ceil(cy + ry); yy++) {
    const dy = (yy + 0.5 - cy) / ry;
    if (Math.abs(dy) > 1) continue;
    const hw = rx * Math.sqrt(1 - dy * dy), x0 = Math.round(cx - hw), x1 = Math.round(cx + hw);
    g.fillRect(x0, yy, x1 - x0, 1);
  }
}
function pxPine(g, x, y) {
  g.fillStyle = PX.trunk; g.fillRect(x, y - 2, 1, 2);
  for (let r = 0; r < 7; r++) {
    const hw = [0, 1, 1, 2, 2, 3, 3][r], ry = y - 9 + r;
    g.fillStyle = PX.pine; g.fillRect(x - hw, ry, hw * 2 + 1, 1);
    g.fillStyle = PX.pineLight; g.fillRect(x - hw, ry, Math.max(1, hw), 1);
  }
}
function pxRound(g, x, y) {
  g.fillStyle = PX.trunk; g.fillRect(x, y - 3, 1, 3);
  pxEllipse(g, x + 0.5, y - 6.5, 4, 4, PX.leaf);
  pxEllipse(g, x - 0.5, y - 7.5, 2, 2, PX.leafLight);
}
function pxGuy(g, x, y, t, walk, dir) {
  const bob = walk ? Math.floor(t * 8) % 2 : 0, by = y - 1 - bob;
  pxEllipse(g, x + 0.5, by - 4.5, 4.5, 5, PX.ink);
  pxEllipse(g, x + 0.5, by - 4.5, 3.5, 4, BUTTER);
  g.fillStyle = BUTTER_SHADE; g.fillRect(x + 3, by - 6, 1, 3);
  g.fillStyle = PX.ink;
  g.fillRect(x - 3, y - 1, 2, 1); g.fillRect(x + 2, y - 1, 2, 1);
  g.fillRect(x - 2 + dir, by - 6, 1, 2); g.fillRect(x + 1 + dir, by - 6, 1, 2);
  g.fillStyle = BLUSH; g.fillRect(x - 3 + dir, by - 4, 1, 1); g.fillRect(x + 2 + dir, by - 4, 1, 1);
  g.fillStyle = '#5ea35f'; g.fillRect(x, by - 11, 1, 2);
  g.fillStyle = LEAF; g.fillRect(x - 2, by - 11, 2, 1); g.fillRect(x + 1, by - 12, 2, 1);
}

sketch('island', {
  init(S) {
    const base = document.createElement('canvas');
    base.width = LW; base.height = LH;
    const b = base.getContext('2d'), im = b.createImageData(LW, LH);
    const kind = new Uint8Array(LW * LH), put = (i, c) => { const [r, g, bl] = hexRgb(c); im.data[i * 4] = r; im.data[i * 4 + 1] = g; im.data[i * 4 + 2] = bl; im.data[i * 4 + 3] = 255; };
    for (let y = 0; y < LH; y++) for (let x = 0; x < LW; x++) {
      const dx = (x - 90) / 72, dy = (y - 56) / 42, ang = Math.atan2(dy, dx);
      const nz = 0.11 * Math.sin(ang * 3 + 1) + 0.07 * Math.sin(ang * 7 + 2) + 0.04 * Math.sin(ang * 13);
      const d = Math.hypot(dx, dy) / (1 + nz), i = y * LW + x;
      const speck = hash2(x, y, 5), patch = Math.sin(x * 0.13 + 1) + Math.sin(y * 0.19 + x * 0.05);
      if (d > 1.16) { kind[i] = 0; put(i, PX.deep); }
      else if (d > 1) { kind[i] = 1; put(i, PX.shallow); }
      else if (d > 0.85) { kind[i] = 2; put(i, speck < 0.12 ? PX.sandDark : PX.sand); }
      else { kind[i] = 3; put(i, patch > 0.7 || speck < 0.05 ? PX.grassDark : PX.grass); }
      if (((x - 62) / 11) ** 2 + ((y - 66) / 6) ** 2 < 1) { kind[i] = 5; put(i, PX.shallow); }
    }
    b.putImageData(im, 0, 0);
    S.foam = [];
    for (let y = 1; y < LH - 1; y++) for (let x = 1; x < LW - 1; x++) {
      if (kind[y * LW + x] !== 1) continue;
      if ([kind[y * LW + x - 1], kind[y * LW + x + 1], kind[(y - 1) * LW + x], kind[(y + 1) * LW + x]].includes(2)) S.foam.push([x, y]);
    }
    // dirt paths, every spoke from home
    for (const l of LOCS.slice(1)) {
      const a = LOCS[0], steps = Math.hypot(l.x - a.x, l.y - a.y) * 2;
      for (const [c, sz] of [[PX.pathEdge, 3], [PX.path, 2]]) {
        b.fillStyle = c;
        for (let s = 0; s <= steps; s++) {
          const [px, py] = spoke(a, l, s / steps), rx = Math.round(px), ry = Math.round(py);
          b.fillRect(rx - (sz === 3 ? 1 : 0), ry - (sz === 3 ? 1 : 0), sz, sz);
          for (let q = 0; q < 4; q++) { const ii = (ry + (q >> 1)) * LW + rx + (q & 1); if (ii >= 0 && ii < kind.length && kind[ii] === 3) kind[ii] = 4; }
        }
      }
    }
    // dock planks
    const dock = LOCS[4];
    for (let x = dock.x - 2; x < dock.x + 24; x++) {
      b.fillStyle = x % 3 ? '#a0704a' : '#7f5637';
      b.fillRect(x, dock.y - 1, 1, 4);
    }
    b.fillStyle = '#5c3e2a'; for (const px of [dock.x + 6, dock.x + 14, dock.x + 22]) b.fillRect(px, dock.y + 3, 1, 2);
    S.base = base;
    S.low = document.createElement('canvas');
    S.low.width = LW; S.low.height = LH;

    // scenery, sorted by y with the guy each frame
    S.things = [];
    const near = (x, y, r) => LOCS.some(l => Math.hypot(l.x - x, l.y - y) < r);
    const r = rng(42);
    for (let i = 0; i < 26; i++) {
      const a = r() * TAU, d = Math.sqrt(r()) * 20, x = Math.round(130 + Math.cos(a) * d * 1.1), y = Math.round(33 + Math.sin(a) * d * 0.7);
      if (kind[y * LW + x] === 3 && !near(x, y, 5)) S.things.push({ y, draw: g => pxPine(g, x, y) });
    }
    for (let i = 0; i < 60 && S.things.length < 46; i++) {
      const x = Math.floor(r() * LW), y = Math.floor(r() * LH);
      if (kind[y * LW + x] === 3 && !near(x, y, 10) && kind[(y - 1) * LW + x] === 3) S.things.push({ y, draw: g => pxRound(g, x, y) });
    }
    const home = LOCS[0], shop = LOCS[3], rock = LOCS[5], beach = LOCS[6];
    S.things.push({ y: home.y - 1, draw(g, t) {
      const x = home.x, y = home.y - 2;
      g.fillStyle = '#8d8a96'; g.fillRect(x + 3, y - 15, 2, 4);
      g.fillStyle = PX.wall; g.fillRect(x - 6, y - 7, 12, 7);
      for (let i = 0; i < 6; i++) { g.fillStyle = i < 2 ? PX.roofDark : PX.roof; g.fillRect(x - 2 - i, y - 13 + i, 4 + i * 2, 1); }
      g.fillStyle = '#8a5a44'; g.fillRect(x - 1, y - 4, 2, 4);
      g.fillStyle = '#ffe08a'; g.fillRect(x + 2, y - 5, 2, 2); g.fillRect(x - 5, y - 5, 2, 2);
      g.fillStyle = 'rgba(255,255,255,0.75)';
      for (let i = 0; i < 3; i++) { const p = (t * 0.4 + i / 3) % 1; g.fillRect(x + 3 + Math.round(Math.sin(p * 6) * 2), y - 16 - Math.round(p * 10), 1 + Math.round(p), 1); }
    } });
    S.things.push({ y: shop.y - 1, draw(g) {
      const x = shop.x, y = shop.y - 2;
      g.fillStyle = '#fde2e4'; g.fillRect(x - 6, y - 8, 12, 8);
      for (let i = -7; i < 7; i++) { g.fillStyle = (i + 7) % 2 ? '#fff' : '#e46f7a'; g.fillRect(x + i, y - 9, 1, 3); }
      g.fillStyle = PX.roofDark; g.fillRect(x - 6, y - 11, 12, 2);
      g.fillStyle = '#8a5a44'; g.fillRect(x - 1, y - 4, 3, 4);
      g.fillStyle = '#9fd6e8'; g.fillRect(x - 5, y - 5, 3, 3); g.fillRect(x + 3, y - 5, 2, 3);
    } });
    S.things.push({ y: rock.y - 1, draw(g, t) {
      const x = rock.x, y = rock.y - 1;
      pxEllipse(g, x - 3, y - 3, 6, 4, '#7b7b8a'); pxEllipse(g, x + 3, y - 3, 5, 4, '#9a9aa8'); pxEllipse(g, x, y - 6, 4, 3, '#b2b2bf');
      g.fillStyle = PX.ink; g.fillRect(x, y - 15, 1, 8);
      g.fillStyle = '#e46f7a'; const w = Math.floor(t * 4) % 2; g.fillRect(x + 1, y - 15, 4, 2); g.fillRect(x + 5, y - 15 + w, 1, 2);
    } });
    S.things.push({ y: beach.y - 1, draw(g) {
      const x = beach.x + 5, y = beach.y;
      g.fillStyle = PX.ink; g.fillRect(x, y - 8, 1, 8);
      for (let r2 = 0; r2 < 3; r2++) for (let dx = -r2 - 2; dx <= r2 + 2; dx++) { g.fillStyle = (dx + 9) % 2 ? '#fff' : '#ff9fb4'; g.fillRect(x + dx, y - 11 + r2, 1, 1); }
      g.fillStyle = '#b8d7f4'; g.fillRect(x - 9, y - 2, 6, 3);
    } });
    S.guy = { at: 0, x: home.x, y: home.y, route: [], u: 0, wait: 2, dir: 1, walk: false };
    S.hoverLoc = null;
  },
  locAt(x, y) {
    const lx = x / 4, ly = y / 4;
    let best = null, bd = 13;
    for (const l of LOCS) { const d = Math.hypot(l.x - lx, l.y - ly); if (d < bd) { bd = d; best = l; } }
    return best;
  },
  go(S, target) {
    const g = S.guy;
    if (g.route.length) { g.next = target; return; }
    if (target.i === g.at) return;
    g.route = g.at === 0 || target.i === 0 ? [target.i] : [0, target.i];
    g.u = 0;
  },
  hover(S, x, y) { S.hoverLoc = this.locAt(x, y); return !!S.hoverLoc; },
  click(S, x, y) { const l = this.locAt(x, y); if (l) this.go(S, l); },
  frame(S, t, dt) {
    const { ctx } = S, g = S.low.getContext('2d'), G = S.guy;
    if (G.route.length) {
      const a = LOCS[G.at], b = LOCS[G.route[0]], len = Math.hypot(b.x - a.x, b.y - a.y) + 4;
      G.u = Math.min(1, G.u + dt * 17 / len);
      const [nx, ny] = spoke(a, b, G.u);
      if (Math.abs(nx - G.x) > 0.01) G.dir = nx > G.x ? 1 : -1;
      G.x = nx; G.y = ny; G.walk = true;
      if (G.u >= 1) {
        G.at = G.route.shift(); G.u = 0;
        if (!G.route.length) { G.walk = false; G.wait = 3 + Math.random() * 4; if (G.next) { const n = G.next; G.next = null; this.go(S, n); } }
      }
    } else if ((G.wait -= dt) <= 0) this.go(S, LOCS[Math.floor(Math.random() * LOCS.length)]), G.wait = 2;

    g.imageSmoothingEnabled = false;
    g.drawImage(S.base, 0, 0);
    // water
    const f = Math.floor(t * 3);
    g.fillStyle = 'rgba(255,255,255,0.45)';
    for (let i = 0; i < 90; i++) {
      const x = Math.floor(hash2(i, f, 2) * LW), y = Math.floor(hash2(i, f, 3) * LH);
      const d = Math.hypot((x - 90) / 72, (y - 56) / 42);
      if (d > 1.05) g.fillRect(x, y, 2, 1);
    }
    g.fillStyle = PX.foam;
    for (const [x, y] of S.foam) if (Math.sin(t * 1.6 + x * 0.35 + y * 0.5) > 0.35) g.fillRect(x, y, 1, 1);
    // pond lily pads and a boat
    g.fillStyle = '#3f8f55'; g.fillRect(58, 66, 2, 1); g.fillRect(66, 68, 2, 1); g.fillRect(63, 63, 2, 1);
    const bob = Math.round(Math.sin(t * 2));
    g.fillStyle = '#e46f7a'; g.fillRect(172, 61 + bob, 7, 2); g.fillStyle = '#fff'; g.fillRect(175, 55 + bob, 1, 6); g.fillRect(176, 56 + bob, 3, 3);

    const drawn = [...S.things, { y: G.y, draw: gg => pxGuy(gg, Math.round(G.x), Math.round(G.y), t, G.walk, G.dir) }];
    drawn.sort((a, b) => a.y - b.y);
    for (const th of drawn) th.draw(g, t);

    // clouds and their shadows
    for (let i = 0; i < 3; i++) {
      const cx = ((i * 74 + t * (2.2 + i * 0.6)) % (LW + 50)) - 25, cy = 14 + i * 26;
      g.globalAlpha = 0.14; pxEllipse(g, cx + 6, cy + 12, 12, 4, '#1c2a4a');
      g.globalAlpha = 0.9; pxEllipse(g, cx, cy, 9, 3, '#ffffff'); pxEllipse(g, cx + 5, cy - 2, 6, 3, '#ffffff');
      g.globalAlpha = 1;
    }
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(S.low, 0, 0, W, H);
    ctx.imageSmoothingEnabled = true;

    const target = G.route.length ? LOCS[G.route[G.route.length - 1]] : null;
    for (const l of LOCS) {
      const hot = S.hoverLoc === l && S.mouse.in, isT = target === l, here = !G.route.length && G.at === l.i;
      if (!hot && !isT && !here) continue;
      ctx.save();
      ctx.font = '12px Silkscreen, monospace';
      const w = ctx.measureText(l.name).width + 12, lx = clamp(l.x * 4 - w / 2, 4, W - w - 4), ly = l.y * 4 + 8;
      ctx.fillStyle = isT ? '#e46f7a' : INK; ctx.fillRect(lx, ly, w, 20);
      ctx.fillStyle = '#fff'; ctx.textBaseline = 'middle'; ctx.fillText(l.name, lx + 6, ly + 11);
      ctx.restore();
    }
    ctx.save(); ctx.font = '12px Silkscreen, monospace'; ctx.fillStyle = '#fff';
    ctx.fillText('click a place', 12, 22);
    ctx.restore();
  },
});

// ================================================================================================
// Paper puppet street
// ================================================================================================

function paperFill(ctx, pathFn, fill, depth = 1) {
  ctx.save();
  ctx.shadowColor = 'rgba(50,25,60,0.32)'; ctx.shadowBlur = 8 * depth; ctx.shadowOffsetY = 4 * depth;
  pathFn(); ctx.fillStyle = fill; ctx.fill();
  ctx.restore();
}
const scissorPoly = (ctx, pts, seed) => wpoly(ctx, pts, seed, 0.7);
const SHOPS = [
  { name: 'home', x: 158, w: 92, h: 92, c: '#b8d7f4', roof: '#e46f7a' },
  { name: 'bakery', x: 280, w: 104, h: 112, c: '#f8bfd0', roof: '#a86f92', awn: true },
  { name: 'records', x: 416, w: 92, h: 124, c: '#cdbaf0', roof: '#6f5a9e' },
  { name: 'plants', x: 552, w: 102, h: 88, c: '#b5e5cd', roof: '#4e9a83', awn: true },
];

sketch('papercut', {
  init(S) { S.off = 0; S.guy = { x: 158, target: 1, wait: 2, dir: 1, say: null }; },
  frame(S, t, dt) {
    const { ctx } = S, G = S.guy;
    const want = S.mouse.in ? (S.mouse.x - W / 2) / W : Math.sin(t * 0.25) * 0.3;
    S.off = lerp(S.off, want, 1 - Math.exp(-dt * 5));
    const shift = d => -S.off * d;
    const sky = ctx.createLinearGradient(0, 0, 0, 340);
    sky.addColorStop(0, '#7d6ba8'); sky.addColorStop(0.55, '#e59ab0'); sky.addColorStop(1, '#ffd2a6');
    ctx.fillStyle = sky; ctx.fillRect(0, 0, W, H);

    // sun on a string
    const sway = Math.sin(t * 0.9) * 8, sunX = 500 + shift(10) + sway, sunY = 110;
    ctx.strokeStyle = 'rgba(70,50,80,0.6)'; ctx.lineWidth = 1.2;
    ctx.beginPath(); ctx.moveTo(500 + shift(10), 0); ctx.lineTo(sunX, sunY - 34); ctx.stroke();
    paperFill(ctx, () => { ctx.beginPath(); ctx.arc(sunX, sunY, 34, 0, TAU); }, '#ffd27d', 1.2);
    for (const [x0, y0, s, sp] of [[140, 70, 1.1, 5], [360, 44, 0.8, 3]]) {
      const cx = x0 + shift(14) + Math.sin(t * 0.3 + x0) * 12;
      ctx.strokeStyle = 'rgba(70,50,80,0.45)'; ctx.beginPath(); ctx.moveTo(cx, 0); ctx.lineTo(cx, y0 - 14 * s); ctx.stroke();
      paperFill(ctx, () => cloudPath(ctx, cx, y0, s, sp), '#fff1e6', 0.9);
    }

    // skyline
    const skyPts = [[-40, 340]];
    let sx = -40, k = 0;
    while (sx < W + 40) {
      const bw = 34 + hash2(k, 1) * 42, bh = 200 - hash2(k, 2) * 80;
      skyPts.push([sx, bh], [sx + bw, bh]);
      sx += bw; k++;
    }
    skyPts.push([sx, 340]);
    ctx.save(); ctx.translate(shift(10), 0);
    paperFill(ctx, () => scissorPoly(ctx, skyPts, 3), '#a48cc4');
    ctx.fillStyle = 'rgba(255,225,150,0.75)';
    sx = -40; k = 0;
    while (sx < W + 40) {
      const bw = 34 + hash2(k, 1) * 42, bh = 200 - hash2(k, 2) * 80;
      for (let wy = bh + 14; wy < 280; wy += 20) for (let wx = sx + 8; wx < sx + bw - 10; wx += 14) {
        if (hash2(Math.floor(wx), Math.floor(wy), 8) > 0.6) ctx.fillRect(wx, wy, 6, 9);
      }
      sx += bw; k++;
    }
    ctx.restore();

    // hills with paper trees
    ctx.save(); ctx.translate(shift(22), 0);
    const hillPts = [[-60, 380]];
    for (let x = -60; x <= W + 60; x += 22) hillPts.push([x, 268 + 18 * Math.sin(x * 0.011 + 1) + 7 * Math.sin(x * 0.03)]);
    hillPts.push([W + 60, 380]);
    for (let i = 0; i < 12; i++) {
      const x = -40 + i * 68 + hash2(i, 4) * 30, y = 268 + 18 * Math.sin(x * 0.011 + 1) + 7 * Math.sin(x * 0.03);
      const h = 38 + hash2(i, 5) * 22;
      paperFill(ctx, () => scissorPoly(ctx, [[x - 14, y + 4], [x, y - h], [x + 14, y + 4]], i), i % 2 ? '#6fa58f' : '#7fb2a2', 0.7);
    }
    paperFill(ctx, () => scissorPoly(ctx, hillPts, 5), '#9cc7b8');
    ctx.restore();

    // street
    ctx.save(); ctx.translate(shift(36), 0);
    for (const b of SHOPS) {
      const x0 = b.x - b.w / 2, top = 366 - b.h;
      paperFill(ctx, () => scissorPoly(ctx, [[x0 - 8, top + 2], [b.x, top - 34], [x0 + b.w + 8, top + 2]], b.x + 1), b.roof);
      paperFill(ctx, () => scissorPoly(ctx, [[x0, top], [x0 + b.w, top], [x0 + b.w, 372], [x0, 372]], b.x), b.c, 1.2);
      // lit windows: cut and backed with yellow
      const lit = 0.75 + 0.2 * Math.sin(t * 1.3 + b.x);
      ctx.fillStyle = `rgba(255,226,140,${lit})`;
      ctx.fillRect(x0 + 12, top + 14, 24, 26); ctx.fillRect(x0 + b.w - 36, top + 14, 24, 26);
      ctx.strokeStyle = 'rgba(80,50,90,0.5)'; ctx.lineWidth = 1.5;
      ctx.strokeRect(x0 + 12, top + 14, 24, 26); ctx.strokeRect(x0 + b.w - 36, top + 14, 24, 26);
      paperFill(ctx, () => scissorPoly(ctx, [[b.x - 13, 372], [b.x - 13, 332], [b.x + 13, 332], [b.x + 13, 372]], b.x + 2), shade(b.roof, -0.1), 0.5);
      if (b.awn) {
        for (let i = 0; i < 6; i++) {
          const ax = x0 + 6 + i * (b.w - 12) / 6, aw = (b.w - 12) / 6;
          paperFill(ctx, () => scissorPoly(ctx, [[ax, top + 44], [ax + aw, top + 44], [ax + aw, top + 58], [ax, top + 58]], i), i % 2 ? '#fff6ea' : '#e46f7a', 0.5);
        }
      }
      paperFill(ctx, () => { roundRectPath(ctx, b.x - 34, top - 2, 68, 20, 4); }, '#fff6ea', 0.5);
      text(ctx, b.name, b.x, top + 8, { size: 16 });
    }
    paperFill(ctx, () => scissorPoly(ctx, [[-70, 372], [W + 70, 370], [W + 70, H + 30], [-70, H + 30]], 7), '#e8c9a8', 1.3);
    ctx.fillStyle = 'rgba(160,120,90,0.35)';
    for (let i = 0; i < 12; i++) ctx.fillRect(-30 + i * 68, 398, 32, 4);

    // the puppet slides along the street, on a stick
    if (G.wait > 0) { G.wait -= dt; }
    else {
      const b = SHOPS[G.target], dx = b.x - G.x;
      if (Math.abs(dx) < 1) { G.say = { s: b.name === 'home' ? 'back home' : `the ${b.name}!`, t }; G.wait = 2.6; G.target = (G.target + 1 + Math.floor(Math.random() * 3)) % SHOPS.length; }
      else { G.x += Math.sign(dx) * Math.min(Math.abs(dx), 62 * dt); G.dir = Math.sign(dx); }
    }
    const moving = G.wait <= 0, wig = moving ? Math.sin(t * 8) * 0.05 : Math.sin(t * 1.5) * 0.015, py = 382 - (moving ? Math.abs(Math.sin(t * 8)) * 6 : 0);
    ctx.strokeStyle = '#9b7450'; ctx.lineWidth = 5; ctx.lineCap = 'round';
    ctx.beginPath(); ctx.moveTo(G.x, py - 6); ctx.lineTo(G.x + wig * 40, H + 20); ctx.stroke();
    ctx.save(); ctx.translate(G.x, py); ctx.rotate(wig);
    drawGuy(ctx, 0, 0, 0.95, { t, style: 'paper', dir: G.dir, shadow: false });
    ctx.restore();
    if (G.say && t - G.say.t < 2.4) bubble(ctx, G.x + 6, py - 72, G.say.s, { alpha: clamp((G.say.t + 2.4 - t) / 0.3, 0, 1) });
    ctx.restore();

    // bushes in front
    ctx.save(); ctx.translate(shift(56), 0);
    const bush = [[-90, H + 30]];
    for (let x = -90; x <= W + 90; x += 26) bush.push([x, 418 + (Math.floor(x / 26) % 2 ? -14 : 8)]);
    bush.push([W + 90, H + 30]);
    paperFill(ctx, () => smoothClosed(ctx, bush), '#6fa58f', 1.4);
    ctx.restore();

    // shoebox frame and curtains don't move
    const curtain = side => {
      const edge = side < 0 ? 0 : W, pts = [[edge, -10]];
      for (let y = -10; y <= H + 10; y += 24) pts.push([edge - side * (58 + Math.sin(y * 0.024) * 18 + (Math.floor(y / 24) % 2 ? 5 : -5)), y]);
      pts.push([edge, H + 10]);
      paperFill(ctx, () => smoothClosed(ctx, pts), '#c64f60', 1.4);
    };
    curtain(-1); curtain(1);
    paperFill(ctx, () => {
      const pts = [];
      for (let i = 0; i <= 13; i++) { pts.push([i * W / 13, i % 2 ? 42 : 26]); }
      pts.push([W + 10, -10], [-10, -10]);
      smoothClosed(ctx, pts);
    }, '#a53f50', 1.4);
    grain(ctx, 1);
  },
});

// ================================================================================================
// Lantern night walk
// ================================================================================================

const lanternPathY = x => 352 + 20 * Math.sin(x * 0.011) + 8 * Math.sin(x * 0.031 + 1);
function p2poly(pts, closed = true) {
  const p = new Path2D();
  pts.forEach((q, i) => i ? p.lineTo(q[0], q[1]) : p.moveTo(q[0], q[1]));
  if (closed) p.closePath();
  return p;
}
function p2ellipse(cx, cy, rx, ry) { const p = new Path2D(); p.ellipse(cx, cy, rx, ry, 0, 0, TAU); return p; }

sketch('lantern', {
  init(S) {
    S.D = document.createElement('canvas');
    S.D.width = W * S.dpr; S.D.height = H * S.dpr;
    S.dc = S.D.getContext('2d');
    S.shapes = [];
    const add = (p, fill, line = true) => S.shapes.push({ p, fill, line });
    // willow
    add(p2poly([[106, 300], [134, 300], [126, 214], [114, 214]]), '#8a6242');
    add(p2ellipse(120, 196, 70, 36), '#5f9c62');
    const r = rng(9);
    S.willow = [];
    for (let i = 0; i < 18; i++) S.willow.push({ x: 56 + i * 7.5, len: 34 + r() * 56, ph: r() * TAU });
    // bench, signpost, stall
    add(p2poly([[284, 322], [344, 322], [344, 330], [284, 330]]), '#a8744f');
    add(p2poly([[284, 304], [344, 304], [344, 310], [284, 310]]), '#a8744f');
    add(p2poly([[290, 330], [295, 330], [295, 344], [290, 344]]), '#7f5637');
    add(p2poly([[333, 330], [338, 330], [338, 344], [333, 344]]), '#7f5637');
    add(p2poly([[424, 268], [430, 268], [430, 334], [424, 334]]), '#8a6242');
    add(p2poly([[398, 272], [456, 272], [470, 284], [456, 296], [398, 296]]), '#e8d3a4');
    add(p2poly([[516, 248], [530, 248], [530, 332], [516, 332]]), '#8a6242');
    add(p2poly([[624, 248], [638, 248], [638, 332], [624, 332]]), '#8a6242');
    add(p2poly([[516, 298], [638, 298], [638, 332], [516, 332]]), '#a8744f');
    add(p2poly([[498, 250], [656, 250], [642, 214], [512, 214]]), '#d9534f');
    add(p2poly([[516, 250], [578, 250], [578, 272], [516, 272]]), '#f3ead3');
    // stone lanterns
    S.lanterns = [40, 180, 262, 372, 490, 590, 690].map(x => ({ x, y: lanternPathY(x) - 18, lit: 0 }));
    for (const l of S.lanterns) {
      add(p2poly([[l.x - 7, l.y], [l.x + 7, l.y], [l.x + 5, l.y - 22], [l.x - 5, l.y - 22]]), '#a9a6a0');
      add(p2poly([[l.x - 13, l.y - 34], [l.x, l.y - 46], [l.x + 13, l.y - 34]]), '#8d8a84');
    }
    S.mush = [[210, 408], [226, 414], [640, 394], [654, 402], [470, 416]].map(([x, y]) => ({ x, y }));
    const rr = rng(4);
    S.flowers = [];
    for (let i = 0; i < 150; i++) {
      const x = rr() * W, y = 292 + rr() * 148;
      if (Math.abs(y - lanternPathY(x)) < 20) continue;
      S.flowers.push({ x, y, c: [PAL.rose, BUTTER, PAL.lavender, '#ff8f7a', WHITE][Math.floor(rr() * 5)], r: 1.8 + rr() * 2 });
    }
    S.reeds = [];
    for (let i = 0; i < 36; i++) S.reeds.push({ x: rr() * W, h: 16 + rr() * 22, ph: rr() * TAU });
    S.guy = { x: 150, dir: 1, walk: false, auto: 1 };
    S.mtn = [
      { base: 204, a: 36, f: 0.012, c: '#38345e', off: 2 },
      { base: 222, a: 24, f: 0.019, c: '#2b284c', off: 5 },
    ];
  },
  frame(S, t, dt) {
    const { ctx, dc } = S, G = S.guy;
    const goal = S.mouse.in ? S.mouse.x : null;
    if (goal != null) {
      const dx = goal - G.x;
      G.walk = Math.abs(dx) > 3;
      if (G.walk) { G.x += Math.sign(dx) * Math.min(Math.abs(dx), 90 * dt); G.dir = Math.sign(dx); }
    } else {
      G.walk = true;
      G.x += G.auto * 44 * dt; G.dir = G.auto;
      if (G.x > W - 40) G.auto = -1; if (G.x < 40) G.auto = 1;
    }
    G.x = clamp(G.x, 20, W - 20);
    for (const l of S.lanterns) {
      if (!l.on && Math.abs(l.x - G.x) < 42) l.on = true;
      if (l.on) l.lit = Math.min(1, l.lit + dt * 1.4);
    }
    const gy = lanternPathY(G.x) + 4;

    // sky, stars, moon, mountains stay visible
    const sky = ctx.createLinearGradient(0, 0, 0, 250);
    sky.addColorStop(0, '#14122a'); sky.addColorStop(1, '#2f2a58');
    ctx.fillStyle = sky; ctx.fillRect(0, 0, W, H);
    for (let i = 0; i < 80; i++) {
      ctx.fillStyle = `rgba(240,235,255,${0.25 + 0.6 * Math.abs(Math.sin(t * 0.8 + i * 1.7))})`;
      ctx.fillRect(hash2(i, 1) * W, hash2(i, 2) * 190, 1.4, 1.4);
    }
    const moon = ctx.createRadialGradient(610, 66, 18, 610, 66, 90);
    moon.addColorStop(0, 'rgba(250,240,200,0.35)'); moon.addColorStop(1, 'rgba(250,240,200,0)');
    ctx.fillStyle = moon; ctx.fillRect(500, 0, 220, 170);
    ctx.fillStyle = '#f7efd2'; ctx.beginPath(); ctx.arc(610, 66, 25, 0, TAU); ctx.fill();
    ctx.fillStyle = 'rgba(190,180,170,0.4)';
    for (const [dx, dy, r] of [[-8, -6, 5], [7, 5, 6], [-3, 11, 3]]) { ctx.beginPath(); ctx.arc(610 + dx, 66 + dy, r, 0, TAU); ctx.fill(); }
    for (const m of S.mtn) {
      // ink wash: stacked translucent ridges, heavier at the foot
      for (let w = 0; w < 5; w++) {
        ctx.beginPath(); ctx.moveTo(0, 250);
        for (let x = 0; x <= W; x += 8) ctx.lineTo(x, m.base + w * 7 - m.a * Math.abs(Math.sin(x * m.f + m.off)) - 12 * Math.sin(x * m.f * 3.3) * 0.5 - hash2(x, w) * 1.6);
        ctx.lineTo(W, 250); ctx.closePath();
        ctx.fillStyle = rgba(m.c, 0.42); ctx.fill();
      }
    }

    // full color underneath: this is what light reveals
    ctx.fillStyle = '#4f7f5a'; ctx.fillRect(0, 226, W, 16);
    const river = ctx.createLinearGradient(0, 240, 0, 288);
    river.addColorStop(0, '#2f5f96'); river.addColorStop(1, '#4b86bb');
    ctx.fillStyle = river; ctx.fillRect(0, 240, W, 48);
    ctx.strokeStyle = 'rgba(200,225,255,0.5)'; ctx.lineWidth = 1.4;
    for (let i = 0; i < 26; i++) {
      const y = 244 + hash2(i, 7) * 40, x = (hash2(i, 8) * W + t * (8 + i % 7)) % W, l = 12 + hash2(i, 9) * 26;
      ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + l, y); ctx.stroke();
    }
    const ground = ctx.createLinearGradient(0, 286, 0, H);
    ground.addColorStop(0, '#6fa660'); ground.addColorStop(1, '#4d8450');
    ctx.fillStyle = ground; ctx.fillRect(0, 286, W, H - 286);
    const top = [], bot = [];
    for (let x = -10; x <= W + 10; x += 12) { top.push([x, lanternPathY(x) - 15]); bot.unshift([x, lanternPathY(x) + 17]); }
    const road = p2poly([...top, ...bot]);
    ctx.fillStyle = '#cfae80'; ctx.fill(road);
    for (const f of S.flowers) { ctx.fillStyle = f.c; ctx.beginPath(); ctx.arc(f.x, f.y, f.r, 0, TAU); ctx.fill(); }
    for (const rd of S.reeds) {
      ctx.strokeStyle = '#5b8f4e'; ctx.lineWidth = 1.6;
      ctx.beginPath(); ctx.moveTo(rd.x, 294); ctx.lineTo(rd.x + Math.sin(t + rd.ph) * 3, 294 - rd.h); ctx.stroke();
    }
    for (const s of S.shapes) { ctx.fillStyle = s.fill; ctx.fill(s.p); }
    const strand = (w, color) => {
      ctx.strokeStyle = color; ctx.lineWidth = w;
      for (const f of S.willow) {
        ctx.beginPath(); ctx.moveTo(f.x, 196 + 24 * (1 - Math.abs(f.x - 120) / 80));
        ctx.quadraticCurveTo(f.x + Math.sin(t * 0.9 + f.ph) * 4, 240, f.x + Math.sin(t * 0.9 + f.ph) * 8, 216 + f.len);
        ctx.stroke();
      }
    };
    strand(1.6, '#5f9c62');
    for (const l of S.lanterns) {
      ctx.fillStyle = l.lit > 0.05 ? mix('#3a372f', '#ffcf6b', l.lit) : '#3a372f';
      ctx.fillRect(l.x - 5, l.y - 32, 10, 10);
    }
    text(ctx, 'tea →', 427, 284, { size: 17, color: '#4a3a4f' });
    text(ctx, 'TEA', 547, 262, { size: 18, color: '#b23a36' });
    for (const m of S.mush) {
      ctx.fillStyle = '#e8e2d2'; ctx.fillRect(m.x - 1.5, m.y - 6, 3, 6);
      ctx.fillStyle = '#7fe0e8'; ctx.beginPath(); ctx.ellipse(m.x, m.y - 7, 6, 4, 0, Math.PI, 0); ctx.fill();
    }
    const hand = drawGuy(ctx, G.x, gy, 0.8, { t, walk: G.walk, dir: G.dir, lantern: true }).hand;

    // darkness with holes punched in it
    const lights = [];
    lights.push([hand[0], hand[1], 118 + Math.sin(t * 13) * 3 + Math.sin(t * 7.3) * 4, '255,190,100', 0.26]);
    for (const l of S.lanterns) if (l.lit > 0) lights.push([l.x, l.y - 27, Math.max(1, 78 * ease(l.lit) + Math.sin(t * 9 + l.x) * 2), '255,190,100', 0.2 * l.lit]);
    for (const m of S.mush) lights.push([m.x, m.y - 6, 26 + Math.sin(t * 2 + m.x) * 3, '120,230,240', 0.22]);
    const flies = [];
    for (let i = 0; i < 10; i++) {
      const on = Math.max(0, Math.sin(t * 1.4 + i * 2));
      const fx = hash2(i, 31) * W + Math.sin(t * 0.6 + i) * 30, fy = 310 + hash2(i, 32) * 110 + Math.cos(t * 0.8 + i) * 14;
      flies.push([fx, fy, on]);
      if (on > 0.2) lights.push([fx, fy, Math.max(1, 16 * on), '230,255,140', 0.25 * on]);
    }
    dc.setTransform(S.dpr, 0, 0, S.dpr, 0, 0);
    dc.globalCompositeOperation = 'source-over';
    dc.clearRect(0, 0, W, H);
    dc.fillStyle = 'rgba(10,9,24,0.9)';
    dc.fillRect(0, 0, W, H);
    dc.globalCompositeOperation = 'destination-out';
    const clear = dc.createLinearGradient(0, 214, 0, 262);
    clear.addColorStop(0, 'rgba(0,0,0,1)'); clear.addColorStop(1, 'rgba(0,0,0,0)');
    dc.fillStyle = clear; dc.fillRect(0, 0, W, 262);
    for (const [x, y, r] of lights) {
      const g = dc.createRadialGradient(x, y, r * 0.15, x, y, r);
      g.addColorStop(0, 'rgba(0,0,0,1)'); g.addColorStop(0.55, 'rgba(0,0,0,0.75)'); g.addColorStop(1, 'rgba(0,0,0,0)');
      dc.fillStyle = g; dc.fillRect(x - r, y - r, r * 2, r * 2);
    }
    // the moon's streak on the water
    for (let i = 0; i < 8; i++) {
      const y = 246 + i * 5.5, w = 14 + i * 3 + Math.sin(t * 2 + i) * 4;
      dc.fillStyle = 'rgba(0,0,0,0.55)'; dc.fillRect(610 - w, y, w * 2, 2.5);
    }
    ctx.drawImage(S.D, 0, 0, W, H);

    // warm glow
    ctx.save(); ctx.globalCompositeOperation = 'lighter';
    for (const [x, y, r, c, a] of lights) {
      const g = ctx.createRadialGradient(x, y, 0, x, y, r);
      g.addColorStop(0, `rgba(${c},${a})`); g.addColorStop(1, `rgba(${c},0)`);
      ctx.fillStyle = g; ctx.fillRect(x - r, y - r, r * 2, r * 2);
    }
    for (const [fx, fy, on] of flies) { ctx.fillStyle = `rgba(240,255,160,${on})`; ctx.fillRect(fx - 1, fy - 1, 2, 2); }
    ctx.restore();

    // pale ink line-work, so the dark still reads as a drawing
    ctx.save();
    ctx.strokeStyle = 'rgba(170,165,215,0.3)'; ctx.lineWidth = 1.3; ctx.lineJoin = 'round';
    for (const s of S.shapes) if (s.line) ctx.stroke(s.p);
    ctx.stroke(road);
    strand(1, 'rgba(170,165,215,0.14)');
    ctx.restore();
    const litN = S.lanterns.filter(l => l.on).length;
    text(ctx, `${litN} of ${S.lanterns.length} lanterns lit`, 18, 26, { size: 20, align: 'left', color: '#e8ddc0' });
  },
});

// ================================================================================================
// Tiny planet
// ================================================================================================

const PLANET_ITEMS = [
  { a: 0.15, draw(ctx, B) {
    crayon(ctx, () => wrect(ctx, -30, -40, 60, 40, 1 + B), BUTTER, { hatch: BUTTER_SHADE, bounds: [-30, -40, 60, 40], lw: 2 });
    crayon(ctx, () => wpoly(ctx, [[-38, -38], [0, -70], [38, -38]], 2 + B), '#e9a3a0', { hatch: '#c97b7f', bounds: [-38, -70, 76, 34], lw: 2 });
    crayon(ctx, () => wrect(ctx, -8, -24, 16, 24, 3 + B, 0.7), '#e46f7a', { lw: 1.8 });
  } },
  { a: 1.0, draw(ctx, B) {
    crayon(ctx, () => wpoly(ctx, [[-7, 2], [7, 2], [5, -48], [-5, -48]], 1 + B, 0.8), '#b98560', { lw: 2 });
    crayon(ctx, () => wellipse(ctx, 0, -72, 38, 34, 2 + B, 0.08), '#9fd3a7', { hatch: '#6fae80', bounds: [-40, -108, 80, 72] });
  } },
  { a: 1.8, draw(ctx, B) {
    crayon(ctx, () => wellipse(ctx, 0, -2, 40, 9, 1 + B, 0.04), PAL.sky, { hatch: '#7fa9d6', bounds: [-42, -12, 84, 20], gap: 5, lw: 2 });
    crayon(ctx, () => wellipse(ctx, -14, -3, 6, 2.4, 2 + B), LEAF, { lw: 1.2 });
    crayon(ctx, () => wellipse(ctx, 16, -1, 6, 2.4, 3 + B), LEAF, { lw: 1.2 });
  } },
  { a: 2.7, lamp: true, draw(ctx, B, t, dark) {
    if (dark > 0) {
      const g = ctx.createRadialGradient(0, -54, 2, 0, -54, 62);
      g.addColorStop(0, `rgba(255,214,120,${0.55 * dark})`); g.addColorStop(1, 'rgba(255,214,120,0)');
      ctx.fillStyle = g; ctx.fillRect(-62, -116, 124, 124);
    }
    ctx.strokeStyle = INK; ctx.lineWidth = 3; wline(ctx, 0, 2, 0, -46, 1 + B, 0.5);
    crayon(ctx, () => wrect(ctx, -8, -60, 16, 15, 2 + B, 0.6), dark > 0 ? '#ffe28a' : '#fbf4ea', { lw: 2 });
    crayon(ctx, () => wpoly(ctx, [[-11, -60], [0, -68], [11, -60]], 3 + B, 0.4), '#7c6c8a', { lw: 1.8 });
  } },
  { a: 3.6, draw(ctx, B) {
    for (let i = 0; i < 6; i++) {
      const x = -24 + i * 10, h = 10 + (i % 3) * 6;
      ctx.strokeStyle = '#5f8f6a'; ctx.lineWidth = 2; wline(ctx, x, 2, x, -h, i + B, 0.4);
      crayon(ctx, () => wellipse(ctx, x, -h - 3, 5, 5, i * 3 + B), [PAL.rose, BUTTER, PAL.lavender][i % 3], { lw: 1.4 });
    }
  } },
  { a: 4.5, draw(ctx, B, t) {
    const b = Math.sin(t * 2) * 0.8;
    ctx.save(); ctx.translate(0, b);
    crayon(ctx, () => wpoly(ctx, [[-12, 0], [12, 0], [12, -46], [0, -68], [-12, -46]], 1 + B, 0.7), WHITE, { lw: 2 });
    crayon(ctx, () => wellipse(ctx, 0, -38, 6, 6, 2 + B, 0.04), PAL.sky, { lw: 1.8 });
    crayon(ctx, () => wpoly(ctx, [[-12, -14], [-22, 2], [-12, 0]], 3 + B, 0.5), '#e46f7a', { lw: 1.8 });
    crayon(ctx, () => wpoly(ctx, [[12, -14], [22, 2], [12, 0]], 4 + B, 0.5), '#e46f7a', { lw: 1.8 });
    ctx.restore();
  } },
  { a: 5.4, draw(ctx, B) {
    ctx.strokeStyle = INK; ctx.lineWidth = 3; wline(ctx, 0, 2, 0, -24, 1 + B, 0.5);
    crayon(ctx, () => wrect(ctx, -14, -40, 28, 18, 2 + B, 0.7), PAL.rose, { lw: 2 });
    ctx.lineWidth = 1.8; wline(ctx, 14, -32, 14, -48, 3 + B, 0.3);
    crayon(ctx, () => wrect(ctx, 14, -50, 10, 6, 4 + B, 0.3), '#e46f7a', { lw: 1.4 });
  } },
];
const MOONS = [
  { name: 'library moon', r: 26, orbit: 215, sp: 0.09, ph: 0.8, c: PAL.apricot, h: '#e8b090' },
  { name: 'garden moon', r: 20, orbit: 262, sp: -0.06, ph: 2.5, c: '#b5e5cd', h: '#7fc2a2' },
  { name: 'moon for sale', r: 15, orbit: 305, sp: 0.045, ph: 5.1, c: PAL.lavender, h: '#a993d6' },
];

sketch('planet', {
  init(S) { S.ang = 0; S.walking = true; S.v = 0.3; },
  hover() { return true; },
  click(S) { S.walking = !S.walking; },
  frame(S, t, dt) {
    const { ctx } = S, B = boil(t), CX = 360, CY = 240, R = 108;
    S.v = lerp(S.v, S.walking ? 0.3 : 0, 1 - Math.exp(-dt * 5));
    S.ang -= S.v * dt;
    ctx.fillStyle = '#1f1b3a'; ctx.fillRect(0, 0, W, H);
    for (let i = 0; i < 110; i++) {
      const x = hash2(i, 1) * W, y = hash2(i, 2) * H, c = i % 7 === 0 ? PAL.rose : '#fff6d8';
      ctx.fillStyle = rgba(c, 0.3 + 0.7 * Math.abs(Math.sin(t * 0.9 + i * 1.3)));
      ctx.beginPath(); ctx.arc(x, y, 0.8 + hash2(i, 3) * 1.1, 0, TAU); ctx.fill();
    }
    // sun off to the left
    const sunG = ctx.createRadialGradient(62, 96, 20, 62, 96, 120);
    sunG.addColorStop(0, 'rgba(255,220,140,0.5)'); sunG.addColorStop(1, 'rgba(255,220,140,0)');
    ctx.fillStyle = sunG; ctx.fillRect(0, 0, 200, 240);
    crayon(ctx, () => wellipse(ctx, 62, 96, 34, 34, 2 + B, 0.05), BUTTER, { hatch: '#f5b971', bounds: [28, 62, 68, 68], stroke: '#f5b971' });
    // orbits and moons behind the planet
    const moonPos = MOONS.map(m => {
      const a = m.ph + t * m.sp;
      return { m, x: CX + Math.cos(a) * m.orbit, y: CY + Math.sin(a) * m.orbit * 0.6 - 20, behind: Math.sin(a) < 0 };
    });
    ctx.save(); ctx.setLineDash([3, 8]); ctx.strokeStyle = 'rgba(205,186,240,0.35)'; ctx.lineWidth = 1.4;
    for (const m of MOONS) { ctx.beginPath(); ctx.ellipse(CX, CY - 20, m.orbit, m.orbit * 0.6, 0, 0, TAU); ctx.stroke(); }
    ctx.restore();
    const drawMoon = p => {
      crayon(ctx, () => wellipse(ctx, p.x, p.y, p.m.r, p.m.r, p.m.r + B, 0.05), p.m.c, { hatch: p.m.h, bounds: [p.x, p.y - p.m.r, p.m.r, p.m.r * 2], gap: 4, stroke: WHITE, lw: 1.8 });
      text(ctx, p.m.name, p.x, p.y + p.m.r + 13, { size: 16, color: '#d9cfee' });
    };
    moonPos.filter(p => p.behind).forEach(drawMoon);
    // atmosphere and globe
    const atm = ctx.createRadialGradient(CX, CY, R - 4, CX, CY, R + 34);
    atm.addColorStop(0, 'rgba(184,215,244,0.4)'); atm.addColorStop(1, 'rgba(184,215,244,0)');
    ctx.fillStyle = atm; ctx.beginPath(); ctx.arc(CX, CY, R + 34, 0, TAU); ctx.fill();
    const items = [];
    for (const it of PLANET_ITEMS) {
      const a = it.a + S.ang;
      items.push({ it, a });
    }
    const drawItem = ({ it, a }) => {
      const dark = clamp(Math.sin(a) * 1.8, 0, 1);
      ctx.save();
      ctx.translate(CX, CY); ctx.rotate(a); ctx.translate(0, -R + 1);
      it.draw(ctx, B + Math.round(it.a * 10) * 4, t, dark);
      ctx.restore();
    };
    items.forEach(drawItem);
    crayon(ctx, () => wellipse(ctx, CX, CY, R, R, 6 + B, 0.012, 42), '#bfe3b4', { hatch: STEM, bounds: [CX - R, CY - R, R * 2, R * 2], gap: 8, seed: 3, lw: 2.6, stroke: WHITE });
    // craters and ponds turning with the ground
    for (let i = 0; i < 9; i++) {
      const a = i * 0.71 + S.ang, rad = R * (0.25 + hash2(i, 5) * 0.65);
      const x = CX + Math.sin(a) * rad, y = CY - Math.cos(a) * rad;
      crayon(ctx, () => wellipse(ctx, x, y, 5 + hash2(i, 6) * 10, 4 + hash2(i, 7) * 7, i + 1 + B, 0.06), i % 3 ? '#a5d6a0' : '#a9cdee', { lw: 1.4, stroke: 'rgba(74,58,79,0.5)' });
    }
    // night side: the terminator stays put while the world turns under it
    ctx.save();
    ctx.beginPath(); ctx.arc(CX, CY, R + 1, 0, TAU); ctx.clip();
    const nightG = ctx.createRadialGradient(CX - R * 0.7, CY - R * 0.25, R * 0.85, CX - R * 0.7, CY - R * 0.25, R * 1.9);
    nightG.addColorStop(0, 'rgba(25,20,70,0)'); nightG.addColorStop(0.3, 'rgba(25,20,70,0.5)'); nightG.addColorStop(1, 'rgba(25,20,70,0.78)');
    ctx.fillStyle = nightG; ctx.fillRect(CX - R, CY - R, R * 2 + 4, R * 2 + 4);
    ctx.restore();
    drawGuy(ctx, CX, CY - R + 2, 0.72, { t, walk: S.v > 0.1 });
    moonPos.filter(p => !p.behind).forEach(drawMoon);
    if (!S.walking) bubble(ctx, CX + 8, CY - R - 54, 'nice up here', {});
    text(ctx, 'once around ≈ 20 s', 22, H - 22, { size: 18, align: 'left', color: '#b8accc' });
    grain(ctx, 0.5);
  },
});
