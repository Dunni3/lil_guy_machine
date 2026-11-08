'use strict';
// The house as data: snap-on room modules made of boxes, with furniture on a tile grid. The isometric sketch
// and the 3D world both draw from this, so a room or a chair added in one is there in the other.

// A cell is 4 tiles inside. From outside the house is drawn at OUT_CELL, so a big interior doesn't make a mansion.
const WALL_H = 2.6, CELL = 4, OUT_CELL = 2, WALL_T = 0.14, SLAB = 0.4, WOOD_TRIM = '#d9bb98';
/** A box in tiles: x and y along the floor (y runs toward the viewer's left), z up. */
const bx = (x, y, z, w, d, h, c, top, glow) => ({ x, y, z, w, d, h, c, top, glow });

const WOOD_L = '#d7a982', WOOD_M = '#c9a27e', POT = '#e8957a', LEAFY = '#7fc28f', LEAFY_L = '#a8dcb0';

const ROOM_DEFS = [
  { id: 'bedroom', name: 'Bedroom', w: 2, h: 1, price: 0, floor: '#f3dccb', wall: '#f9cfdc', roof: '#e46f7a', boxes: [
    bx(0.15, 0.15, 0, 2.4, 1.5, 0.45, WOOD_L), bx(0.2, 0.28, 0.45, 0.55, 1.24, 0.16, '#fffdf8'), bx(0.8, 0.22, 0.45, 1.75, 1.36, 0.13, '#a9cdee'),
    bx(2.75, 0.15, 0, 0.7, 0.7, 0.6, WOOD_L), bx(2.95, 0.35, 0.6, 0.3, 0.3, 0.4, '#ffe3a1', null, 1),
    bx(5.6, 0.15, 0, 1.9, 0.8, 0.72, WOOD_M), bx(6.1, 0.2, 0.72, 0.9, 0.1, 0.55, '#2d2a40'), bx(6.2, 1.15, 0, 0.7, 0.7, 0.42, '#e46f7a'),
  ] },
  { id: 'kitchen', name: 'Kitchen', w: 2, h: 1, price: 90, floor: '#f6ead0', wall: '#ffe9b8', roof: '#d98a3f', boxes: [
    bx(0.15, 0.15, 0, 4.4, 0.85, 0.8, '#fffdf8', '#e9d2b6'), bx(1.0, 0.3, 0.8, 0.55, 0.5, 0.28, '#9aa7c7'), bx(3.0, 0.3, 0.8, 0.4, 0.4, 0.22, '#e46f7a'),
    bx(4.75, 0.15, 0, 0.95, 0.95, 2.0, '#dff0f6'),
    bx(2.4, 2.35, 0, 0.45, 0.45, 0.38, WOOD_L), bx(3.0, 2.0, 0, 1.6, 1.1, 0.62, '#e8b48a'), bx(3.6, 2.3, 0.62, 0.35, 0.35, 0.2, '#f8bfd0'),
  ], steam: [1.25, 0.55, 1.1] },
  { id: 'studio', name: 'Art nook', w: 1, h: 1, price: 60, floor: '#ded3ee', wall: '#cdbaf0', roof: '#7f6ab0', boxes: [
    bx(0.95, 0.5, 0, 0.1, 0.1, 1.3, '#a87a5a'), bx(1.95, 0.5, 0, 0.1, 0.1, 1.3, '#a87a5a'),
    bx(0.75, 0.56, 0.45, 1.5, 0.08, 1.15, '#fffdf8'), bx(0.95, 0.64, 0.7, 0.5, 0.03, 0.45, '#ff9fb4'), bx(1.45, 0.64, 0.95, 0.5, 0.03, 0.45, '#8fd0e6'),
    bx(2.9, 0.2, 0, 0.9, 0.7, 0.6, WOOD_M), bx(3.05, 0.3, 0.6, 0.18, 0.18, 0.24, '#ffe3a1'), bx(3.4, 0.5, 0.6, 0.18, 0.18, 0.3, LEAFY),
  ] },
  { id: 'bath', name: 'Bath', w: 1, h: 1, price: 45, floor: '#d3e6f2', wall: '#b8d7f4', roof: '#6f9fd0', boxes: [
    bx(0.2, 0.2, 0, 2.2, 1.3, 0.58, '#fffdf8', '#8fd0e6'), bx(3.05, 0.2, 0, 0.6, 0.6, 0.42, WOOD_L), bx(3.1, 0.25, 0.42, 0.5, 0.5, 0.16, '#f8bfd0'),
  ], steam: [1.2, 0.8, 0.7] },
  { id: 'library', name: 'Library', w: 1, h: 2, price: 120, floor: '#d9eadb', wall: '#b5e5cd', roof: '#4e9a83', boxes: (() => {
    const out = [bx(0.1, 0.3, 0, 0.55, 7.2, 2.2, '#a87a5a')], cs = ['#f8bfd0', '#b8d7f4', '#ffe3a1', '#cdbaf0', '#e46f7a'];
    for (let s = 0; s < 3; s++) for (let b = 0; b < 22; b++) out.push(bx(0.65, 0.4 + b * 0.32, 0.2 + s * 0.68, 0.03, 0.27, 0.5, cs[(b * 2 + s * 3) % 5]));
    out.push(bx(2.0, 5.2, 0, 1.2, 1.2, 0.5, '#e98f95'), bx(2.0, 6.2, 0.5, 1.2, 0.2, 0.55, '#e98f95'), bx(3.4, 0.5, 0, 0.12, 0.12, 1.8, '#4a3a4f'), bx(3.22, 0.32, 1.8, 0.48, 0.48, 0.35, '#ffe3a1', null, 1));
    return out;
  })() },
  { id: 'garden', name: 'Garden', w: 2, h: 2, price: 220, floor: '#bfe3b4', wall: '#d6ecf7', roof: '#9fd0e8', boxes: (() => {
    const out = [bx(0.8, 4.6, 0, 3.2, 2.4, 0.03, '#8fd0e6')];
    for (const [x, y, s] of [[0.5, 0.5, 1], [3, 0.5, 0.8], [5.6, 0.6, 1.1], [6.4, 3.8, 0.9], [4.6, 6.2, 1]]) {
      out.push(bx(x, y, 0, 0.6, 0.6, 0.42, POT), bx(x + 0.05, y + 0.05, 0.42, 0.5, 0.5, 0.46 * s, LEAFY), bx(x + 0.15, y + 0.15, 0.42 + 0.46 * s, 0.3, 0.3, 0.32 * s, LEAFY_L));
    }
    out.push(bx(3.6, 2.6, 0, 0.26, 0.26, 1.15, '#a87a5a'), bx(3.0, 2.0, 1.15, 1.46, 1.46, 0.6, LEAFY), bx(3.3, 2.3, 1.75, 0.86, 0.86, 0.45, LEAFY_L));
    return out;
  })() },
  { id: 'stars', name: 'Stars', w: 1, h: 1, price: 150, floor: '#45436e', wall: '#3a3866', win: '#2b2850', roof: '#3a3866', starry: true, boxes: [
    bx(0.7, 0.7, 0, 2.6, 2.6, 0.02, '#5b5890'),
    bx(1.5, 1.5, 0, 0.16, 0.16, 0.8, '#cfc7e0'), bx(1.3, 1.72, 0.8, 0.34, 0.24, 0.22, '#f8bfd0'), bx(1.48, 1.54, 1.0, 0.34, 0.24, 0.22, '#f8bfd0'), bx(1.66, 1.36, 1.2, 0.34, 0.24, 0.24, '#f8bfd0'),
    bx(2.8, 2.8, 0, 0.9, 0.8, 0.34, '#cdbaf0'),
  ] },
  { id: 'hall', name: 'Hallway', w: 1, h: 1, price: 15, floor: '#ecdcc8', wall: '#f3e6d3', roof: '#c9a27e', repeat: true, boxes: [
    bx(1.3, 0.4, 0, 1.4, 3.2, 0.02, '#e98f95'), bx(0.2, 0.2, 0, 0.5, 0.5, 0.38, POT), bx(0.25, 0.25, 0.38, 0.4, 0.4, 0.6, LEAFY),
  ] },
];
const ROOM_DEF = Object.fromEntries(ROOM_DEFS.map(d => [d.id, d]));

/** Furniture you place: a footprint in tiles and the boxes inside it. A flat one can go under other things. */
const ITEMS = [
  { id: 'rug', name: 'Rug', w: 3, d: 2, price: 12, flat: true, boxes: [bx(0.05, 0.05, 0, 2.9, 1.9, 0.03, '#cdbaf0'), bx(0.35, 0.35, 0.03, 2.3, 1.3, 0.01, '#f8bfd0')] },
  { id: 'plant', name: 'Plant', w: 1, d: 1, price: 8, boxes: [bx(0.22, 0.22, 0, 0.56, 0.56, 0.4, POT), bx(0.26, 0.26, 0.4, 0.48, 0.48, 0.5, LEAFY), bx(0.34, 0.34, 0.9, 0.32, 0.32, 0.34, LEAFY_L)] },
  { id: 'armchair', name: 'Armchair', w: 1, d: 1, price: 30, boxes: [bx(0.08, 0.08, 0, 0.84, 0.84, 0.42, '#e98f95'), bx(0.08, 0.08, 0.42, 0.84, 0.2, 0.5, '#e98f95')] },
  { id: 'lamp', name: 'Lamp', w: 1, d: 1, price: 14, boxes: [bx(0.3, 0.3, 0, 0.4, 0.4, 0.06, '#4a3a4f'), bx(0.45, 0.45, 0.06, 0.1, 0.1, 1.5, '#4a3a4f'), bx(0.26, 0.26, 1.5, 0.48, 0.48, 0.38, '#ffe3a1', null, 1)] },
  { id: 'table', name: 'Table', w: 2, d: 1, price: 22, boxes: [bx(0.1, 0.1, 0.5, 1.8, 0.8, 0.1, WOOD_L), bx(0.15, 0.15, 0, 0.1, 0.1, 0.5, WOOD_M), bx(1.75, 0.15, 0, 0.1, 0.1, 0.5, WOOD_M), bx(0.15, 0.75, 0, 0.1, 0.1, 0.5, WOOD_M), bx(1.75, 0.75, 0, 0.1, 0.1, 0.5, WOOD_M)] },
  { id: 'shelf', name: 'Shelf', w: 2, d: 1, price: 26, boxes: [bx(0.05, 0.1, 0, 1.9, 0.5, 1.7, '#a87a5a'), bx(0.15, 0.6, 0.2, 0.35, 0.03, 0.42, '#f8bfd0'), bx(0.55, 0.6, 0.2, 0.3, 0.03, 0.42, '#b8d7f4'), bx(0.95, 0.6, 0.2, 0.35, 0.03, 0.42, '#ffe3a1'), bx(0.3, 0.6, 0.95, 0.4, 0.03, 0.42, '#cdbaf0'), bx(1.05, 0.6, 0.95, 0.5, 0.03, 0.42, '#e46f7a')] },
  { id: 'records', name: 'Records', w: 1, d: 1, price: 40, boxes: [bx(0.1, 0.15, 0, 0.8, 0.7, 0.55, WOOD_M), bx(0.18, 0.23, 0.55, 0.64, 0.54, 0.05, '#2d2a40'), bx(0.34, 0.34, 0.6, 0.32, 0.32, 0.02, '#e46f7a')] },
  { id: 'beanbag', name: 'Beanbag', w: 1, d: 1, price: 18, boxes: [bx(0.12, 0.12, 0, 0.76, 0.76, 0.26, '#ffe3a1'), bx(0.24, 0.24, 0.26, 0.52, 0.52, 0.16, '#ffe3a1')] },
];
const ITEM = Object.fromEntries(ITEMS.map(i => [i.id, i]));

function makeRoom(def, gx, gy, rot = false) {
  return { def, gx, gy, rot, w: rot ? def.h : def.w, h: rot ? def.w : def.h, parent: null, door: null, born: -9, items: [] };
}
const cellRoom = (rooms, cx, cy) => rooms.find(r => cx >= r.gx && cx < r.gx + r.w && cy >= r.gy && cy < r.gy + r.h);
const roomCenter = r => [(r.gx + r.w / 2) * CELL, (r.gy + r.h / 2) * CELL];
const roomContains = (r, x, y) => x >= r.gx * CELL && x <= (r.gx + r.w) * CELL && y >= r.gy * CELL && y <= (r.gy + r.h) * CELL;

/** The wall two touching rooms share, as a door position in cells; null if they don't touch. */
function sharedDoor(a, b) {
  const yo = [Math.max(a.gy, b.gy), Math.min(a.gy + a.h, b.gy + b.h)], xo = [Math.max(a.gx, b.gx), Math.min(a.gx + a.w, b.gx + b.w)];
  if ((a.gx + a.w === b.gx || b.gx + b.w === a.gx) && yo[1] > yo[0]) return { gx: a.gx + a.w === b.gx ? b.gx : a.gx, gy: (yo[0] + yo[1]) / 2, v: true };
  if ((a.gy + a.h === b.gy || b.gy + b.h === a.gy) && xo[1] > xo[0]) return { gx: (xo[0] + xo[1]) / 2, gy: a.gy + a.h === b.gy ? b.gy : a.gy, v: false };
  return null;
}
/** Every spot a module could snap on: touching a room, overlapping nothing, inside the lot. */
function snapSpots(rooms, def, rot) {
  const w = rot ? def.h : def.w, h = rot ? def.w : def.h, spots = [];
  for (let gx = -3; gx + w <= 4; gx++) for (let gy = -3; gy + h <= 4; gy++) {
    if (rooms.some(r => gx < r.gx + r.w && r.gx < gx + w && gy < r.gy + r.h && r.gy < gy + h)) continue;
    const parent = rooms.find(r => sharedDoor(r, { gx, gy, w, h }));
    if (parent) spots.push({ gx, gy, parent, door: sharedDoor(parent, { gx, gy, w, h }) });
  }
  return spots;
}

/** A room's fixed furniture and placed items, as boxes in house coordinates. */
function roomBoxes(room) {
  const x0 = room.gx * CELL, y0 = room.gy * CELL, out = [];
  for (const b of room.def.boxes) {
    const q = room.rot ? { ...b, x: b.y, y: b.x, w: b.d, d: b.w } : b;
    out.push({ ...q, x: q.x + x0, y: q.y + y0 });
  }
  for (const it of room.items) for (const b of it.def.boxes) {
    const q = it.rot ? { ...b, x: b.y, y: b.x, w: b.d, d: b.w } : b;
    out.push({ ...q, x: q.x + x0 + it.tx, y: q.y + y0 + it.ty, item: it });
  }
  return out.sort((p, q) => depthOf(p) - depthOf(q));
}
/** Painter's order within a room; things flat on the floor always go first. */
const depthOf = b => (b.z + b.h <= 0.05 ? -1e6 : b.x + b.w / 2 + b.y + b.d / 2 + b.z * 0.1);

const doorsOf = (room, rooms) => rooms.filter(r => r.door && (r === room || r.parent === room)).map(r => ({ d: r.door, other: r === room ? r.parent : r }));
const footprint = (item, rot) => (rot ? [item.d, item.w] : [item.w, item.d]);
/** Whether an item fits at tile (tx, ty): inside the room, off other furniture, and clear of the doors. */
function canPlace(room, rooms, item, tx, ty, rot) {
  const [w, d] = footprint(item, rot), W = room.w * CELL, D = room.h * CELL;
  if (tx < 0 || ty < 0 || tx + w > W || ty + d > D) return false;
  const x0 = room.gx * CELL + tx, y0 = room.gy * CELL + ty;
  if (doorsOf(room, rooms).some(({ d: door }) => {
    const px = door.gx * CELL, py = door.gy * CELL;
    return px > x0 - 1 && px < x0 + w + 1 && py > y0 - 1 && py < y0 + d + 1;
  })) return false;
  if (item.flat) return true;
  return !roomBoxes(room).some(b => b.z + b.h > 0.05 && b.z < 0.5 && b.x < x0 + w && b.x + b.w > x0 && b.y < y0 + d && b.y + b.d > y0);
}
/** Somewhere he can stand in a room. */
function freeSpot(room) {
  const boxes = roomBoxes(room).filter(b => b.z + b.h > 0.05 && b.z < 0.5), x0 = room.gx * CELL, y0 = room.gy * CELL;
  for (let i = 0; i < 24; i++) {
    const x = x0 + 0.6 + Math.random() * (room.w * CELL - 1.2), y = y0 + 0.6 + Math.random() * (room.h * CELL - 1.2);
    if (!boxes.some(b => x > b.x - 0.45 && x < b.x + b.w + 0.45 && y > b.y - 0.45 && y < b.y + b.d + 0.45)) return [x, y];
  }
  return roomCenter(room);
}

/** Back walls of a room, cut around doors with a header over each, plus windows. */
function roomWalls(room, rooms) {
  const d = room.def, x0 = room.gx * CELL, y0 = room.gy * CELL, wc = d.wall, win = d.win || '#e3f2ff', out = [];
  const edge = (alongX) => {
    const from = alongX ? x0 : y0, to = from + (alongX ? room.w : room.h) * CELL;
    const doors = rooms.filter(r => r.door).map(r => r.door).filter(q => (alongX
      ? (!q.v && q.gy === room.gy && q.gx > room.gx && q.gx < room.gx + room.w)
      : (q.v && q.gx === room.gx && q.gy > room.gy && q.gy < room.gy + room.h))).map(q => (alongX ? q.gx : q.gy) * CELL).sort((p, q) => p - q);
    const wall = (a, b, z, h) => out.push(alongX
      ? { ...bx(a, y0 - WALL_T, z, b - a, WALL_T, h, wc), inner: 'y' }
      : { ...bx(x0 - WALL_T, a, z, WALL_T, b - a, h, wc), inner: 'x' });
    let a = alongX ? from - WALL_T : from;
    for (const u of doors) {
      wall(a, u - 0.55, -SLAB, WALL_H + SLAB);
      wall(u - 0.55, u + 0.55, 1.65, WALL_H - 1.65);
      if (alongX) out.push(bx(u - 0.62, y0 - WALL_T, 0, 0.1, WALL_T + 0.02, 1.65, WOOD_TRIM), bx(u + 0.52, y0 - WALL_T, 0, 0.1, WALL_T + 0.02, 1.65, WOOD_TRIM));
      else out.push(bx(x0 - WALL_T, u - 0.62, 0, WALL_T + 0.02, 0.1, 1.65, WOOD_TRIM), bx(x0 - WALL_T, u + 0.52, 0, WALL_T + 0.02, 0.1, 1.65, WOOD_TRIM));
      a = u + 0.55;
    }
    wall(a, to, -SLAB, WALL_H + SLAB);
    for (let c = from; c < to; c += CELL) {
      const mid = c + CELL / 2;
      if (doors.some(u => Math.abs(u - mid) < 1.5)) continue;
      out.push(alongX ? bx(mid - 0.7, y0, 1.0, 1.4, 0.02, 1.0, win, null, d.starry ? 0 : 0.8) : bx(x0, mid - 0.7, 1.0, 0.02, 1.4, 1.0, win, null, d.starry ? 0 : 0.8));
    }
    if (d.starry) for (let i = 0; i < 12; i++) {
      const u = from + hash2(i, alongX ? 41 : 43) * (to - from), z = 0.3 + hash2(i, 42) * 2.1;
      if (doors.some(q => Math.abs(q - u) < 0.7 && z < 1.7)) continue;
      out.push(alongX ? bx(u, y0, z, 0.06, 0.02, 0.06, '#fff0be', null, 1) : bx(x0, u, z, 0.02, 0.06, 0.06, '#fff0be', null, 1));
    }
  };
  edge(true); edge(false);
  return out;
}
function roomFloor(room) {
  return { ...bx(room.gx * CELL, room.gy * CELL, -SLAB, room.w * CELL, room.h * CELL, SLAB, '#eadbd0', room.def.floor), fl: '#e2d0c4', fr: '#cdb9ae' };
}

/** Rooms in painter's order: anything wholly behind another comes first. */
function backToFront(rooms) {
  const left = rooms.slice(), out = [];
  const before = (a, b) => a.gx + a.w <= b.gx || a.gy + a.h <= b.gy;
  while (left.length) {
    let i = left.findIndex(r => !left.some(o => o !== r && before(o, r) && !before(r, o)));
    if (i < 0) i = 0;
    out.push(left.splice(i, 1)[0]);
  }
  return out;
}

/** Waypoints from one room to another through the doors, in tiles. */
function housePath(from, to) {
  const up = r => { const a = []; for (; r; r = r.parent) a.push(r); return a; };
  const A = up(from), B = up(to), lca = A.find(r => B.includes(r));
  const chain = [...A.slice(0, A.indexOf(lca) + 1), ...B.slice(0, B.indexOf(lca)).reverse()], pts = [];
  for (let i = 1; i < chain.length; i++) {
    const a = chain[i - 1], b = chain[i], d = b.parent === a ? b.door : a.door;
    pts.push([d.gx * CELL, d.gy * CELL]);
  }
  return pts;
}

/** The way in from outside: the free front wall nearest the middle of the house, in cells. */
function houseEntry(rooms) {
  let best = null;
  for (const r of rooms) for (let i = 0; i < r.w; i++) {
    if (cellRoom(rooms, r.gx + i, r.gy + r.h)) continue;
    const cx = r.gx + i + 0.5, cy = r.gy + r.h, score = cy * 2 - Math.abs(cx - 1);
    if (!best || score > best.score) best = { cx, cy, room: r, score };
  }
  return best;
}

window.HOUSE = { rooms: [makeRoom(ROOM_DEF.bedroom, 0, 0)], version: 0 };
function houseChanged() { window.HOUSE.version++; window.dispatchEvent(new CustomEvent('house-changed')); }
