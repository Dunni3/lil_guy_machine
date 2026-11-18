'use strict';
// Snap-on dioramas: one room at a time, big enough to decorate. Its neighbours flatten to floor plans, and
// the whole house can flatten too, as a plan for choosing where the next room snaps on.

const TRAY_Y = H - 64;
const RISE = { neighbour: 0.05, plan: 0.16 };

function drawHouseBox(ctx, G, b) {
  let o = { top: b.top, fl: b.fl, fr: b.fr };
  if (b.inner === 'x') o = { fr: b.c, fl: shade(b.c, -0.2), top: shade(b.c, 0.3) };
  if (b.inner === 'y') o = { fl: shade(b.c, -0.07), fr: shade(b.c, -0.2), top: shade(b.c, 0.3) };
  isoBox(ctx, G, b.x, b.y, b.z, b.w, b.d, b.h, b.c, o);
}
function pill(ctx, x, y, w, h, label, on, o = {}) {
  roundRectPath(ctx, x, y, w, h, h / 2);
  ctx.fillStyle = on ? '#e46f7a' : (o.over ? '#fff' : 'rgba(255,250,243,0.92)'); ctx.fill();
  ctx.strokeStyle = on ? '#e46f7a' : (o.over ? INK : '#dccdbd'); ctx.lineWidth = 1.5; ctx.stroke();
  text(ctx, label, x + w / 2, y + h / 2 + 1, { size: o.size || 16, color: on ? '#fff' : INK });
}

sketch('snapon', {
  init(S) {
    S.rooms = window.HOUSE.rooms;
    S.mode = 'room'; S.focus = S.rooms[0]; S.follow = false;
    S.sel = null; S.rot = false; S.spent = 0;
    S.item = ITEM.plant; S.itemRot = false;
    S.hot = null; S.place = null; S.doorTags = [];
    for (const r of S.rooms) r.rise = 1;
    S.view = { u: 40, ox: W / 2, oy: 70 };
    const [gx, gy] = freeSpot(S.rooms[0]);
    S.guy = { x: gx, y: gy, room: S.rooms[0], path: [], wait: 2, dir: 1 };
    S.btns = [
      { id: 'room', label: 'This room', x: 12, y: 10, w: 84, h: 26 },
      { id: 'house', label: 'Whole house', x: 100, y: 10, w: 100, h: 26 },
      { id: 'follow', label: 'Follow him', x: W - 104, y: 10, w: 92, h: 26 },
    ];
    this.chips(S);
  },
  chips(S) {
    if (S.mode === 'room') {
      const w = (W - 18 - 7 * 5) / 8;
      S.chipList = ITEMS.map((item, i) => ({ item, x: 9 + i * (w + 5), y: TRAY_Y + 4, w, h: 54 }));
    } else {
      S.chipList = ROOM_DEFS.filter(d => d.price).map((def, i) => ({ def, x: 9 + i * 101, y: TRAY_Y + 4, w: 96, h: 54 }));
    }
    S.spots = S.mode === 'house' && S.sel ? snapSpots(S.rooms, S.sel, S.rot) : [];
    S.hot = null; S.place = null;
  },
  setMode(S, mode) {
    S.mode = mode;
    if (mode === 'house') { S.sel = ROOM_DEFS.find(d => d.price && !this.owned(S, d)) || null; S.rot = false; S.follow = false; }
    this.chips(S);
  },
  focusOn(S, room) { S.focus = room; S.follow = false; S.mode !== 'room' ? this.setMode(S, 'room') : this.chips(S); },
  owned(S, def) { return !def.repeat && S.rooms.some(r => r.def === def); },
  G(S, bob = 0) {
    const { u, ox, oy } = S.view;
    return (x, y, z) => [ox + (x - y) * u * 0.866, oy + (x + y) * u * 0.5 - z * u + bob];
  },
  toFloor(S, mx, my) {
    const { u, ox, oy } = S.view, dd = (mx - ox) / (u * 0.866), ss = (my - oy) / (u * 0.5);
    return [(ss + dd) / 2, (ss - dd) / 2];
  },
  spotAt(S, mx, my) {
    if (!S.sel) return null;
    const [x, y] = this.toFloor(S, mx, my), w = (S.rot ? S.sel.h : S.sel.w) * CELL, h = (S.rot ? S.sel.w : S.sel.h) * CELL;
    let best = null, bd = 1e9;
    for (const s of S.spots) {
      const x0 = s.gx * CELL, y0 = s.gy * CELL;
      if (x < x0 || x > x0 + w || y < y0 || y > y0 + h) continue;
      const d = Math.hypot(x - x0 - w / 2, y - y0 - h / 2);
      if (d < bd) { bd = d; best = s; }
    }
    return best;
  },
  /** Where the held item would land under the cursor, snapped to tiles. */
  placeAt(S, mx, my) {
    const r = S.focus, it = S.item;
    if (!it) return null;
    const [x, y] = this.toFloor(S, mx, my);
    if (!roomContains(r, x, y)) return null;
    const [w, d] = footprint(it, S.itemRot);
    const tx = clamp(Math.round(x - r.gx * CELL - w / 2), 0, r.w * CELL - w), ty = clamp(Math.round(y - r.gy * CELL - d / 2), 0, r.h * CELL - d);
    return { tx, ty, w, d, ok: canPlace(r, S.rooms, it, tx, ty, S.itemRot) };
  },
  itemAt(S, mx, my) {
    const [x, y] = this.toFloor(S, mx, my), r = S.focus;
    return r.items.slice().reverse().find(it => {
      const [w, d] = footprint(it.def, it.rot), x0 = r.gx * CELL + it.tx, y0 = r.gy * CELL + it.ty;
      return x >= x0 && x <= x0 + w && y >= y0 && y <= y0 + d;
    });
  },
  chipAt(S, x, y) { return S.chipList.find(c => x >= c.x && x <= c.x + c.w && y >= c.y && y <= c.y + c.h); },
  btnAt(S, x, y) { return S.btns.find(b => (b.id !== 'follow' || S.mode === 'room') && x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h); },
  tagAt(S, x, y) { return S.doorTags.find(t => x >= t.x && x <= t.x + t.w && y >= t.y && y <= t.y + t.h); },
  roomAt(S, mx, my) { const [x, y] = this.toFloor(S, mx, my); return S.rooms.find(r => roomContains(r, x, y)); },
  hover(S, x, y) {
    S.hot = null; S.place = null;
    if (y > TRAY_Y) { const c = this.chipAt(S, x, y); return !!c && !(c.def && this.owned(S, c.def)); }
    if (this.btnAt(S, x, y) || this.tagAt(S, x, y)) return true;
    if (S.mode === 'house') { S.hot = this.spotAt(S, x, y); return !!S.hot || !!this.roomAt(S, x, y); }
    S.place = this.placeAt(S, x, y);
    return !!S.place || !!this.itemAt(S, x, y) || !!this.roomAt(S, x, y);
  },
  click(S, x, y) {
    const b = this.btnAt(S, x, y);
    if (b) {
      if (b.id === 'follow') { S.follow = !S.follow; if (S.follow) { S.focus = S.guy.room; this.chips(S); } }
      else this.setMode(S, b.id);
      return;
    }
    const tag = this.tagAt(S, x, y);
    if (tag) { this.focusOn(S, tag.room); return; }
    if (y > TRAY_Y) { this.clickChip(S, x, y); return; }
    if (S.mode === 'house') { this.clickPlan(S, x, y); return; }
    const place = this.placeAt(S, x, y);
    if (place) {
      if (place.ok) {
        S.focus.items.push({ def: S.item, tx: place.tx, ty: place.ty, rot: S.itemRot, born: S.t });
        S.spent += S.item.price;
        S.follow = false;
        houseChanged();
      } else {
        const it = this.itemAt(S, x, y);
        if (it) this.pickUp(S, it);
      }
      return;
    }
    const it = this.itemAt(S, x, y);
    if (it) { this.pickUp(S, it); return; }
    const r = this.roomAt(S, x, y);
    if (r && r !== S.focus) this.focusOn(S, r);
  },
  pickUp(S, it) {
    S.focus.items.splice(S.focus.items.indexOf(it), 1);
    S.spent -= it.def.price; S.item = it.def; S.itemRot = it.rot; S.follow = false;
    houseChanged();
  },
  clickChip(S, x, y) {
    const c = this.chipAt(S, x, y);
    if (!c) return;
    if (c.item) {
      // clicking the held card turns it if it can turn, then puts it down
      if (S.item !== c.item) { S.item = c.item; S.itemRot = false; }
      else if (c.item.w !== c.item.d && !S.itemRot) S.itemRot = true;
      else { S.item = null; S.itemRot = false; }
      return;
    }
    if (this.owned(S, c.def)) return;
    if (S.sel === c.def) { if (c.def.w !== c.def.h) S.rot = !S.rot; }
    else { S.sel = c.def; S.rot = false; }
    this.chips(S);
  },
  clickPlan(S, x, y) {
    const spot = this.spotAt(S, x, y);
    if (!spot) { const r = this.roomAt(S, x, y); if (r) this.focusOn(S, r); return; }
    const room = makeRoom(S.sel, spot.gx, spot.gy, S.rot);
    room.parent = spot.parent; room.door = spot.door; room.born = S.t; room.rise = 0;
    S.rooms.push(room);
    S.spent += S.sel.price;
    this.walkTo(S, room);
    S.sel = S.sel.repeat ? S.sel : (ROOM_DEFS.find(d => d.price && !this.owned(S, d)) || null);
    S.rot = false;
    this.chips(S);
    houseChanged();
  },
  walkTo(S, room) {
    const g = S.guy, pts = housePath(g.room, room);
    pts.push(freeSpot(room));
    g.path = pts; g.room = room;
  },
  fit(S, dt) {
    let x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
    const pad = S.mode === 'house' && S.sel ? 1 : 0, list = S.mode === 'room' ? [S.focus] : S.rooms, top = S.mode === 'room' ? WALL_H : WALL_H * RISE.plan;
    for (const r of list) for (const px of [r.gx - pad, r.gx + r.w + pad]) for (const py of [r.gy - pad, r.gy + r.h + pad]) for (const z of [-0.6, top]) {
      const sx = (px - py) * CELL * 0.866, sy = (px + py) * CELL * 0.5 - z;
      x0 = Math.min(x0, sx); x1 = Math.max(x1, sx); y0 = Math.min(y0, sy); y1 = Math.max(y1, sy);
    }
    const u = clamp(Math.min((W - 70) / (x1 - x0), (TRAY_Y - 62) / (y1 - y0)), 6, 62);
    const ox = W / 2 - (x0 + x1) / 2 * u, oy = (TRAY_Y + 34) / 2 - (y0 + y1) / 2 * u, k = 1 - Math.exp(-dt * 4.5);
    S.view.u = lerp(S.view.u, u, k); S.view.ox = lerp(S.view.ox, ox, k); S.view.oy = lerp(S.view.oy, oy, k);
  },
  step(S, dt) {
    const g = S.guy;
    if (g.path.length) {
      const [px, py] = g.path[0], dx = px - g.x, dy = py - g.y, d = Math.hypot(dx, dy);
      if (d < 0.05) g.path.shift();
      else { const st = Math.min(d, 2.6 * dt); g.x += dx / d * st; g.y += dy / d * st; if (Math.abs(dx - dy) > 0.01) g.dir = dx - dy > 0 ? 1 : -1; }
    } else if ((g.wait -= dt) <= 0) {
      g.wait = 3 + Math.random() * 3;
      this.walkTo(S, S.rooms.length > 1 && Math.random() < 0.45 ? S.rooms[Math.floor(Math.random() * S.rooms.length)] : g.room);
    }
    if (S.follow && S.mode === 'room') {
      const here = S.rooms.find(r => roomContains(r, g.x, g.y));
      if (here && here !== S.focus) { S.focus = here; this.chips(S); }
    }
    for (const r of S.rooms) {
      const want = S.mode === 'house' ? RISE.plan : r === S.focus ? 1 : RISE.neighbour;
      r.rise = lerp(r.rise ?? want, want, 1 - Math.exp(-dt * 6));
    }
  },
  frame(S, t, dt) {
    const { ctx } = S;
    this.fit(S, dt); this.step(S, dt);
    const bgr = ctx.createLinearGradient(0, 0, W, H);
    bgr.addColorStop(0, '#efe6f6'); bgr.addColorStop(1, '#fde9dc');
    ctx.fillStyle = bgr; ctx.fillRect(0, 0, W, H);
    ctx.fillStyle = 'rgba(255,255,255,0.5)';
    for (let i = 0; i < 18; i++) { ctx.beginPath(); ctx.arc((hash2(i, 1) * W + t * (4 + i % 5)) % W, hash2(i, 2) * TRAY_Y, 1.5 + hash2(i, 3) * 2, 0, TAU); ctx.fill(); }

    const bob = Math.sin(t * 1.1) * 1.6, G = this.G(S, bob), u = S.view.u, g = S.guy, room = S.mode === 'room';
    // the shadow, far below: the house floats
    ctx.fillStyle = 'rgba(80,50,90,0.09)';
    for (const r of S.rooms) {
      const x0 = r.gx * CELL, y0 = r.gy * CELL, x1 = x0 + r.w * CELL, y1 = y0 + r.h * CELL, drop = -1.3;
      const pts = [G(x0, y0, drop), G(x1, y0, drop), G(x1, y1, drop), G(x0, y1, drop)];
      ctx.beginPath(); pts.forEach((p, i) => i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])); ctx.closePath(); ctx.fill();
    }
    // open spots, in plan view
    if (S.sel && !room) {
      const w = (S.rot ? S.sel.h : S.sel.w) * CELL, h = (S.rot ? S.sel.w : S.sel.h) * CELL;
      ctx.save();
      ctx.setLineDash([5, 5]); ctx.lineDashOffset = -t * 10;
      ctx.strokeStyle = 'rgba(228,111,122,0.5)'; ctx.fillStyle = 'rgba(228,111,122,0.04)'; ctx.lineWidth = 1.4;
      for (const s of S.spots) {
        const x0 = s.gx * CELL, y0 = s.gy * CELL, pts = [G(x0, y0, 0), G(x0 + w, y0, 0), G(x0 + w, y0 + h, 0), G(x0, y0 + h, 0)];
        ctx.beginPath(); pts.forEach((p, i) => i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])); ctx.closePath(); ctx.fill(); ctx.stroke();
      }
      ctx.restore();
    }
    let preview = null;
    if (S.hot && S.mouse.in) { preview = makeRoom(S.sel, S.hot.gx, S.hot.gy, S.rot); preview.door = S.hot.door; preview.parent = S.hot.parent; preview.ghost = true; preview.rise = RISE.plan; }
    const all = preview ? [...S.rooms, preview] : S.rooms, here = S.rooms.find(r => roomContains(r, g.x, g.y)) || g.room;

    for (const r of backToFront(all)) {
      const [cx, cy] = roomCenter(r), k = r.ghost ? 1 : easeBack((t - r.born) / 0.55), rise = r.rise;
      const P = (x, y, z) => {
        const zz = z > 0 ? z * rise : z;
        return k >= 1 ? G(x, y, zz) : G(cx + (x - cx) * k, cy + (y - cy) * k, zz * k - (1 - k) * 2);
      };
      ctx.save();
      const focused = room && r === S.focus;
      ctx.globalAlpha = r.ghost ? 0.62 : (room && !focused ? 0.72 : 1) * clamp((t - r.born) / 0.2, 0, 1);
      drawHouseBox(ctx, P, roomFloor(r));
      if (focused && rise > 0.7) {
        // tiles
        ctx.strokeStyle = 'rgba(74,58,79,0.1)'; ctx.lineWidth = 1;
        const x0 = r.gx * CELL, y0 = r.gy * CELL, x1 = x0 + r.w * CELL, y1 = y0 + r.h * CELL;
        ctx.beginPath();
        for (let x = x0 + 1; x < x1; x++) { const a = G(x, y0, 0), b = G(x, y1, 0); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); }
        for (let y = y0 + 1; y < y1; y++) { const a = G(x0, y, 0), b = G(x1, y, 0); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); }
        ctx.stroke();
      }
      for (const b of roomWalls(r, all)) drawHouseBox(ctx, P, b);
      if (focused && S.place && S.mouse.in) {
        const x0 = r.gx * CELL + S.place.tx, y0 = r.gy * CELL + S.place.ty, pts = [G(x0, y0, 0.02), G(x0 + S.place.w, y0, 0.02), G(x0 + S.place.w, y0 + S.place.d, 0.02), G(x0, y0 + S.place.d, 0.02)];
        ctx.beginPath(); pts.forEach((p, i) => i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])); ctx.closePath();
        ctx.fillStyle = S.place.ok ? 'rgba(120,200,140,0.35)' : 'rgba(228,111,122,0.35)'; ctx.fill();
      }
      const boxes = roomBoxes(r);
      if (focused && S.place && S.place.ok && S.mouse.in) {
        for (const b of S.item.boxes) {
          const q = S.itemRot ? { ...b, x: b.y, y: b.x, w: b.d, d: b.w } : b;
          boxes.push({ ...q, x: q.x + r.gx * CELL + S.place.tx, y: q.y + r.gy * CELL + S.place.ty, ghost: true });
        }
        boxes.sort((p, q) => depthOf(p) - depthOf(q));
      }
      const guyHere = r === here, gd = g.x + g.y;
      let guyDrawn = false;
      const drawGuyNow = () => { guyDrawn = true; const [px, py] = G(g.x, g.y, 0); drawGuy(ctx, px, py, 1.15 * u / 60 * (0.55 + 0.45 * rise), { t, style: 'flat', walk: g.path.length > 0, dir: g.dir }); };
      for (const b of boxes) {
        if (guyHere && !guyDrawn && depthOf(b) > gd) drawGuyNow();
        if (b.ghost) { ctx.save(); ctx.globalAlpha *= 0.6; drawHouseBox(ctx, P, b); ctx.restore(); }
        else if (b.item && t - b.item.born < 0.35) {
          const q = easeBack((t - b.item.born) / 0.35);
          drawHouseBox(ctx, (x, y, z) => P(x, y, z * q), b);
        } else drawHouseBox(ctx, P, b);
      }
      if (guyHere && !guyDrawn) drawGuyNow();
      if (r.def.steam && rise > 0.6) {
        const [sx0, sy0, sz] = r.def.steam, [sx, sy] = P(r.gx * CELL + (r.rot ? sy0 : sx0), r.gy * CELL + (r.rot ? sx0 : sy0), sz);
        ctx.strokeStyle = 'rgba(255,255,255,0.85)'; ctx.lineWidth = 2;
        for (let i = 0; i < 2; i++) {
          ctx.beginPath();
          for (let q = 0; q < 6; q++) ctx.lineTo(sx + i * 10 + Math.sin(t * 2.4 + q + i) * 2.8, sy - q * u * 0.1 - ((t * 9) % 4));
          ctx.stroke();
        }
      }
      ctx.restore();
    }
    // labels
    S.doorTags = [];
    if (room) {
      const r = S.focus;
      for (const { d, other } of doorsOf(r, S.rooms)) {
        const [px, py] = G(d.gx * CELL, d.gy * CELL, 1.0), label = `${other.def.name} ▸`;
        ctx.save(); ctx.font = `700 15px ${HAND}`;
        const w = ctx.measureText(label).width + 18; ctx.restore();
        const tag = { room: other, x: clamp(px - w / 2, 6, W - w - 6), y: clamp(py - 11, 42, TRAY_Y - 26), w, h: 22 };
        S.doorTags.push(tag);
        pill(ctx, tag.x, tag.y, tag.w, tag.h, label, false, { size: 15, over: S.mouse.in && this.tagAt(S, S.mouse.x, S.mouse.y) === tag });
      }
      text(ctx, r.def.name, W / 2, 24, { size: 26 });
    } else {
      for (const r of S.rooms) {
        const [cx, cy] = roomCenter(r), [px, py] = G(cx, cy, 0.4);
        if (u * r.w * CELL > 46) text(ctx, r.def.name, px, py, { size: Math.min(18, 11 + u * 0.3), color: r.def.starry ? '#fff' : '#5d4d62' });
      }
      text(ctx, 'click a room to go in', W / 2, 24, { size: 18, color: '#8b7b8d' });
    }
    for (const b of S.btns) {
      if (b.id === 'follow' && !room) continue;
      const on = b.id === S.mode || (b.id === 'follow' && S.follow);
      pill(ctx, b.x, b.y, b.w, b.h, b.label, on, { over: S.mouse.in && this.btnAt(S, S.mouse.x, S.mouse.y) === b });
    }
    text(ctx, `spent $${S.spent}`, 14, 52, { size: 17, align: 'left', color: '#6d5d72' });

    // the tray
    ctx.fillStyle = 'rgba(255,250,243,0.92)'; ctx.fillRect(0, TRAY_Y, W, H - TRAY_Y);
    ctx.strokeStyle = '#e4d6c7'; ctx.lineWidth = 1.5; ctx.beginPath(); ctx.moveTo(0, TRAY_Y); ctx.lineTo(W, TRAY_Y); ctx.stroke();
    for (const c of S.chipList) (c.item ? this.drawItemChip : this.drawRoomChip).call(this, S, ctx, c);
  },
  drawItemChip(S, ctx, c) {
    const sel = S.item === c.item, over = S.mouse.in && this.chipAt(S, S.mouse.x, S.mouse.y) === c;
    roundRectPath(ctx, c.x, c.y, c.w, c.h, 10);
    ctx.fillStyle = sel ? '#fff' : (over ? '#fff8ef' : '#f8efe4'); ctx.fill();
    ctx.strokeStyle = sel ? '#e46f7a' : '#dccdbd'; ctx.lineWidth = sel ? 2.5 : 1.2; ctx.stroke();
    const [w, d] = footprint(c.item, sel && S.itemRot), iu = 13 / Math.max(1.4, Math.max(w, d) * 0.9), ax = c.x + 19, ay = c.y + 33 - (w + d) * iu * 0.25;
    const I = (x, y, z) => [ax + (x - y) * iu * 0.866, ay + (x + y) * iu * 0.5 - z * iu];
    for (const b of c.item.boxes.map(b => (sel && S.itemRot ? { ...b, x: b.y, y: b.x, w: b.d, d: b.w } : b)).sort((p, q) => depthOf(p) - depthOf(q))) drawHouseBox(ctx, I, b);
    text(ctx, c.item.name, c.x + c.w - 5, c.y + 15, { size: 14, align: 'right' });
    text(ctx, `$${c.item.price}`, c.x + c.w - 5, c.y + 37, { size: 17, align: 'right', color: '#e46f7a' });
  },
  drawRoomChip(S, ctx, c) {
    const owned = this.owned(S, c.def), sel = S.sel === c.def, over = S.mouse.in && this.chipAt(S, S.mouse.x, S.mouse.y) === c;
    ctx.save();
    ctx.globalAlpha = owned ? 0.42 : 1;
    roundRectPath(ctx, c.x, c.y, c.w, c.h, 10);
    ctx.fillStyle = sel ? '#fff' : (over && !owned ? '#fff8ef' : '#f8efe4'); ctx.fill();
    ctx.strokeStyle = sel ? '#e46f7a' : '#dccdbd'; ctx.lineWidth = sel ? 2.5 : 1.2; ctx.stroke();
    const iu = 9.5 * (c.def.w + c.def.h > 3 ? 0.68 : c.def.w + c.def.h === 3 ? 0.82 : 1), rw = (sel && S.rot ? c.def.h : c.def.w) * 2, rh = (sel && S.rot ? c.def.w : c.def.h) * 2, ax = c.x + 26 + (rh - rw) * iu * 0.22, ay = c.y + 21 - (rw + rh) * iu * 0.12;
    const I = (x, y, z) => [ax + (x - y) * iu * 0.5, ay + (x + y) * iu * 0.29 - z * iu * 0.6];
    isoBox(ctx, I, 0, 0, -0.5, rw, rh, 0.5, '#e2d0c4', { top: c.def.floor });
    isoBox(ctx, I, 0, -0.1, 0, rw, 0.1, 1.5, c.def.wall, { fl: c.def.wall, top: shade(c.def.wall, 0.3) });
    isoBox(ctx, I, -0.1, 0, 0, 0.1, rh, 1.5, c.def.wall, { fr: shade(c.def.wall, 0.08), top: shade(c.def.wall, 0.3) });
    text(ctx, c.def.name, c.x + c.w - 6, c.y + 16, { size: 15, align: 'right' });
    text(ctx, owned ? 'built' : `$${c.def.price}`, c.x + c.w - 6, c.y + 37, { size: 18, align: 'right', color: owned ? '#8b7b8d' : '#e46f7a' });
    ctx.restore();
  },
});
