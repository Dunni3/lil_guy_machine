'use strict';
// Rooms he can buy: the crayon dollhouse, pocket dioramas, and the riso catalog with a floor plan.

// ================================================================================================
// Crayon dollhouse
// ================================================================================================

const WOOD = '#d7a982', WOOD_DARK = '#b98560';

const dhRooms = {
  bedroom(ctx, r, B) {
    const { x, y, w } = r, fy = r.y + r.h;
    ctx.strokeStyle = 'rgba(255,255,255,0.5)'; ctx.lineWidth = 5;
    for (let sx = x + 10; sx < x + w; sx += 22) wline(ctx, sx, y, sx, fy, sx + B, 1);
    crayon(ctx, () => wrect(ctx, x + 118, y + 14, 50, 36, 1 + B, 1), '#e3f2ff', { lw: 2 });
    ctx.strokeStyle = INK; ctx.lineWidth = 1.6;
    wline(ctx, x + 143, y + 14, x + 143, y + 50, 2 + B, 0.8); wline(ctx, x + 118, y + 32, x + 168, y + 32, 3 + B, 0.8);
    crayon(ctx, () => wrect(ctx, x + 40, y + 12, 28, 34, 4 + B, 1), PAL.butter, { lw: 1.6 });
    crayon(ctx, () => wellipse(ctx, x + 54, y + 29, 7, 7, 5 + B), '#ff9fb4', { lw: 1.4 });
    crayon(ctx, () => wellipse(ctx, x + 125, fy - 4, 46, 6, 6 + B), PAL.lavender, { lw: 1.6, hatch: '#a993d6', bounds: [x + 75, fy - 12, 100, 16], gap: 5 });
    crayon(ctx, () => wrect(ctx, x + 8, fy - 46, 10, 44, 7 + B, 1), WOOD_DARK, { lw: 2 });
    crayon(ctx, () => wrect(ctx, x + 8, fy - 28, 94, 22, 8 + B, 1), WOOD, { lw: 2 });
    crayon(ctx, () => wellipse(ctx, x + 30, fy - 33, 12, 6, 9 + B), WHITE, { lw: 1.8 });
    crayon(ctx, () => wrect(ctx, x + 40, fy - 38, 62, 14, 10 + B, 1.2), PAL.sky, { lw: 2, hatch: '#8bb8e3', bounds: [x + 40, fy - 40, 64, 20], gap: 5 });
    crayon(ctx, () => wrect(ctx, x + 172, fy - 26, 20, 22, 11 + B, 1), WOOD, { lw: 1.8 });
    crayon(ctx, () => wpoly(ctx, [[x + 174, fy - 44], [x + 190, fy - 44], [x + 194, fy - 34], [x + 170, fy - 34]], 12 + B, 0.8), PAL.butter, { lw: 1.6 });
  },
  kitchen(ctx, r, B, t) {
    const { x, y, w } = r, fy = r.y + r.h;
    ctx.strokeStyle = 'rgba(255,255,255,0.55)'; ctx.lineWidth = 1.3;
    for (let ty = y + 40; ty < fy - 30; ty += 12) wline(ctx, x + 50, ty, x + w - 8, ty, ty + B, 0.6);
    crayon(ctx, () => wrect(ctx, x + 10, y + 20, 34, r.h - 22, 1 + B, 1), WHITE, { lw: 2 });
    ctx.strokeStyle = INK; ctx.lineWidth = 1.5; wline(ctx, x + 10, y + 46, x + 44, y + 46, 2 + B, 0.7);
    crayon(ctx, () => wrect(ctx, x + 52, fy - 34, 98, 32, 3 + B, 1), PAL.apricot, { lw: 2, hatch: '#f0b58f', bounds: [x + 52, fy - 34, 98, 32], gap: 6 });
    crayon(ctx, () => wrect(ctx, x + 50, fy - 38, 102, 6, 4 + B, 0.8), '#e9d6c0', { lw: 1.8 });
    crayon(ctx, () => wrect(ctx, x + 96, fy - 54, 28, 16, 5 + B, 0.8), '#9aa7c7', { lw: 1.8 });
    ctx.strokeStyle = 'rgba(74,58,79,0.45)'; ctx.lineWidth = 1.5;
    for (let i = 0; i < 3; i++) {
      const sx = x + 104 + i * 7, ph = t * 2 + i;
      ctx.beginPath();
      for (let k = 0; k < 6; k++) { const yy = fy - 58 - k * 4 - (t * 8 % 4); ctx.lineTo(sx + Math.sin(ph + k) * 2.5, yy); }
      ctx.stroke();
    }
    for (let i = 0; i < 4; i++) crayon(ctx, () => wrect(ctx, x + 62 + i * 30, y + 14, 16, 20, 6 + i + B, 0.8), [PAL.rose, PAL.butter, PAL.mint, PAL.sky][i], { lw: 1.4 });
    ctx.strokeStyle = INK; ctx.lineWidth = 2; wline(ctx, x + 56, y + 35, x + 182, y + 35, 11 + B, 0.6);
    crayon(ctx, () => wellipse(ctx, x + 175, fy - 30, 18, 5, 12 + B), WOOD, { lw: 1.8 });
    ctx.strokeStyle = INK; ctx.lineWidth = 2.2; wline(ctx, x + 175, fy - 26, x + 175, fy - 2, 13 + B, 0.6);
  },
  library(ctx, r, B) {
    const { x, y } = r, fy = r.y + r.h;
    crayon(ctx, () => wrect(ctx, x + 10, y + 10, 86, r.h - 12, 1 + B, 1), WOOD_DARK, { lw: 2 });
    const rr = rng(r.i * 3 + 1);
    for (let s = 0; s < 3; s++) {
      const sy = y + 14 + s * 28;
      let bx = x + 15;
      while (bx < x + 88) {
        const bw = 5 + rr() * 6, bh = 16 + rr() * 7;
        ctx.fillStyle = [PAL.rose, PAL.sky, PAL.mint, PAL.butter, PAL.lavender, '#e46f7a'][Math.floor(rr() * 6)];
        ctx.fillRect(bx, sy + 24 - bh, bw, bh);
        ctx.strokeStyle = INK; ctx.lineWidth = 1; ctx.strokeRect(bx, sy + 24 - bh, bw, bh);
        bx += bw + 1;
      }
      ctx.strokeStyle = INK; ctx.lineWidth = 2; wline(ctx, x + 12, sy + 25, x + 94, sy + 25, s + B, 0.5);
    }
    crayon(ctx, () => wellipse(ctx, x + 150, fy - 20, 32, 18, 5 + B, 0.06), '#e98f95', { lw: 2, hatch: '#c76c77', bounds: [x + 118, fy - 40, 64, 40], gap: 5 });
    crayon(ctx, () => wellipse(ctx, x + 150, fy - 38, 26, 12, 6 + B, 0.06), '#f1a6aa', { lw: 2 });
    ctx.strokeStyle = INK; ctx.lineWidth = 2; wline(ctx, x + 190, fy, x + 190, y + 30, 7 + B, 0.6);
    crayon(ctx, () => wpoly(ctx, [[x + 180, y + 18], [x + 200, y + 18], [x + 205, y + 32], [x + 175, y + 32]], 8 + B, 0.8), PAL.butter, { lw: 1.6 });
  },
  studio(ctx, r, B, t) {
    const { x, y } = r, fy = r.y + r.h;
    crayon(ctx, () => wrect(ctx, x + 138, y + 14, 48, 40, 1 + B, 1), '#e3f2ff', { lw: 2 });
    ctx.strokeStyle = INK; ctx.lineWidth = 2.2;
    wline(ctx, x + 50, fy, x + 72, y + 22, 2 + B); wline(ctx, x + 94, fy, x + 72, y + 22, 3 + B); wline(ctx, x + 72, fy - 4, x + 72, y + 30, 4 + B);
    crayon(ctx, () => wrect(ctx, x + 46, y + 26, 54, 42, 5 + B, 1), WHITE, { lw: 2 });
    const c = [PAL.rose, '#e46f7a', PAL.sky, PAL.mint, PAL.butter];
    for (let i = 0; i < 5; i++) crayon(ctx, () => wellipse(ctx, x + 56 + i * 9, y + 40 + Math.sin(i * 2) * 10, 6, 5, 6 + i + B), c[i], { stroke: false });
    crayon(ctx, () => wrect(ctx, x + 120, fy - 26, 60, 6, 12 + B, 0.8), WOOD, { lw: 1.8 });
    for (let i = 0; i < 3; i++) crayon(ctx, () => wrect(ctx, x + 128 + i * 16, fy - 40, 10, 14, 13 + i + B, 0.6), [PAL.sky, PAL.rose, PAL.butter][i], { lw: 1.4 });
    for (let i = 0; i < 4; i++) crayon(ctx, () => wellipse(ctx, x + 22 + i * 48, fy - 3, 8, 3, 20 + i + B), c[i], { stroke: false });
  },
  greenhouse(ctx, r, B, t) {
    const { x, y, w } = r, fy = r.y + r.h;
    ctx.strokeStyle = 'rgba(255,255,255,0.9)'; ctx.lineWidth = 2;
    for (let gx = x + 25; gx < x + w; gx += 25) wline(ctx, gx, y, gx, fy, gx + B, 0.6);
    for (let gy = y + 22; gy < fy; gy += 22) wline(ctx, x, gy, x + w, gy, gy + B, 0.6);
    for (let i = 0; i < 5; i++) {
      const px = x + 22 + i * 38, sz = 0.8 + (i % 3) * 0.25;
      for (let l = 0; l < 4; l++) {
        const a = -Math.PI / 2 + (l - 1.5) * 0.6 + Math.sin(t * 1.5 + i + l) * 0.05;
        crayon(ctx, () => wellipse(ctx, px + Math.cos(a) * 12 * sz, fy - 26 + Math.sin(a) * 14 * sz, 9 * sz, 4 * sz, 10 + i * 4 + l + B, 0.05, 10, a), l % 2 ? LEAF : '#7fc28f', { lw: 1.4 });
      }
      crayon(ctx, () => wpoly(ctx, [[px - 10, fy - 22], [px + 10, fy - 22], [px + 7, fy - 2], [px - 7, fy - 2]], 40 + i + B, 0.7), '#e8957a', { lw: 1.8 });
    }
    ctx.strokeStyle = INK; ctx.lineWidth = 1.4; wline(ctx, x + 100, y, x + 100, y + 22, 50 + B, 0.4);
    crayon(ctx, () => wellipse(ctx, x + 100, y + 30, 14, 9, 51 + B), '#7fc28f', { lw: 1.6 });
  },
  attic(ctx, r, B, t) {
    const { x, y, w, h } = r, fy = r.y + r.h;
    for (let i = 0; i < 26; i++) {
      const sx = x + hash2(i, 1, 9) * w, sy = y + hash2(i, 2, 9) * (h - 30);
      ctx.fillStyle = `rgba(255,240,190,${0.4 + 0.6 * Math.abs(Math.sin(t * 2 + i))})`;
      ctx.beginPath(); ctx.arc(sx, sy, 1.4, 0, TAU); ctx.fill();
    }
    crayon(ctx, () => wellipse(ctx, x + 150, y + 36, 22, 22, 1 + B, 0.03), '#2a2850', { lw: 2.2 });
    crayon(ctx, () => wellipse(ctx, x + 156, y + 32, 9, 9, 2 + B, 0.05), '#fff3c4', { stroke: false });
    ctx.strokeStyle = WHITE; ctx.lineWidth = 2;
    wline(ctx, x + 60, fy, x + 70, fy - 30, 3 + B); wline(ctx, x + 80, fy, x + 70, fy - 30, 4 + B);
    ctx.save(); ctx.translate(x + 70, fy - 34); ctx.rotate(-0.6);
    crayon(ctx, () => wrect(ctx, -24, -7, 50, 14, 5 + B, 0.8), PAL.rose, { lw: 2, stroke: WHITE });
    ctx.restore();
    crayon(ctx, () => wellipse(ctx, x + 22, fy - 10, 18, 10, 6 + B), PAL.lavender, { lw: 2, stroke: WHITE });
  },
};

sketch('dollhouse', {
  init(S) {
    const X0 = 190, RW = 200, FH = 100, G = 396;
    S.L = { X0, RW, FH, G, LX: X0 - 24 };
    const defs = [
      ['Bedroom', 0, 1, 0, PAL.rose, 'bedroom'],
      ['Kitchen', 0, 0, 120, PAL.mint, 'kitchen'],
      ['Library', 1, 1, 180, PAL.apricot, 'library'],
      ['Studio', 1, 0, 260, PAL.lavender, 'studio'],
      ['Greenhouse', 2, 0, 340, '#d6ecf7', 'greenhouse'],
      ['Star attic', 2, 1, 420, '#3d3a66', 'attic'],
    ];
    S.rooms = defs.map(([name, f, c, price, wall, draw], i) =>
      ({ name, f, c, price, wall, draw, i, x: X0 + c * RW, y: G - (f + 1) * FH, w: RW, h: FH, owned: !price, p: price ? 0 : 1 }));
    S.money = 130;
    S.guy = { x: X0 + RW + 120, fy: 0, path: [], wait: 1.5, dir: 1, walk: false, goal: null };
    S.toast = null;
  },
  roomAt(S, x, y) { return S.rooms.find(r => x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h); },
  hover(S, x, y) { const r = this.roomAt(S, x, y); return r && !r.owned && S.money >= r.price; },
  click(S, x, y) {
    const r = this.roomAt(S, x, y);
    if (!r || r.owned) return;
    if (S.money < r.price) { S.toast = { s: `$${Math.ceil(r.price - S.money)} short`, t: S.t }; return; }
    S.money -= r.price; r.owned = true; S.guy.goal = r; S.guy.wait = 0.6;
    S.toast = { s: `he bought the ${r.name.toLowerCase()}!`, t: S.t };
  },
  step(S, dt) {
    const g = S.guy, L = S.L;
    if (!g.path.length) {
      g.walk = false;
      g.wait -= dt;
      if (g.wait > 0) return;
      const owned = S.rooms.filter(r => r.owned);
      const r = g.goal || owned[Math.floor(Math.random() * owned.length)];
      g.goal = null;
      const tx = r.x + 30 + Math.random() * (r.w - 60), f0 = Math.round(g.fy);
      g.path = f0 !== r.f ? [{ x: L.LX, f: f0 }, { x: L.LX, f: r.f }, { x: tx, f: r.f }] : [{ x: tx, f: r.f }];
      g.wait = 2.5 + Math.random() * 3;
      return;
    }
    const wp = g.path[0], dx = wp.x - g.x;
    g.walk = true;
    if (Math.abs(dx) > 1) { g.x += Math.sign(dx) * Math.min(Math.abs(dx), 75 * dt); g.dir = Math.sign(dx); }
    else if (Math.abs(wp.f - g.fy) > 0.005) { g.x = wp.x; g.fy += Math.sign(wp.f - g.fy) * Math.min(Math.abs(wp.f - g.fy), 0.8 * dt); }
    else g.path.shift();
  },
  frame(S, t, dt) {
    const { ctx } = S, L = S.L, B = boil(t);
    S.money += dt * 9;
    for (const r of S.rooms) if (r.owned && r.p < 1) r.p = Math.min(1, r.p + dt * 0.8);
    this.step(S, dt);

    const sky = ctx.createLinearGradient(0, 0, 0, H);
    sky.addColorStop(0, '#cfe4f5'); sky.addColorStop(1, PAPER);
    ctx.fillStyle = sky; ctx.fillRect(0, 0, W, H);
    crayon(ctx, () => wellipse(ctx, 650, 66, 26, 26, 3 + B, 0.06), BUTTER, { hatch: '#f5b971', bounds: [620, 36, 60, 60], lw: 2 });
    for (let i = 0; i < 3; i++) {
      const cx = ((i * 260 + t * 8) % (W + 160)) - 80, cy = 50 + i * 30;
      crayon(ctx, () => cloudPath(ctx, cx, cy, 0.8, i * 11 + B), WHITE, { lw: 1.6, stroke: '#b9adc6' });
    }
    crayon(ctx, () => wpoly(ctx, [[-10, L.G], [W + 10, L.G - 4], [W + 10, H + 10], [-10, H + 10]], 20 + B, 1.5), '#bfe3b4',
      { hatch: STEM, bounds: [0, L.G - 6, W, H - L.G + 6], gap: 7 });
    // tree
    ctx.strokeStyle = INK;
    crayon(ctx, () => wpoly(ctx, [[652, L.G], [668, L.G], [664, L.G - 90], [656, L.G - 90]], 21 + B, 1), WOOD_DARK, { lw: 2 });
    crayon(ctx, () => wellipse(ctx, 660, L.G - 120, 48, 44, 22 + B, 0.08), '#9fd3a7', { hatch: '#6fae80', bounds: [610, L.G - 165, 100, 90], gap: 6 });

    const hx0 = L.X0 - 48, hx1 = L.X0 + 2 * L.RW, top = L.G - 3 * L.FH;
    crayon(ctx, () => wpoly(ctx, [[hx0 + 260, top - 40], [hx0 + 284, top - 40], [hx0 + 284, top - 4], [hx0 + 260, top - 4]], 29 + B), '#c97b7f', { lw: 2 });
    crayon(ctx, () => wpoly(ctx, [[hx0 - 22, top + 2], [(hx0 + hx1) / 2, top - 62], [hx1 + 22, top + 2]], 30 + B, 1.4), '#e9a3a0',
      { hatch: '#c97b7f', bounds: [hx0 - 22, top - 62, hx1 - hx0 + 44, 66], lw: 2.4 });
    ctx.strokeStyle = 'rgba(120,110,130,0.45)'; ctx.lineWidth = 3;
    for (let i = 0; i < 3; i++) {
      const p = (t * 0.4 + i / 3) % 1;
      ctx.globalAlpha = 1 - p;
      ctx.beginPath(); ctx.arc(hx0 + 272 + Math.sin(p * 6) * 6, top - 46 - p * 40, 4 + p * 8, 0, TAU); ctx.stroke();
    }
    ctx.globalAlpha = 1;
    crayon(ctx, () => wrect(ctx, hx0, top, hx1 - hx0, 3 * L.FH, 31 + B, 1.2), '#fff3e2',
      { hatch: '#e8d7c1', bounds: [hx0, top, 48, 3 * L.FH], gap: 8, lw: 2.6 });

    for (const r of S.rooms) {
      const rev = ease(r.p);
      if (rev < 1) this.locked(S, r, B, t);
      if (rev > 0) {
        ctx.save();
        ctx.beginPath(); ctx.rect(r.x, r.y, r.w * rev, r.h); ctx.clip();
        ctx.fillStyle = r.wall; ctx.fillRect(r.x, r.y, r.w, r.h);
        dhRooms[r.draw](ctx, r, B, t);
        ctx.restore();
        if (rev < 1) { ctx.strokeStyle = '#e46f7a'; ctx.lineWidth = 4; wline(ctx, r.x + r.w * rev, r.y + 2, r.x + r.w * rev, r.y + r.h - 2, B + r.i, 3); }
      }
    }
    // structure
    ctx.strokeStyle = INK; ctx.lineWidth = 3;
    for (let f = 0; f <= 3; f++) wline(ctx, f % 3 ? L.X0 : hx0, L.G - f * L.FH, hx1, L.G - f * L.FH, 40 + f + B, 1);
    wline(ctx, L.X0 + L.RW, top, L.X0 + L.RW, L.G, 45 + B, 1);
    wline(ctx, L.X0, top, L.X0, L.G, 46 + B, 1);
    ctx.lineWidth = 2.2;
    wline(ctx, L.LX - 12, L.G, L.LX - 12, top + 8, 47 + B, 0.8); wline(ctx, L.LX + 12, L.G, L.LX + 12, top + 8, 48 + B, 0.8);
    ctx.lineWidth = 1.8;
    for (let ry = L.G - 12; ry > top + 10; ry -= 14) wline(ctx, L.LX - 12, ry, L.LX + 12, ry, ry + B, 0.6);
    // front door
    crayon(ctx, () => wrect(ctx, hx0 - 2, L.G - 56, 8, 54, 49 + B, 0.8), '#e46f7a', { lw: 2 });

    const g = S.guy;
    drawGuy(ctx, g.x, L.G - g.fy * L.FH - 3, 0.78, { t, walk: g.walk, dir: g.dir });

    // HUD
    crayon(ctx, () => wellipse(ctx, 32, 32, 13, 13, 50 + B, 0.05), '#ffd36b', { lw: 2 });
    text(ctx, '$', 32, 33, { size: 18 });
    text(ctx, `${Math.floor(S.money)}`, 52, 33, { size: 30, align: 'left' });
    text(ctx, 'he earns while you watch', 24, 60, { size: 16, align: 'left', color: '#8b7b8d' });
    if (S.toast && t - S.toast.t < 2.2) {
      const a = 1 - ease((t - S.toast.t - 1.6) / 0.6);
      bubble(ctx, g.x, L.G - g.fy * L.FH - 66, S.toast.s, { alpha: a });
    }
    grain(ctx, 0.9);
  },
  locked(S, r, B, t) {
    const { ctx } = S;
    ctx.fillStyle = '#f6f1ea'; ctx.fillRect(r.x, r.y, r.w, r.h);
    ctx.save(); ctx.beginPath(); ctx.rect(r.x, r.y, r.w, r.h); ctx.clip();
    hatch(ctx, r.x, r.y, r.w, r.h, '#b7aec0', 10, r.i, 0.35, 1);
    ctx.restore();
    ctx.save(); ctx.setLineDash([5, 5]); ctx.strokeStyle = '#a99fb3'; ctx.lineWidth = 1.5;
    ctx.strokeRect(r.x + 8, r.y + 8, r.w - 16, r.h - 16); ctx.restore();
    if (r.owned) return;
    const afford = S.money >= r.price;
    const over = S.mouse.in && S.mouse.x > r.x && S.mouse.x < r.x + r.w && S.mouse.y > r.y && S.mouse.y < r.y + r.h;
    const sc = over && afford ? 1.08 : 1;
    ctx.save();
    ctx.translate(r.x + r.w / 2, r.y + r.h / 2);
    ctx.rotate((hash2(r.i, 3) - 0.5) * 0.14 + (afford ? Math.sin(t * 3 + r.i) * 0.03 : 0));
    ctx.scale(sc, sc);
    ctx.globalAlpha = afford ? 1 : 0.55;
    crayon(ctx, () => wpoly(ctx, [[-58, -22], [44, -22], [60, 0], [44, 22], [-58, 22]], r.i + B, 0.8), afford ? WHITE : '#efe9e2', { lw: 2 });
    ctx.strokeStyle = INK; ctx.lineWidth = 1.5; ctx.beginPath(); ctx.arc(44, 0, 3.5, 0, TAU); ctx.stroke();
    text(ctx, r.name, -8, -7, { size: 18 });
    text(ctx, `$${r.price}`, -8, 11, { size: 20, color: afford ? '#e46f7a' : '#8b7b8d' });
    ctx.restore();
  },
});

// ================================================================================================
// Pocket dioramas (isometric clay)
// ================================================================================================

function isoPoly(ctx, pts, fill, edge = 'rgba(70,50,80,0.28)') {
  ctx.beginPath();
  pts.forEach((p, i) => i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1]));
  ctx.closePath();
  ctx.fillStyle = fill; ctx.fill();
  if (edge) { ctx.strokeStyle = edge; ctx.lineWidth = 1; ctx.stroke(); }
}
/** Box in diorama units: x runs down-right, y down-left, z up. Visible faces are top, +y and +x. */
function isoBox(ctx, P, x, y, z, w, d, h, c, o = {}) {
  isoPoly(ctx, [P(x, y, z + h), P(x + w, y, z + h), P(x + w, y + d, z + h), P(x, y + d, z + h)], o.top || shade(c, 0.12));
  isoPoly(ctx, [P(x, y + d, z), P(x + w, y + d, z), P(x + w, y + d, z + h), P(x, y + d, z + h)], o.fl || c);
  isoPoly(ctx, [P(x + w, y, z), P(x + w, y + d, z), P(x + w, y + d, z + h), P(x + w, y, z + h)], o.fr || shade(c, -0.14));
}

const DIORAMAS = [
  {
    name: 'Bedroom', price: 0, floor: '#f3dccb', wl: '#f9cfdc', wr: '#f2b5c8',
    props(ctx, P) {
      isoPoly(ctx, [P(0.9, 1.3, 0.01), P(2.4, 1.3, 0.01), P(2.4, 2.6, 0.01), P(0.9, 2.6, 0.01)], '#cdbaf0', null);
      isoBox(ctx, P, 0.1, 0.15, 0, 1.5, 1.0, 0.42, '#d7a982');
      isoBox(ctx, P, 0.15, 0.2, 0.42, 0.45, 0.9, 0.16, '#fffdf8');
      isoBox(ctx, P, 0.6, 0.2, 0.42, 1.0, 0.9, 0.12, '#a9cdee');
      isoBox(ctx, P, 1.9, 0.12, 0, 0.5, 0.5, 0.55, '#d7a982');
      isoBox(ctx, P, 2.05, 0.25, 0.55, 0.2, 0.2, 0.35, '#ffe3a1');
    },
    wall(ctx, P) { isoPoly(ctx, [P(0.9, 0, 1.0), P(1.9, 0, 1.0), P(1.9, 0, 1.8), P(0.9, 0, 1.8)], '#e3f2ff'); },
  },
  {
    name: 'Lofi studio', price: 60, floor: '#ded3ee', wl: '#cdbaf0', wr: '#b6a2e0',
    props(ctx, P, t) {
      isoBox(ctx, P, 0.1, 0.1, 0, 1.8, 0.7, 0.6, '#c9a27e');
      isoBox(ctx, P, 0.5, 0.15, 0.6, 0.8, 0.1, 0.55, '#2d2a40', { fl: `rgba(140,220,255,${0.7 + 0.15 * Math.sin(t * 3)})` });
      isoBox(ctx, P, 0.8, 1.0, 0, 0.55, 0.55, 0.35, '#e46f7a');
      isoBox(ctx, P, 2.3, 0.2, 0, 0.45, 0.45, 0.35, '#e8957a');
      for (let l = 0; l < 3; l++) isoBox(ctx, P, 2.35 + l * 0.08, 0.25 + l * 0.05, 0.35 + l * 0.12, 0.3, 0.3, 0.2, '#7fc28f');
    },
    wall(ctx, P) { isoPoly(ctx, [P(0, 1.2, 0.9), P(0, 2.4, 0.9), P(0, 2.4, 1.9), P(0, 1.2, 1.9)], '#2b2850'); },
  },
  {
    name: 'Moss library', price: 90, floor: '#d9eadb', wl: '#b5e5cd', wr: '#94d1b3',
    props(ctx, P) {
      isoBox(ctx, P, 0.05, 0.2, 0, 0.45, 2.5, 1.9, '#a87a5a');
      const cs = ['#f8bfd0', '#b8d7f4', '#ffe3a1', '#cdbaf0', '#e46f7a'];
      for (let s = 0; s < 3; s++) for (let b = 0; b < 8; b++)
        isoPoly(ctx, [P(0.5, 0.3 + b * 0.29, 0.2 + s * 0.6), P(0.5, 0.5 + b * 0.29, 0.2 + s * 0.6), P(0.5, 0.5 + b * 0.29, 0.6 + s * 0.6), P(0.5, 0.3 + b * 0.29, 0.6 + s * 0.6)], cs[(b + s * 3) % 5]);
      isoBox(ctx, P, 1.5, 1.4, 0, 0.9, 0.9, 0.45, '#e98f95');
      isoBox(ctx, P, 1.5, 1.4, 0.45, 0.9, 0.25, 0.5, '#e98f95');
      isoBox(ctx, P, 2.5, 0.3, 0, 0.12, 0.12, 1.6, '#4a3a4f');
      isoBox(ctx, P, 2.36, 0.16, 1.6, 0.4, 0.4, 0.3, '#ffe3a1');
    },
  },
  {
    name: 'Tiny kitchen', price: 80, floor: '#f6ead0', wl: '#ffe9b8', wr: '#ffd98a',
    props(ctx, P, t) {
      isoBox(ctx, P, 0.1, 0.1, 0, 1.9, 0.6, 0.7, '#fffdf8', { top: '#e9d2b6' });
      isoBox(ctx, P, 2.2, 0.1, 0, 0.65, 0.65, 1.7, '#dff0f6');
      isoBox(ctx, P, 0.8, 0.2, 0.7, 0.4, 0.35, 0.25, '#9aa7c7');
      isoBox(ctx, P, 1.1, 1.6, 0, 0.9, 0.9, 0.55, '#e8b48a');
      isoBox(ctx, P, 1.4, 1.9, 0.55, 0.25, 0.25, 0.2, '#e46f7a');
      const [sx, sy] = P(1.0, 0.35, 1.1);
      ctx.strokeStyle = 'rgba(255,255,255,0.8)'; ctx.lineWidth = 2;
      ctx.beginPath(); for (let k = 0; k < 6; k++) ctx.lineTo(sx + Math.sin(t * 3 + k) * 3, sy - k * 4 - (t * 10 % 4)); ctx.stroke();
    },
  },
  {
    name: 'Observatory', price: 150, floor: '#45436e', wl: '#3a3866', wr: '#2c2a52',
    props(ctx, P, t) {
      isoPoly(ctx, [P(0.6, 0.6, 0.01), P(2.4, 0.6, 0.01), P(2.4, 2.4, 0.01), P(0.6, 2.4, 0.01)], '#5b5890', null);
      isoBox(ctx, P, 1.35, 1.35, 0, 0.3, 0.3, 0.7, '#cfc7e0');
      const a = P(1.1, 1.6, 0.85), b = P(2.0, 0.9, 1.9);
      ctx.lineCap = 'round';
      ctx.strokeStyle = '#2a2040'; ctx.lineWidth = 13; ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.stroke();
      ctx.strokeStyle = '#f8bfd0'; ctx.lineWidth = 10; ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.stroke();
      isoBox(ctx, P, 0.3, 2.0, 0, 0.7, 0.7, 0.3, '#cdbaf0');
    },
    wall(ctx, P, t) {
      for (let i = 0; i < 14; i++) {
        const onLeft = i % 2, u = 0.2 + hash2(i, 4) * 2.6, z = 0.4 + hash2(i, 5) * 1.9;
        const [sx, sy] = onLeft ? P(0, u, z) : P(u, 0, z);
        ctx.fillStyle = `rgba(255,240,190,${0.4 + 0.6 * Math.abs(Math.sin(t * 2 + i))})`;
        ctx.beginPath(); ctx.arc(sx, sy, 1.6, 0, TAU); ctx.fill();
      }
    },
  },
  {
    name: 'Bath house', price: 110, floor: '#d3e6f2', wl: '#b8d7f4', wr: '#98c2ea',
    props(ctx, P, t) {
      isoBox(ctx, P, 0.3, 0.3, 0, 1.8, 1.3, 0.5, '#fffdf8', { top: '#8fd0e6' });
      isoBox(ctx, P, 2.3, 1.9, 0, 0.5, 0.5, 0.4, '#d7a982');
      isoBox(ctx, P, 2.35, 1.95, 0.4, 0.4, 0.4, 0.18, '#f8bfd0');
      ctx.strokeStyle = 'rgba(255,255,255,0.8)'; ctx.lineWidth = 2.4;
      for (let i = 0; i < 3; i++) {
        const [sx, sy] = P(0.8 + i * 0.5, 0.9, 0.6), p = (t * 0.5 + i / 3) % 1;
        ctx.globalAlpha = 1 - p;
        ctx.beginPath(); for (let k = 0; k < 6; k++) ctx.lineTo(sx + Math.sin(t * 2 + k + i) * 4, sy - k * 5 - p * 20); ctx.stroke();
      }
      ctx.globalAlpha = 1;
    },
  },
];

sketch('dioramas', {
  init(S) {
    S.U = 24;
    S.slots = [[360, 66], [196, 148], [524, 148], [360, 240], [196, 318], [524, 318]].map(([x, y], i) =>
      ({ x, y, th: DIORAMAS[i], owned: i === 0, born: -10 }));
    S.spent = 0;
    S.guy = { at: 0, from: 0, to: 0, u: 1, wait: 2 };
  },
  P(S, sl, t, k = 1) {
    const u = S.U * k, ax = sl.x, ay = sl.y + Math.sin(t * 1.1 + sl.x * 0.03) * 3;
    return (x, y, z) => [ax + (x - y) * u * 0.866, ay + (x + y) * u * 0.5 - z * u];
  },
  slotAt(S, x, y) {
    return S.slots.find(sl => Math.abs(x - sl.x) / 64 + Math.abs(y - (sl.y + 36)) / 38 < 1.1);
  },
  hover(S, x, y) { const sl = this.slotAt(S, x, y); return sl && !sl.owned; },
  click(S, x, y) {
    const sl = this.slotAt(S, x, y);
    if (!sl || sl.owned) return;
    sl.owned = true; sl.born = S.t; S.spent += sl.th.price;
    const g = S.guy; g.from = g.at; g.to = S.slots.indexOf(sl); g.u = 0; g.wait = 0;
  },
  frame(S, t, dt) {
    const { ctx } = S, U = S.U;
    const bg = ctx.createLinearGradient(0, 0, W, H);
    bg.addColorStop(0, '#efe6f6'); bg.addColorStop(1, '#fde9dc');
    ctx.fillStyle = bg; ctx.fillRect(0, 0, W, H);
    for (let i = 0; i < 18; i++) {
      ctx.fillStyle = 'rgba(255,255,255,0.5)';
      const px = (hash2(i, 1) * W + t * (4 + i % 5)) % W, py = hash2(i, 2) * H;
      ctx.beginPath(); ctx.arc(px, py, 1.5 + hash2(i, 3) * 2, 0, TAU); ctx.fill();
    }
    // guy hop
    const g = S.guy;
    if (g.u < 1) { g.u = Math.min(1, g.u + dt * 1.1); if (g.u >= 1) { g.at = g.to; g.wait = 2.5 + Math.random() * 2; } }
    else if ((g.wait -= dt) <= 0) {
      const owned = S.slots.map((s, i) => s.owned ? i : -1).filter(i => i >= 0 && i !== g.at);
      if (owned.length) { g.from = g.at; g.to = owned[Math.floor(Math.random() * owned.length)]; g.u = 0; }
      else g.wait = 2;
    }
    S.slots.forEach((sl, i) => {
      if (!sl.owned) {
        const P = this.P(S, sl, t);
        const pts = [P(0, 0, 0), P(3, 0, 0), P(3, 3, 0), P(0, 3, 0)];
        ctx.save(); ctx.setLineDash([6, 6]); ctx.lineDashOffset = -t * 10;
        ctx.strokeStyle = 'rgba(74,58,79,0.45)'; ctx.lineWidth = 2;
        ctx.beginPath(); pts.forEach((p, k) => k ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])); ctx.closePath(); ctx.stroke();
        ctx.restore();
        const over = S.mouse.in && this.slotAt(S, S.mouse.x, S.mouse.y) === sl;
        text(ctx, '+', sl.x, sl.y + 36, { size: over ? 40 : 32, color: over ? '#e46f7a' : '#8b7b8d' });
        text(ctx, `${sl.th.name} · $${sl.th.price}`, sl.x, sl.y + 88, { size: 17, color: '#6d5d72' });
        return;
      }
      const k = easeBack((t - sl.born) / 0.7);
      ctx.save();
      ctx.globalAlpha = clamp((t - sl.born) / 0.25, 0, 1);
      ctx.fillStyle = 'rgba(80,50,90,0.12)';
      ctx.beginPath(); ctx.ellipse(sl.x, sl.y + 94, 54 * k, 7 * k, 0, 0, TAU); ctx.fill();
      const P = this.P(S, { x: sl.x, y: sl.y + (1 - k) * 40 }, t, k);
      const th = sl.th;
      isoBox(ctx, P, 0, 0, -0.45, 3, 3, 0.45, '#eadbd0', { top: th.floor, fl: '#e2d0c4', fr: '#cdb9ae' });
      isoBox(ctx, P, -0.16, -0.16, -0.45, 3.16, 0.16, 2.6, th.wr, { fl: th.wr, fr: shade(th.wr, -0.2), top: shade(th.wr, 0.3) });
      isoBox(ctx, P, -0.16, 0, -0.45, 0.16, 3, 2.6, th.wl, { fr: th.wl, fl: shade(th.wl, -0.2), top: shade(th.wl, 0.3) });
      th.wall && th.wall(ctx, P, t);
      th.props(ctx, P, t);
      ctx.restore();
      if (g.u >= 1 && g.at === i) {
        const [gx, gy] = P(1.9, 2.1, 0);
        drawGuy(ctx, gx, gy, 0.5, { t, style: 'flat', walk: false });
      }
    });
    for (const sl of S.slots) if (sl.owned) text(ctx, sl.th.name, sl.x, sl.y + 110, { size: 18, color: '#6d5d72' });
    if (g.u < 1) {
      const a = S.slots[g.from], b = S.slots[g.to], u = ease(g.u);
      const pa = this.P(S, a, t)(1.9, 2.1, 0), pb = this.P(S, b, t)(1.9, 2.1, 0);
      const x = lerp(pa[0], pb[0], u), y = lerp(pa[1], pb[1], u) - Math.sin(Math.PI * u) * 90;
      drawGuy(ctx, x, y, 0.5, { t, style: 'flat', walk: true, dir: pb[0] >= pa[0] ? 1 : -1, shadow: false });
    }
    text(ctx, `spent $${S.spent}`, 20, 26, { size: 20, align: 'left', color: '#6d5d72' });
  },
});

// ================================================================================================
// Rooms by mail: riso catalog + floor plan
// ================================================================================================

const RISO = { pink: '#ff48b0', blue: '#0078bf', yellow: '#ffe800', paper: '#f6f0e2' };
const MODULES = [
  { id: 'kitchen', name: 'Galley kitchen', w: 2, h: 1, price: 90, ink: 'yellow', line: 'cooks real food' },
  { id: 'studio', name: 'Art nook', w: 1, h: 1, price: 60, ink: 'pink', line: 'paint bigger' },
  { id: 'bath', name: 'Bath', w: 1, h: 1, price: 45, ink: 'blue', line: 'soak off a bad day' },
  { id: 'library', name: 'Reading room', w: 1, h: 2, price: 120, ink: 'yellow', line: 'shelves for days' },
  { id: 'garden', name: 'Garden room', w: 2, h: 2, price: 220, ink: 'pink', line: 'grow tomatoes' },
  { id: 'hall', name: 'Hallway', w: 1, h: 1, price: 15, ink: 'blue', line: 'connects things' },
];

function dotTile(color, size, r) {
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const g = c.getContext('2d');
  g.fillStyle = color;
  for (const [x, y] of [[0, 0], [size, 0], [0, size], [size, size], [size / 2, size / 2]]) { g.beginPath(); g.arc(x, y, r, 0, TAU); g.fill(); }
  return c;
}

/** Top-down furniture glyphs for a module, in a box of cells. */
function planGlyphs(ctx, id, x, y, w, h) {
  ctx.save();
  ctx.strokeStyle = RISO.blue; ctx.lineWidth = 1.6; ctx.fillStyle = 'rgba(255,72,176,0.55)';
  const box = (bx, by, bw, bh, f = true) => { ctx.beginPath(); ctx.rect(x + bx * w, y + by * h, bw * w, bh * h); if (f) ctx.fill(); ctx.stroke(); };
  const dot = (bx, by, r, f = true) => { ctx.beginPath(); ctx.arc(x + bx * w, y + by * h, r * Math.min(w, h), 0, TAU); if (f) ctx.fill(); ctx.stroke(); };
  switch (id) {
    case 'bedroom': box(0.08, 0.15, 0.32, 0.7); box(0.08, 0.15, 0.1, 0.7, false); dot(0.7, 0.5, 0.22, false); break;
    case 'kitchen': box(0.05, 0.08, 0.9, 0.18); dot(0.6, 0.6, 0.18); dot(0.4, 0.6, 0.07, false); dot(0.8, 0.6, 0.07, false); break;
    case 'studio': ctx.beginPath(); ctx.moveTo(x + w * 0.3, y + h * 0.75); ctx.lineTo(x + w * 0.5, y + h * 0.25); ctx.lineTo(x + w * 0.7, y + h * 0.75); ctx.stroke(); dot(0.75, 0.3, 0.1); break;
    case 'bath': box(0.1, 0.15, 0.45, 0.7); dot(0.78, 0.3, 0.1, false); break;
    case 'library': for (let i = 0; i < 4; i++) box(0.08, 0.08 + i * 0.12, 0.25, 0.08); dot(0.65, 0.75, 0.2); break;
    case 'garden': for (let i = 0; i < 7; i++) dot(0.15 + hash2(i, 1) * 0.7, 0.15 + hash2(i, 2) * 0.7, 0.05 + hash2(i, 3) * 0.06); break;
    case 'hall': box(0.3, 0.1, 0.4, 0.8, false); break;
  }
  ctx.restore();
}

sketch('catalog', {
  init(S) {
    S.C = 42; S.N = 7; S.gx0 = 384; S.gy0 = 100;
    S.pat = {
      pink: S.ctx.createPattern(dotTile(RISO.pink, 6, 1.7), 'repeat'),
      blue: S.ctx.createPattern(dotTile(RISO.blue, 6, 1.5), 'repeat'),
      yellow: RISO.yellow,
    };
    const bedroom = { mod: { id: 'bedroom', name: 'Bedroom', ink: 'blue', w: 2, h: 1 }, gx: 2, gy: 3, w: 2, h: 1, parent: null, door: null, born: -9 };
    S.placed = [bedroom];
    S.guy = { x: 0, y: 0, room: bedroom, path: [], wait: 1, dir: [1, 0] };
    [S.guy.x, S.guy.y] = this.center(S, bedroom);
    S.cards = MODULES.map((m, i) => ({ m, x: 24 + (i % 2) * 166, y: 116 + Math.floor(i / 2) * 104, w: 154, h: 94 }));
    S.flash = null;
  },
  center(S, r) { return [S.gx0 + (r.gx + r.w / 2) * S.C, S.gy0 + (r.gy + r.h / 2) * S.C]; },
  fits(S, gx, gy, w, h) {
    if (gx < 0 || gy < 0 || gx + w > S.N || gy + h > S.N) return false;
    return !S.placed.some(r => gx < r.gx + r.w && r.gx < gx + w && gy < r.gy + r.h && r.gy < gy + h);
  },
  /** The shared wall between two rooms that touch, as a door position; null if they don't touch. */
  door(a, b) {
    const yo = [Math.max(a.gy, b.gy), Math.min(a.gy + a.h, b.gy + b.h)], xo = [Math.max(a.gx, b.gx), Math.min(a.gx + a.w, b.gx + b.w)];
    if ((a.gx + a.w === b.gx || b.gx + b.w === a.gx) && yo[1] > yo[0]) return { gx: a.gx + a.w === b.gx ? b.gx : a.gx, gy: (yo[0] + yo[1]) / 2, v: true };
    if ((a.gy + a.h === b.gy || b.gy + b.h === a.gy) && xo[1] > xo[0]) return { gx: (xo[0] + xo[1]) / 2, gy: a.gy + a.h === b.gy ? b.gy : a.gy, v: false };
    return null;
  },
  order(S, m) {
    const cands = [];
    for (const p of S.placed) for (let gx = 0; gx < S.N; gx++) for (let gy = 0; gy < S.N; gy++) {
      if (!this.fits(S, gx, gy, m.w, m.h)) continue;
      const d = this.door(p, { gx, gy, w: m.w, h: m.h });
      if (d) cands.push({ gx, gy, parent: p, door: d, score: Math.hypot(gx + m.w / 2 - 3.5, gy + m.h / 2 - 3.5) + Math.random() * 1.6 });
    }
    if (!cands.length) return false;
    cands.sort((a, b) => a.score - b.score);
    const c = cands[0], room = { mod: m, gx: c.gx, gy: c.gy, w: m.w, h: m.h, parent: c.parent, door: c.door, born: S.t };
    S.placed.push(room);
    this.walkTo(S, room);
    return true;
  },
  walkTo(S, target) {
    const g = S.guy, up = r => { const a = []; for (; r; r = r.parent) a.push(r); return a; };
    const A = up(g.room), Bs = up(target), lca = A.find(r => Bs.includes(r));
    const chain = [...A.slice(0, A.indexOf(lca) + 1), ...Bs.slice(0, Bs.indexOf(lca)).reverse()];
    const pts = [];
    for (let i = 1; i < chain.length; i++) {
      const a = chain[i - 1], b = chain[i], d = b.parent === a ? b.door : a.door;
      pts.push([S.gx0 + d.gx * S.C, S.gy0 + d.gy * S.C]);
    }
    const [cx, cy] = this.center(S, target);
    pts.push([cx + (Math.random() - 0.5) * (target.w * S.C - 30), cy + (Math.random() - 0.5) * (target.h * S.C - 30)]);
    g.path = pts; g.room = target;
  },
  cardAt(S, x, y) { return S.cards.find(c => x > c.x && x < c.x + c.w && y > c.y && y < c.y + c.h); },
  hover(S, x, y) { return !!this.cardAt(S, x, y); },
  click(S, x, y) {
    const c = this.cardAt(S, x, y);
    if (c) S.flash = { card: c, t: S.t, ok: this.order(S, c.m) };
  },
  frame(S, t, dt) {
    const { ctx } = S, C = S.C;
    // guy
    const g = S.guy;
    if (g.path.length) {
      const [px, py] = g.path[0], dx = px - g.x, dy = py - g.y, d = Math.hypot(dx, dy);
      if (d < 1) g.path.shift(); else { const st = Math.min(d, 60 * dt); g.x += dx / d * st; g.y += dy / d * st; g.dir = [dx / d, dy / d]; }
    } else if ((g.wait -= dt) <= 0) { g.wait = 2 + Math.random() * 3; this.walkTo(S, S.placed[Math.floor(Math.random() * S.placed.length)]); }

    ctx.fillStyle = RISO.paper; ctx.fillRect(0, 0, W, H);
    ctx.globalCompositeOperation = 'multiply';
    // misregistered title: pink plate nudged
    for (const [ink, ox, oy] of [['pink', 2, 1.5], ['blue', 0, 0]]) {
      text(ctx, 'LIL GUY HOMES', 24 + ox, 44 + oy, { font: '30px "Rubik Mono One", sans-serif', align: 'left', color: RISO[ink] });
    }
    text(ctx, 'rooms by mail · they snap on · no tools', 26, 80, { font: '700 14px Nunito, sans-serif', align: 'left', color: RISO.pink });
    ctx.fillStyle = S.pat.blue; ctx.fillRect(24, 92, 320, 6);

    for (const c of S.cards) {
      const fl = S.flash && S.flash.card === c ? clamp(1 - (t - S.flash.t) / 0.5, 0, 1) : 0;
      const over = S.mouse.in && this.cardAt(S, S.mouse.x, S.mouse.y) === c;
      ctx.save(); ctx.translate(c.x, c.y);
      ctx.fillStyle = S.pat[c.m.ink]; ctx.fillRect(2, 2, c.w, c.h);
      if (fl && !S.flash.ok) { ctx.fillStyle = RISO.blue; ctx.globalAlpha = fl * 0.5; ctx.fillRect(0, 0, c.w, c.h); ctx.globalAlpha = 1; }
      ctx.strokeStyle = RISO.blue; ctx.lineWidth = over ? 3 : 2; ctx.strokeRect(0, 0, c.w, c.h);
      const sz = 22, pw = c.m.w * sz, ph = c.m.h * sz;
      ctx.strokeRect(10, 10, pw, ph); planGlyphs(ctx, c.m.id, 10, 10, pw, ph);
      text(ctx, c.m.name, c.w - 8, 18, { font: '800 13px Nunito, sans-serif', align: 'right', color: RISO.blue });
      text(ctx, c.m.line, c.w - 8, 36, { font: '600 11px Nunito, sans-serif', align: 'right', color: RISO.blue });
      text(ctx, `$${c.m.price}`, c.w - 8, 60, { font: '20px "Rubik Mono One", sans-serif', align: 'right', color: RISO.pink });
      text(ctx, `${c.m.w}×${c.m.h}`, 10, c.h - 12, { font: '700 11px Nunito, sans-serif', align: 'left', color: RISO.blue });
      text(ctx, fl && !S.flash.ok ? 'NO ROOM!' : 'ORDER ▸', c.w - 8, c.h - 12, { font: '800 12px Nunito, sans-serif', align: 'right', color: over ? RISO.pink : RISO.blue });
      ctx.restore();
    }

    // plan
    text(ctx, 'HIS PLACE', S.gx0, 60, { font: '18px "Rubik Mono One", sans-serif', align: 'left', color: RISO.blue });
    text(ctx, `${S.placed.length} room${S.placed.length > 1 ? 's' : ''} · plan, scale 1:50`, S.gx0, 82, { font: '700 12px Nunito, sans-serif', align: 'left', color: RISO.pink });
    ctx.fillStyle = 'rgba(0,120,191,0.35)';
    for (let i = 0; i <= S.N; i++) for (let j = 0; j <= S.N; j++) { ctx.beginPath(); ctx.arc(S.gx0 + i * C, S.gy0 + j * C, 1.3, 0, TAU); ctx.fill(); }
    for (const r of S.placed) {
      const k = easeBack((t - r.born) / 0.5), x = S.gx0 + r.gx * C, y = S.gy0 + r.gy * C, w = r.w * C, h = r.h * C;
      ctx.save();
      ctx.translate(x + w / 2, y + h / 2); ctx.scale(k, k); ctx.translate(-w / 2, -h / 2);
      ctx.fillStyle = S.pat[r.mod.ink]; ctx.fillRect(0, 0, w, h);
      if (t - r.born < 0.6) { ctx.fillStyle = RISO.pink; ctx.globalAlpha = 1 - (t - r.born) / 0.6; ctx.fillRect(0, 0, w, h); ctx.globalAlpha = 1; }
      planGlyphs(ctx, r.mod.id, 0, 0, w, h);
      ctx.strokeStyle = RISO.blue; ctx.lineWidth = 4; ctx.strokeRect(0, 0, w, h);
      text(ctx, r.mod.id.toUpperCase(), w / 2, h - 9, { font: '800 8px Nunito, sans-serif', color: RISO.blue });
      ctx.restore();
    }
    ctx.globalCompositeOperation = 'source-over';
    // doors: knock a gap in the wall
    for (const r of S.placed) if (r.door && t - r.born > 0.4) {
      const d = r.door, x = S.gx0 + d.gx * C, y = S.gy0 + d.gy * C;
      ctx.fillStyle = RISO.paper;
      if (d.v) ctx.fillRect(x - 3, y - 9, 6, 18); else ctx.fillRect(x - 9, y - 3, 18, 6);
    }
    // guy, top-down
    ctx.save(); ctx.translate(g.x, g.y);
    ctx.fillStyle = RISO.yellow; ctx.strokeStyle = RISO.blue; ctx.lineWidth = 2;
    const bob = g.path.length ? Math.sin(t * 14) * 0.08 : 0;
    ctx.beginPath(); ctx.ellipse(0, 0, 9 * (1 + bob), 9 * (1 - bob), 0, 0, TAU); ctx.fill(); ctx.stroke();
    ctx.fillStyle = RISO.blue;
    for (const k of [-1, 1]) { ctx.beginPath(); ctx.arc(g.dir[0] * 4 - g.dir[1] * k * 3, g.dir[1] * 4 + g.dir[0] * k * 3, 1.4, 0, TAU); ctx.fill(); }
    ctx.fillStyle = RISO.pink; ctx.beginPath(); ctx.ellipse(-4, -9, 4, 2, -0.5, 0, TAU); ctx.ellipse(4, -10, 4, 2, 0.5, 0, TAU); ctx.fill();
    ctx.restore();
    // ink dropout
    grain(ctx, 1);
  },
});
