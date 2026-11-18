// A real 3D world in Three.js. The ground is a heightfield that repeats every L units, bent into a globe in
// the vertex shader around wherever he stands. Rooms come from house.js, the same data the isometric sketch draws.
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

const L = 96, HALF = L / 2, GRID = 48, CELL_W = L / GRID;
const wrap = v => ((v + HALF) % L + L) % L - HALF;
const wrapDelta = (a, b) => wrap(b - a);

const PLACES = {
  home: { name: 'Home', x: 2, z: 8, say: 'home sweet home', flat: 17, y: 1.2 },
  town: { name: 'Town', x: 31, z: -11, go: [31, -7.5], say: 'ooh, the shops', flat: 13, y: 1.5 },
  lake: { name: 'Lake', x: -22, z: 13, say: 'frogs?', tile: true },
  hill: { name: 'Lookout', x: -27, z: -27, say: 'you can see everything', tile: true },
  woods: { name: 'Woods', x: 27, z: 27, say: 'it smells like pine', tile: true },
  orchard: { name: 'Orchard', x: -3, z: 36, say: 'apples!', flat: 9, y: 1.1 },
  mill: { name: 'Windmill', x: 4, z: -30, say: 'round and round', flat: 7, y: 2.0 },
};
const SPOKES = ['town', 'lake', 'hill', 'woods', 'orchard', 'mill'];

// --- the land ---------------------------------------------------------------------------------

const TAU2 = Math.PI * 2;
const wave = (x, z, nx, nz, ph) => Math.cos(TAU2 * (nx * x + nz * z) / L + ph);
const gaussAt = (x, z, cx, cz, r) => { const dx = wrap(x - cx), dz = wrap(z - cz); return Math.exp(-(dx * dx + dz * dz) / (r * r)); };
const distAt = (x, z, cx, cz) => Math.hypot(wrap(x - cx), wrap(z - cz));
function heightAt(x, z) {
  let h = 1.5 + 0.7 * wave(x, z, 1, 2, 0.4) + 0.5 * wave(x, z, 3, -1, 2.1) + 0.28 * wave(x, z, 4, 3, 1.1) + 0.14 * wave(x, z, 7, -5, 3.2);
  h += 10.5 * gaussAt(x, z, -27, -27, 13);
  h += 2.2 * gaussAt(x, z, 34, 30, 15);
  h -= 6.2 * gaussAt(x, z, -24, 18, 10.5) + 2.5 * gaussAt(x, z, -14, 22, 6);
  for (const p of Object.values(PLACES)) {
    if (!p.flat) continue;
    const k = ease(1 - (distAt(x, z, p.x, p.z) - p.flat * 0.55) / (p.flat * 0.75));
    h = lerp(h, p.y, k);
  }
  return h;
}
function distToSeg(x, z, a, b) {
  const dxb = wrapDelta(a.x, b.x), dzb = wrapDelta(a.z, b.z), dxp = wrap(x - a.x), dzp = wrap(z - a.z);
  const u = clamp((dxp * dxb + dzp * dzb) / (dxb * dxb + dzb * dzb), 0, 1);
  return Math.hypot(dxp - dxb * u, dzp - dzb * u);
}
const onPath = (x, z) => SPOKES.some(k => distToSeg(x, z, PLACES.home, PLACES[k]) < 1.25);

// --- parts ------------------------------------------------------------------------------------

const col = c => new THREE.Color(c);
function paint(geo, color, glow = 0) {
  const g = geo.index ? geo.toNonIndexed() : geo;
  g.deleteAttribute('uv');
  const n = g.attributes.position.count, c = col(color), a = new Float32Array(n * 3), gl = new Float32Array(n);
  for (let i = 0; i < n; i++) { a[3 * i] = c.r; a[3 * i + 1] = c.g; a[3 * i + 2] = c.b; gl[i] = glow; }
  g.setAttribute('aColor', new THREE.BufferAttribute(a, 3));
  g.setAttribute('aGlow', new THREE.BufferAttribute(gl, 1));
  return g;
}
/** The inverted-hull outline, as in tools/workshop/guy.html: the part pushed out along its smoothed normals. */
function hullOf(geo, th) {
  const g = geo.clone(), p = g.attributes.position, nrm = g.attributes.normal, acc = new Map();
  const key = i => `${p.getX(i).toFixed(3)},${p.getY(i).toFixed(3)},${p.getZ(i).toFixed(3)}`;
  for (let i = 0; i < p.count; i++) {
    const k = key(i);
    if (!acc.has(k)) acc.set(k, new THREE.Vector3());
    acc.get(k).add(new THREE.Vector3(nrm.getX(i), nrm.getY(i), nrm.getZ(i)));
  }
  const moved = new Float32Array(p.count * 3);
  for (let i = 0; i < p.count; i++) {
    const v = acc.get(key(i)).clone().normalize();
    moved[3 * i] = p.getX(i) + v.x * th; moved[3 * i + 1] = p.getY(i) + v.y * th; moved[3 * i + 2] = p.getZ(i) + v.z * th;
  }
  g.setAttribute('position', new THREE.BufferAttribute(moved, 3));
  return g;
}
class Parts {
  constructor() { this.solid = []; this.hull = []; }
  add(geo, color, o = {}) {
    const g = paint(geo, color, o.glow || 0);
    this.solid.push(g);
    if (o.hull !== false) this.hull.push(hullOf(g, o.th || 0.03));
    return this;
  }
  /** A box given as x, z on the ground and y up, with its own colour on top. */
  box(x, y, z, w, h, d, color, o = {}) {
    const g = paint(new THREE.BoxGeometry(w, h, d).translate(x + w / 2, y + h / 2, z + d / 2), color, o.glow || 0);
    if (o.top) {
      const a = g.attributes.aColor, c = col(o.top);
      for (let i = 12; i < 18; i++) a.setXYZ(i, c.r, c.g, c.b);
    }
    this.solid.push(g);
    if (o.hull !== false) this.hull.push(hullOf(g, o.th || 0.03));
    return this;
  }
  build() { return { solid: this.solid.length ? mergeGeometries(this.solid) : null, hull: this.hull.length ? mergeGeometries(this.hull) : null }; }
}
function lump(radius, seed, detail = 1, amp = 0.14) {
  const g = new THREE.IcosahedronGeometry(radius, detail), p = g.attributes.position, seen = new Map();
  for (let i = 0; i < p.count; i++) {
    const k = `${p.getX(i).toFixed(3)},${p.getY(i).toFixed(3)},${p.getZ(i).toFixed(3)}`;
    if (!seen.has(k)) seen.set(k, 1 + (hash2(seen.size, seed, 3) - 0.5) * 2 * amp);
    const s = seen.get(k);
    p.setXYZ(i, p.getX(i) * s, p.getY(i) * s, p.getZ(i) * s);
  }
  g.computeVertexNormals();
  return g;
}

const pine = (P, x, z, s) => {
  const y = heightAt(x, z) - 0.05;
  P.add(new THREE.CylinderGeometry(0.11 * s, 0.17 * s, 0.75 * s, 6).translate(x, y + 0.37 * s, z), '#8a6242');
  [[0.95, 1.2, 0.95], [0.75, 1.05, 1.6], [0.5, 0.9, 2.2]].forEach(([r, h, oy], i) =>
    P.add(new THREE.ConeGeometry(r * s, h * s, 7).translate(x, y + oy * s, z), i % 2 ? '#5fa877' : '#4f9a6c'));
};
const roundTree = (P, x, z, s, leaf = '#9fd3a7', fruit) => {
  const y = heightAt(x, z) - 0.05, seed = Math.floor(x * 7 + z * 13);
  P.add(new THREE.CylinderGeometry(0.13 * s, 0.2 * s, 1.1 * s, 6).translate(x, y + 0.55 * s, z), '#a87a5a');
  P.add(lump(0.95 * s, seed).translate(x, y + 1.75 * s, z), leaf);
  if (fruit) for (let i = 0; i < 7; i++) {
    const a = i * 2.4, b = 0.4 + (i % 3) * 0.3;
    P.add(new THREE.IcosahedronGeometry(0.1 * s, 0).translate(x + Math.cos(a) * b * s, y + (1.45 + (i % 4) * 0.22) * s, z + Math.sin(a) * b * s + 0.55 * s), fruit, { hull: false });
  }
};
function building(P, x, z, w, d, h, c, roof, o = {}) {
  const y = (o.y ?? heightAt(x, z)) - 0.1;
  P.box(x - w / 2, y, z - d / 2, w, h + 0.1, d, c);
  const rise = Math.min(w, d) * 0.42, hw = w / 2 + 0.28, hd = d / 2 + 0.28, ry = y + h + 0.1;
  P.add(prism(x, z, hw, hd, ry, rise), roof);
  P.box(x - 0.42, y, z + d / 2 - 0.03, 0.84, 1.65, 0.08, shade(c, -0.35), { hull: false });
  for (const sx of [-1, 1]) {
    const px = x + sx * (w / 4 + 0.3);
    if (w > 2.6) P.box(px - 0.32, y + 1.1, z + d / 2 - 0.02, 0.64, 0.7, 0.05, '#fff0bc', { glow: 1, hull: false });
  }
  if (o.awning) for (let i = 0; i < 6; i++) {
    const aw = (w - 0.3) / 6;
    P.box(x - w / 2 + 0.15 + i * aw, y + 1.85, z + d / 2, aw, 0.08, 0.6, i % 2 ? '#fffdf8' : o.awning, { hull: false });
  }
}
/** A gabled roof over a rectangle, with the ridge along its long side. */
function prism(x, z, hw, hd, y, rise) {
  const alongX = hw >= hd;
  const A = [x - hw, y, z - hd], B = [x + hw, y, z - hd], C = [x + hw, y, z + hd], D = [x - hw, y, z + hd];
  const R1 = alongX ? [x - hw, y + rise, z] : [x, y + rise, z - hd], R2 = alongX ? [x + hw, y + rise, z] : [x, y + rise, z + hd];
  const tris = alongX ? [D, C, R2, D, R2, R1, B, A, R1, B, R1, R2, A, D, R1, C, B, R2] : [A, D, R1, D, R2, R1, C, B, R2, B, R1, R2, D, C, R2, B, A, R1];
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(tris.flat()), 3));
  g.computeVertexNormals();
  return g;
}

function buildScenery() {
  const P = new Parts(), blocked = [];
  const r = rng(11);
  const free = (x, z, rad = 2.5) => heightAt(x, z) > 0.5 && !onPath(x, z) && !Object.values(PLACES).some(p => p.flat && distAt(x, z, p.x, p.z) < p.flat * 0.72) && blocked.every(b => distAt(x, z, b[0], b[1]) > rad);
  const mark = (x, z, rad = 1) => blocked.push([x, z, rad]);
  // the woods
  for (let i = 0; i < 90; i++) {
    const a = r() * TAU2, d = Math.sqrt(r()) * 17, x = wrap(27 + Math.cos(a) * d), z = wrap(27 + Math.sin(a) * d);
    if (free(x, z, 1.7)) { pine(P, x, z, 0.85 + r() * 0.8); mark(x, z); }
  }
  // the rest of the country
  for (let i = 0; i < 260; i++) {
    const x = (r() - 0.5) * L, z = (r() - 0.5) * L;
    if (distAt(x, z, 27, 27) < 15 || !free(x, z, 4)) continue;
    const k = r();
    if (k < 0.45) { roundTree(P, x, z, 0.85 + r() * 0.7, ['#9fd3a7', '#b5dca0', '#8fcb9c'][Math.floor(r() * 3)]); mark(x, z); }
    else if (k < 0.65) { P.add(lump(0.55 + r() * 0.5, i).translate(x, heightAt(x, z) + 0.3, z), '#8fcb9c'); mark(x, z, 0.6); }
    else if (k < 0.85) { P.add(lump(0.35 + r() * 0.55, i, 0, 0.2).translate(x, heightAt(x, z) + 0.12, z), '#b8b2c2'); mark(x, z, 0.5); }
  }
  // flowers
  for (let i = 0; i < 420; i++) {
    const x = (r() - 0.5) * L, z = (r() - 0.5) * L, y = heightAt(x, z);
    if (y < 0.6 || onPath(x, z) || distAt(x, z, 0, 0) < 12) continue;
    P.add(new THREE.OctahedronGeometry(0.11, 0).translate(x, y + 0.15, z), ['#f8bfd0', '#ffe3a1', '#cdbaf0', '#fffdf8', '#ff9fb4'][i % 5], { hull: false });
  }
  // town: a little square of shops
  const T = PLACES.town;
  [[-5.5, -5, 5, 4, 3.2, '#f8bfd0', '#a86f92', '#e46f7a'], [1, -5.5, 4.4, 3.6, 4.2, '#cdbaf0', '#6f5a9e'], [6.8, -3.5, 4, 4, 3, '#b5e5cd', '#4e9a83', '#4e9a83'], [-6.5, 3.8, 4, 4, 2.8, '#ffd8c0', '#d98a3f', '#d98a3f'], [6.6, 4.5, 4.4, 3.6, 3.6, '#b8d7f4', '#6f9fd0']]
    .forEach(([dx, dz, w, d, h, c, roof, awn]) => { building(P, T.x + dx, T.z + dz, w, d, h, c, roof, { awning: awn, y: T.y }); mark(wrap(T.x + dx), wrap(T.z + dz), Math.max(w, d) * 0.5); });
  P.add(new THREE.CylinderGeometry(1.3, 1.5, 0.5, 12).translate(T.x, T.y + 0.25, T.z), '#c6c0cc');
  P.add(new THREE.CylinderGeometry(1.1, 1.1, 0.06, 12).translate(T.x, T.y + 0.5, T.z), '#8fd0e6', { hull: false });
  mark(wrap(T.x), wrap(T.z), 1.2);
  for (const [dx, dz] of [[-3, 1], [3, 1], [0, 6], [-10, -1], [-14, 3]]) {
    const x = T.x + dx, z = T.z + dz, y = heightAt(x, z);
    P.add(new THREE.CylinderGeometry(0.06, 0.08, 2.3, 6).translate(x, y + 1.15, z), '#4a3a4f');
    P.add(new THREE.IcosahedronGeometry(0.24, 1).translate(x, y + 2.42, z), '#ffe28a', { glow: 1 });
  }
  // the lookout on the hill
  const hx = PLACES.hill.x + 1.8, hz = PLACES.hill.z - 1.8, hy = heightAt(hx, hz);
  P.add(new THREE.CylinderGeometry(0.75, 1.0, 3.8, 8).translate(hx, hy + 1.9, hz), '#fff3e2');
  P.add(new THREE.ConeGeometry(1.1, 1.3, 8).translate(hx, hy + 4.4, hz), '#e46f7a');
  P.add(new THREE.CylinderGeometry(0.03, 0.03, 1.2, 5).translate(hx, hy + 5.4, hz), '#4a3a4f');
  P.box(hx, hy + 5.5, hz - 0.02, 0.62, 0.34, 0.04, '#ffe3a1');
  mark(wrap(hx), wrap(hz), 1.5);
  // the dock
  const dx0 = -14.5, dz0 = 13.5, dy = 0.55;
  for (let i = 0; i < 9; i++) P.box(dx0 - i * 0.62, dy, dz0 - 0.7, 0.56, 0.1, 1.4, i % 2 ? '#c9a27e' : '#b98560', { hull: false });
  for (const i of [2, 5, 8]) for (const s of [-0.65, 0.6]) P.box(dx0 - i * 0.62, -0.4, dz0 + s, 0.12, 1.0, 0.12, '#8a6242', { hull: false });
  // the orchard, in rows, inside a fence
  const O = PLACES.orchard;
  for (let i = -2; i <= 2; i++) for (let j = -1; j <= 1; j++) {
    if (i === 0) continue;
    const x = O.x + i * 2.6, z = O.z + j * 2.7;
    roundTree(P, x, z, 0.82, '#a8d8a0', '#e46f7a'); mark(wrap(x), wrap(z));
  }
  // the windmill's tower (its sails turn, so they're their own mesh)
  const M = PLACES.mill;
  P.add(new THREE.CylinderGeometry(1.0, 1.7, 5, 8).translate(M.x, M.y + 2.5, M.z - 2.5), '#fff3e2');
  P.add(new THREE.ConeGeometry(1.2, 1.2, 8).translate(M.x, M.y + 5.6, M.z - 2.5), '#c97b7f');
  mark(wrap(M.x), wrap(M.z - 2.5), 2);
  for (let i = -3; i <= 3; i++) { P.box(M.x - 11 + i * 1.3, heightAt(M.x - 11, M.z) + 0.02, M.z - 3, 0.9, 0.42, 6, i % 2 ? '#e6cf7f' : '#d9bf6a', { hull: false }); }

  // clouds
  const C = new Parts();
  for (let i = 0; i < 9; i++) {
    const x = (r() - 0.5) * L, z = (r() - 0.5) * L, y = 15 + r() * 6;
    for (let k = 0; k < 4; k++) C.add(lump(1.3 + r() * 1.5, i * 7 + k, 1, 0.1).scale(1.3, 0.72, 1).translate(x + (k - 1.5) * 1.8, y + r() * 0.5, z + (r() - 0.5) * 1.5), '#fffdf8', { hull: false });
  }
  return { scene: P.build(), clouds: C.build(), blocked };
}

function buildTerrain() {
  const g = new THREE.PlaneGeometry(L, L, 96, 96).rotateX(-Math.PI / 2), p = g.attributes.position, n = g.attributes.normal;
  g.setAttribute('aColor', new THREE.BufferAttribute(new Float32Array(p.count * 3), 3));
  g.setAttribute('aGlow', new THREE.BufferAttribute(new Float32Array(p.count), 1));
  for (let i = 0; i < p.count; i++) {
    const x = p.getX(i), z = p.getZ(i);
    p.setY(i, heightAt(x, z));
    const e = 0.25, nx = heightAt(x - e, z) - heightAt(x + e, z), nz = heightAt(x, z - e) - heightAt(x, z + e), len = Math.hypot(nx, 2 * e, nz);
    n.setXYZ(i, nx / len, 2 * e / len, nz / len);
  }
  return g;
}

/** The ground's paint, baked once into a repeating texture: grass, sand, rock and thin dirt paths. */
function buildGroundTexture() {
  const N = 512, data = new Uint8Array(N * N * 4), rgb = hex => hexRgb(hex);
  const grassA = rgb('#bfe3b4'), grassB = rgb('#a9d8a6'), grassC = rgb('#cbe6ad'), sand = rgb('#f2dcae'), mud = rgb('#c8c0a8'), path = rgb('#efcfa8'), rock = rgb('#d2ccd8');
  const blend = (a, b, u) => a.map((v, i) => lerp(v, b[i], clamp(u, 0, 1)));
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const x = (i + 0.5) / N * L - HALF, z = (j + 0.5) / N * L - HALF, h = heightAt(x, z);
    let c = blend(grassA, grassB, 0.5 + 0.5 * wave(x, z, 5, 3, 0.6) * wave(x, z, -2, 6, 1.9));
    c = blend(c, grassC, (0.5 + 0.5 * wave(x, z, 2, -3, 1.0)) * 0.5);
    if (h > 6) c = blend(c, rock, (h - 6) / 2.5);
    if (h < 0.95) c = blend(c, sand, (0.95 - h) / 0.15);
    if (h < 0.1) c = blend(c, mud, (0.1 - h) / 0.2);
    if (h > 0.7) {
      let d = 9;
      for (const k of SPOKES) d = Math.min(d, distToSeg(x, z, PLACES.home, PLACES[k]));
      c = blend(c, path, (1.0 + 0.16 * wave(x, z, 13, 7, 0.3) - d) / 0.22);
    }
    const speck = hash2(i, j, 9), tone = 1 + (speck - 0.5) * 0.045, n = (j * N + i) * 4;
    data[n] = clamp(c[0] * tone, 0, 255); data[n + 1] = clamp(c[1] * tone, 0, 255); data[n + 2] = clamp(c[2] * tone, 0, 255); data[n + 3] = 255;
  }
  const tex = new THREE.DataTexture(data, N, N, THREE.RGBAFormat);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.magFilter = THREE.LinearFilter; tex.minFilter = THREE.LinearMipmapLinearFilter; tex.generateMipmaps = true; tex.anisotropy = 4;
  tex.needsUpdate = true;
  return tex;
}

// --- the house, from house.js -----------------------------------------------------------------

function addHouseBox(P, b) {
  P.box(b.x, b.z, b.y, b.w, b.h, b.d, b.c, { top: b.top, glow: b.glow ? 1 : 0, hull: false });
}
/** Inside, a room apart: the same boxes the isometric sketch draws, with what stands up separate so it can flatten. */
function buildRoomInterior(room, rooms) {
  const Floor = new Parts(), Up = new Parts();
  addHouseBox(Floor, roomFloor(room));
  roomWalls(room, rooms).forEach(b => addHouseBox(Up, b));
  roomBoxes(room).forEach(b => addHouseBox(Up, b));
  return { room, floor: Floor.build().solid, up: Up.build().solid };
}
/** Outside: every wall with no room against it, windows, and a roof per room, at the smaller outside scale. */
function buildHouse(rooms) {
  const CELL = OUT_CELL, Out = new Parts(), W_ = WALL_H, y = PLACES.home.y;
  for (const r of rooms) {
    const x0 = r.gx * CELL, z0 = r.gy * CELL, x1 = (r.gx + r.w) * CELL, z1 = (r.gy + r.h) * CELL, c = r.def.wall;
    Out.box(x0, y - 0.45, z0, x1 - x0, 0.45, z1 - z0, '#d9c7b8', { hull: true, top: r.def.floor });
    const side = (cx, cz, wall, win, sx, sz) => {
      if (cellRoom(rooms, cx, cz)) return;
      Out.box(wall[0], y, wall[1], wall[2], W_, wall[3], c, { hull: true });
      Out.box(win[0], y + 0.95, win[1], win[2], 0.85, win[3], r.def.starry ? '#2b2850' : '#fff0bc', { glow: r.def.starry ? 0 : 1 });
    };
    for (let i = 0; i < r.w; i++) {
      const a = x0 + i * CELL;
      side(r.gx + i, r.gy - 1, [a, z0, CELL, WALL_T], [a + 0.55, z0 - 0.03, 0.9, 0.04]);
      side(r.gx + i, r.gy + r.h, [a, z1 - WALL_T, CELL, WALL_T], [a + 0.55, z1 - 0.01, 0.9, 0.04]);
    }
    for (let i = 0; i < r.h; i++) {
      const a = z0 + i * CELL;
      side(r.gx - 1, r.gy + i, [x0, a, WALL_T, CELL], [x0 - 0.03, a + 0.55, 0.04, 0.9]);
      side(r.gx + r.w, r.gy + i, [x1 - WALL_T, a, WALL_T, CELL], [x1 - 0.01, a + 0.55, 0.04, 0.9]);
    }
    Out.add(prism((x0 + x1) / 2, (z0 + z1) / 2, (x1 - x0) / 2 + 0.2, (z1 - z0) / 2 + 0.2, y + W_, Math.min(x1 - x0, z1 - z0) * 0.32), r.def.roof);
  }
  const way = houseEntry(rooms), e = { x: way.cx * CELL, y: way.cy * CELL, room: way.room };
  Out.box(e.x - 0.45, y, e.y - 0.02, 0.9, 1.7, 0.08, '#e46f7a', { hull: true });
  Out.box(e.x - 1.0, y - 0.28, e.y, 2.0, 0.3, 1.2, '#d9c7b8', { hull: false });
  return { outside: Out.build(), entry: e };
}

// --- shaders ----------------------------------------------------------------------------------

const BEND = /* glsl */`
  uniform vec3 uC; uniform float uK;
  varying vec3 vN; varying vec3 vP; varying vec3 vColor; varying float vGlow; varying float vR; varying float vDist;
  attribute vec3 aColor; attribute float aGlow;
  vec3 bendPoint(vec3 w, out float a, out vec2 dir, out float r) {
    vec2 d = w.xz - uC.xz; r = length(d); dir = r > 1e-4 ? d / r : vec2(1.0, 0.0); a = r * uK;
    if (uK < 1e-4) { a = 0.0; return w; }
    float R = 1.0 / uK, rad = R + w.y;
    return vec3(uC.x + dir.x * rad * sin(a), rad * cos(a) - R, uC.z + dir.y * rad * sin(a));
  }`;
const VERT = /* glsl */`${BEND}
  void main() {
    vec4 w = modelMatrix * vec4(position, 1.0);
    float a, r; vec2 dir;
    vec3 b = bendPoint(w.xyz, a, dir, r);
    vec3 n = mat3(modelMatrix) * normal;
    float nr = dot(n.xz, dir);
    vec2 tang = n.xz - dir * nr;
    float nr2 = nr * cos(a) + n.y * sin(a), ny2 = -nr * sin(a) + n.y * cos(a);
    vN = vec3(tang.x + dir.x * nr2, ny2, tang.y + dir.y * nr2);
    vP = w.xyz; vColor = aColor; vGlow = aGlow; vR = r;
    vec4 mv = viewMatrix * vec4(b, 1.0);
    vDist = -mv.z;
    gl_Position = projectionMatrix * mv;
  }`;
const FRAG_COMMON = /* glsl */`
  uniform vec3 uLight; uniform vec3 uTint; uniform vec3 uShade; uniform vec3 uInk; uniform vec3 uHaze;
  uniform float uCrayon; uniform float uBoil; uniform float uNight; uniform float uTime; uniform float uK;
  varying vec3 vN; varying vec3 vP; varying vec3 vColor; varying float vGlow; varying float vR; varying float vDist;
  // Fades to the sky with distance, and cuts off past the globe's horizon so nothing from its far side pokes out.
  float hazeAt() {
    if (vR * uK > 1.72) discard;
    return max(smoothstep(30.0, 47.0, vR), smoothstep(1.5, 1.72, vR * uK));
  }
  vec3 shadeIt(vec3 base) {
    vec3 n = normalize(vN);
    float ndl = dot(n, uLight);
    vec3 clay = base * mix(uShade, vec3(1.0), smoothstep(-0.2, 0.7, ndl));
    float wob = 0.08 * sin(vP.x * 9.0 + uBoil * 2.1) * sin(vP.y * 11.0 - uBoil * 1.3 + vP.z * 7.0);
    float lit = smoothstep(-0.03, 0.03, ndl + 0.1 + wob);
    vec3 cr = mix(base * uShade, base, lit);
    float near = 1.0 - smoothstep(9.0, 26.0, vDist);
    float mid = 1.0 - smoothstep(-0.03, 0.03, ndl - 0.42 + wob);
    float deep = 1.0 - smoothstep(-0.03, 0.03, ndl + 0.25 + wob);
    float h1 = smoothstep(0.3, 0.75, sin(dot(vP, vec3(1.0, 0.85, 0.35)) * 26.0 + uBoil * 2.0));
    float h2 = smoothstep(0.35, 0.8, sin(dot(vP, vec3(-0.9, 1.0, 0.2)) * 22.0 - uBoil * 1.7));
    cr = mix(cr, uInk, clamp((h1 * (0.16 * mid + 0.34 * (1.0 - lit)) + h2 * 0.38 * deep) * near, 0.0, 0.7));
    vec3 c = mix(clay, cr, uCrayon) * uTint;
    c = mix(c, vec3(1.0, 0.72, 0.36), vGlow * uNight * 0.92);
    float g = fract(sin(dot(floor(gl_FragCoord.xy / 1.5), vec2(12.9898, 78.233))) * 43758.5453);
    c *= 0.975 + 0.05 * g * (0.4 + 0.6 * uCrayon);
    return mix(c, uHaze, hazeAt());
  }`;
const FRAG = /* glsl */`${FRAG_COMMON}
  void main() { gl_FragColor = vec4(shadeIt(vColor), 1.0);
    #include <colorspace_fragment>
  }`;
const GROUND_FRAG = /* glsl */`${FRAG_COMMON}
  uniform sampler2D uGround;
  void main() {
    gl_FragColor = vec4(shadeIt(texture2D(uGround, (vP.xz + ${HALF.toFixed(1)}) / ${L.toFixed(1)}).rgb), 1.0);
    #include <colorspace_fragment>
  }`;
const HULL_FRAG = /* glsl */`${FRAG_COMMON}
  void main() { gl_FragColor = vec4(mix(uInk * (0.45 + 0.55 * uTint), uHaze, hazeAt()), 1.0);
    #include <colorspace_fragment>
  }`;
const WATER_FRAG = /* glsl */`${FRAG_COMMON}
  void main() {
    float r1 = sin(vP.x * 2.1 + uTime * 1.3 + 2.0 * sin(vP.z * 0.8 + uTime * 0.4));
    float r2 = sin(vP.z * 2.7 - uTime * 1.1 + 2.0 * sin(vP.x * 0.6 - uTime * 0.3));
    vec3 c = mix(vec3(0.28, 0.56, 0.78), vec3(0.45, 0.75, 0.9), 0.5 + 0.25 * (r1 + r2));
    c = mix(c, vec3(0.92, 0.97, 1.0), smoothstep(1.55, 1.95, r1 + r2) * 0.75);
    gl_FragColor = vec4(mix(c * uTint, uHaze, hazeAt()), 1.0);
    #include <colorspace_fragment>
  }`;
const SKY_VERT = /* glsl */`varying vec3 vD; void main() { vD = normalize(position); gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`;
const SKY_FRAG = /* glsl */`
  uniform vec3 uTop; uniform vec3 uLow; uniform vec3 uSun; uniform float uNight; uniform float uTime; varying vec3 vD;
  void main() {
    vec3 d = normalize(vD);
    vec3 c = mix(uLow, uTop, smoothstep(-0.1, 0.5, d.y));
    float sd = dot(d, uSun);
    c += mix(vec3(1.0, 0.86, 0.55), vec3(0.75, 0.75, 0.6), uNight) * (smoothstep(0.9975, 0.9985, sd) + 0.2 * pow(max(sd, 0.0), 40.0));
    vec3 cell = floor(d * 150.0);
    float s = fract(sin(dot(cell, vec3(12.9, 78.2, 37.7))) * 43758.5);
    c += uNight * smoothstep(0.994, 1.0, s) * (0.5 + 0.5 * sin(uTime * 2.0 + s * 90.0)) * vec3(1.0, 0.96, 0.82);
    gl_FragColor = vec4(c, 1.0);
    #include <colorspace_fragment>
  }`;

// --- the guy ----------------------------------------------------------------------------------

function buildGuy(mat, hullMat, U) {
  const root = new THREE.Group(), R = 0.3, sc = 1.42;
  root.scale.setScalar(sc);
  const part = (geo, color, hull = true, th = 0.022) => {
    const g = paint(geo, color), m = new THREE.Mesh(g, mat);
    m.frustumCulled = false;
    const grp = new THREE.Group(); grp.add(m);
    if (hull) { const h = new THREE.Mesh(hullOf(g, th), hullMat); h.frustumCulled = false; grp.add(h); }
    return grp;
  };
  const sphere = (x, y, z, s = 1) => new THREE.SphereGeometry(1, 18, 14).scale(x * s, y * s, z * s);
  const body = part(lump(1, 5, 4, 0.03).scale(R * 1.14, R * 0.9, R * 0.96), '#ffe3a1');
  const arms = [-1, 1].map(() => part(sphere(0.055, 0.085, 0.06), '#ffdd95'));
  const feet = [-1, 1].map(() => part(sphere(0.09, 0.05, 0.11), '#fbd487'));
  const face = new THREE.Group();
  const eyes = [-1, 1].map(s => {
    const e = part(sphere(0.032, 0.045, 0.018), '#4a3a4f', false);
    e.position.set(s * 0.1, 0.04, 0.267);
    const hi = part(sphere(0.011, 0.011, 0.006), '#ffffff', false); hi.position.set(0.01, 0.014, 0.016); e.add(hi);
    face.add(e);
    return e;
  });
  for (const s of [-1, 1]) { const b = part(sphere(0.046, 0.028, 0.012), '#ff9fb4', false); b.position.set(s * 0.19, -0.022, 0.222); b.rotation.y = s * 0.6; face.add(b); }
  const mouth = part(new THREE.TorusGeometry(0.03, 0.008, 6, 12, Math.PI).rotateZ(Math.PI), '#b05a6e', false); mouth.position.set(0, 0.002, 0.287); face.add(mouth);
  body.add(face);
  const sprout = new THREE.Group();
  sprout.add(part(new THREE.CylinderGeometry(0.009, 0.011, 0.11, 5).translate(0, 0.055, 0), '#8ec79c'));
  for (const [x, y, rot] of [[-0.05, 0.12, 0.5], [0.06, 0.135, -0.45]]) {
    const leaf = part(sphere(0.06, 0.026, 0.035), '#a8dcb0'); leaf.position.set(x, y, 0); leaf.rotation.z = rot; sprout.add(leaf);
  }
  root.add(body, sprout, ...arms, ...feet);
  const shadow = new THREE.Mesh(paint(new THREE.CircleGeometry(0.42, 18).rotateX(-Math.PI / 2), '#3d2f4a'), new THREE.ShaderMaterial({
    vertexShader: VERT, fragmentShader: /* glsl */`varying vec3 vColor; void main() { gl_FragColor = vec4(vColor, 0.2);
    #include <colorspace_fragment>
    }`, uniforms: U, transparent: true, depthWrite: false,
  }));
  shadow.frustumCulled = false;
  return {
    root, shadow,
    pose(t, walking, yaw, x, y, z) {
      const ph = t * 11; let squash = 1 + 0.025 * Math.sin(t * 2.3);
      if (walking) squash -= 0.07 * Math.cos(2 * ph);
      const sx = 1 / Math.sqrt(squash), hop = walking ? Math.abs(Math.sin(ph)) * 0.07 : 0, bottom = R * 0.9;
      root.position.set(x, y + hop * sc, z); root.rotation.y = yaw;
      shadow.position.set(x, y + 0.03, z);
      body.position.y = 0.03 + bottom * squash; body.scale.set(sx, squash, sx);
      [-1, 1].forEach((s, i) => {
        const step = walking ? Math.sin(ph + i * Math.PI) * 0.09 : 0;
        feet[i].position.set(s * 0.13, 0.045 + (step > 0 ? step * 0.3 : 0), 0.08 + step);
        arms[i].position.set(s * (R * 1.14 * sx + 0.015), 0.03 + (bottom - 0.045) * squash, walking ? -step * 0.8 : 0);
      });
      sprout.position.y = 0.03 + 2 * bottom * squash - 0.015;
      sprout.rotation.x = (10 + 5 * Math.sin(t * 1.7)) * Math.PI / 180;
      const blink = t % 3.7 < 0.12 ? 0.12 : 1;
      eyes.forEach(e => { e.scale.y = blink; });
    },
  };
}

// --- the page ---------------------------------------------------------------------------------

function mount(root) {
  root.innerHTML = `
    <canvas tabindex="0" aria-label="A 3D world you can walk around in"></canvas>
    <div class="w-labels"></div>
    <div class="w-bubble" hidden></div>
    <div class="w-panel">
      <div class="w-row"><span>Look</span><button data-look="0">Clay</button><button data-look="1">Crayon</button></div>
      <div class="w-row"><span>Time</span><button data-clock="0.3">Morning</button><button data-clock="0.5">Noon</button><button data-clock="0.74">Dusk</button><button data-clock="0.92">Night</button></div>
      <div class="w-row"><span>World</span><span class="w-end">flat</span><input type="range" min="0" max="100" value="62" aria-label="How round the world is"><span class="w-end">globe</span></div>
    </div>
    <div class="w-places"></div>
    <div class="w-foot"><code class="w-intent"></code><span class="w-stats"></span></div>
    <div class="w-fade"></div>`;
  const $ = s => root.querySelector(s), cv = $('canvas');

  const renderer = new THREE.WebGLRenderer({ canvas: cv, antialias: true });
  renderer.setPixelRatio(Math.min(1.5, window.devicePixelRatio || 1));
  const scene = new THREE.Scene(), camera = new THREE.PerspectiveCamera(40, 16 / 9, 0.1, 1200);
  const U = {
    uC: { value: new THREE.Vector3() }, uK: { value: 0 }, uLight: { value: new THREE.Vector3(-0.45, 0.65, 0.6).normalize() },
    uTint: { value: new THREE.Color(1, 1, 1) }, uShade: { value: new THREE.Color('#d8c8e8') }, uInk: { value: col('#4a3a4f') }, uHaze: { value: new THREE.Color() },
    uCrayon: { value: 0 }, uBoil: { value: 0 }, uNight: { value: 0 }, uTime: { value: 0 }, uGround: { value: buildGroundTexture() },
  };
  const mat = new THREE.ShaderMaterial({ vertexShader: VERT, fragmentShader: FRAG, uniforms: U });
  const groundMat = new THREE.ShaderMaterial({ vertexShader: VERT, fragmentShader: GROUND_FRAG, uniforms: U });
  const hullMat = new THREE.ShaderMaterial({ vertexShader: VERT, fragmentShader: HULL_FRAG, uniforms: U, side: THREE.BackSide });
  const waterMat = new THREE.ShaderMaterial({ vertexShader: VERT, fragmentShader: WATER_FRAG, uniforms: U });
  const skyU = { uTop: { value: new THREE.Color() }, uLow: { value: new THREE.Color() }, uSun: { value: new THREE.Vector3(0, 1, 0) }, uNight: U.uNight, uTime: U.uTime };
  const sky = new THREE.Mesh(new THREE.SphereGeometry(600, 24, 16), new THREE.ShaderMaterial({ vertexShader: SKY_VERT, fragmentShader: SKY_FRAG, uniforms: skyU, side: THREE.BackSide, depthWrite: false }));
  sky.frustumCulled = false;
  scene.add(sky);

  // one tile of world, four copies around him
  const terrain = buildTerrain(), { scene: land, clouds, blocked } = buildScenery();
  const water = paint(new THREE.PlaneGeometry(L, L, 48, 48).rotateX(-Math.PI / 2), '#8fd0e6');
  const sails = new Parts();
  for (let i = 0; i < 4; i++) {
    const a = i * Math.PI / 2, g = new THREE.BoxGeometry(0.55, 2.7, 0.05).translate(0.12, 1.5, 0).rotateZ(a);
    sails.add(g, i % 2 ? '#fffdf8' : '#f8bfd0', { th: 0.02 });
  }
  const sailsGeo = sails.build();
  const copies = [], M = PLACES.mill;
  const addMesh = (grp, geo, material) => { const m = new THREE.Mesh(geo, material); m.frustumCulled = false; grp.add(m); return m; };
  for (let i = 0; i < 4; i++) {
    const grp = new THREE.Group();
    const c = { grp };
    addMesh(grp, terrain, groundMat); addMesh(grp, water, waterMat);
    addMesh(grp, land.solid, mat); c.landHull = addMesh(grp, land.hull, hullMat);
    c.clouds = addMesh(grp, clouds.solid, mat);
    c.sail = new THREE.Group(); c.sail.position.set(M.x, M.y + 4.8, M.z - 1.55);
    addMesh(c.sail, sailsGeo.solid, mat); addMesh(c.sail, sailsGeo.hull, hullMat); grp.add(c.sail);
    c.out = addMesh(grp, new THREE.BufferGeometry(), mat); c.outHull = addMesh(grp, new THREE.BufferGeometry(), hullMat);
    scene.add(grp); copies.push(c);
  }
  const interior = new THREE.Group();
  interior.visible = false;
  scene.add(interior);
  const guyRig = buildGuy(mat, hullMat, U);
  scene.add(guyRig.root, guyRig.shadow);

  // walkable grid, wrapped; A* like the sim's Pathfinder
  const baseBlocked = new Uint8Array(GRID * GRID), pathCell = new Uint8Array(GRID * GRID), houseBlocked = new Uint8Array(GRID * GRID);
  const ci = v => Math.floor(((v + HALF) % L + L) % L / CELL_W), idx = (cx, cz) => ((cz + GRID) % GRID) * GRID + ((cx + GRID) % GRID);
  const cellPos = n => [(n % GRID + 0.5) * CELL_W - HALF, (Math.floor(n / GRID) + 0.5) * CELL_W - HALF];
  for (let n = 0; n < GRID * GRID; n++) {
    const [x, z] = cellPos(n), h = heightAt(x, z);
    if (h < 0.42) baseBlocked[n] = 1;
    if (onPath(x, z)) pathCell[n] = 1;
  }
  for (const [x, z, rad] of blocked) {
    const reach = Math.ceil((rad || 1) / CELL_W);
    for (let a = -reach; a <= reach; a++) for (let b = -reach; b <= reach; b++) {
      const n = idx(ci(x) + a, ci(z) + b), [cx, cz] = cellPos(n);
      if (distAt(cx, cz, x, z) < (rad || 1) + 0.25) baseBlocked[n] = 1;
    }
  }
  const isBlocked = n => baseBlocked[n] || houseBlocked[n];
  /** Everything he can actually get to from home, so a goal never lands in a sealed pocket. */
  const reachable = new Uint8Array(GRID * GRID);
  function floodFromHome() {
    reachable.fill(0);
    const s = idx(ci(PLACES.home.x), ci(PLACES.home.z)), q = [s];
    reachable[s] = 1;
    while (q.length) {
      const n = q.pop(), cx = n % GRID, cz = Math.floor(n / GRID);
      for (const [a, b] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const m = idx(cx + a, cz + b);
        if (!reachable[m] && !isBlocked(m)) { reachable[m] = 1; q.push(m); }
      }
    }
  }
  function findPath(sx, sz, tx, tz) {
    const start = idx(ci(sx), ci(sz));
    let goal = idx(ci(tx), ci(tz));
    if (!reachable[goal]) {
      let found = -1, bestD = Infinity;
      for (let a = -8; a <= 8; a++) for (let b = -8; b <= 8; b++) {
        const n = idx(ci(tx) + a, ci(tz) + b), d = a * a + b * b;
        if (reachable[n] && d < bestD) { bestD = d; found = n; }
      }
      if (found < 0) return null;
      goal = found;
    }
    const cost = new Float32Array(GRID * GRID).fill(Infinity), from = new Int32Array(GRID * GRID).fill(-1), open = [start];
    cost[start] = 0;
    const [gx, gz] = cellPos(goal), score = n => { const [x, z] = cellPos(n); return cost[n] + 0.7 * Math.hypot(wrap(gx - x), wrap(gz - z)) / CELL_W; };
    while (open.length) {
      let best = 0, bs = Infinity;
      for (let i = 0; i < open.length; i++) { const v = score(open[i]); if (v < bs) { bs = v; best = i; } }
      const n = open.splice(best, 1)[0];
      if (n === goal) break;
      const cx = n % GRID, cz = Math.floor(n / GRID);
      for (const [a, b] of [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [1, -1], [-1, 1], [-1, -1]]) {
        const m = idx(cx + a, cz + b);
        if (isBlocked(m)) continue;
        if (a && b && (isBlocked(idx(cx + a, cz)) || isBlocked(idx(cx, cz + b)))) continue;
        const [mx, mz] = cellPos(m), [nx, nz] = cellPos(n);
        const c = cost[n] + (a && b ? 1.41 : 1) * (pathCell[m] ? 0.55 : 1) * (1 + Math.abs(heightAt(mx, mz) - heightAt(nx, nz)) * 0.8);
        if (c < cost[m]) { cost[m] = c; from[m] = n; if (!open.includes(m)) open.push(m); }
      }
    }
    if (goal !== start && from[goal] < 0) return null;
    const seq = [];
    for (let n = goal; n !== start && n >= 0; n = from[n]) seq.unshift(n);
    const pts = []; let px = sx, pz = sz;
    for (const n of seq) { const [x, z] = cellPos(n); px += wrap(x - px); pz += wrap(z - pz); pts.push([px, pz]); }
    return pts.filter((_, i) => i % 2 === 1 || i === pts.length - 1);
  }

  // the state of things
  const G = { x: PLACES.home.x, z: PLACES.home.z + 2, y: 0, yaw: 0, walk: false, path: [], idle: 4, arrive: null, inside: false, room: null };
  const cam = { yaw: 0.55, pitch: 0.4, dist: 16, tx: G.x, ty: 2, tz: G.z, in: 0 };
  const view = { clock: 0.36, round: 0.62, crayon: 0, auto: true, roundSmooth: 0.62, plan: false };
  const keys = new Set();
  let house = null, visible = true, bubble = null, insideRooms = [];

  function rebuildHouse() {
    const rooms = window.HOUSE.rooms, old = house;
    house = buildHouse(rooms);
    houseBlocked.fill(0);
    for (const r of rooms) for (let x = r.gx * OUT_CELL - 1; x <= (r.gx + r.w) * OUT_CELL + 1; x += CELL_W) for (let z = r.gy * OUT_CELL - 1; z <= (r.gy + r.h) * OUT_CELL + 1; z += CELL_W) {
      houseBlocked[idx(ci(x), ci(z))] = 1;
    }
    houseBlocked[idx(ci(house.entry.x), ci(house.entry.y + 2))] = 0;
    for (const c of copies) { c.out.geometry = house.outside.solid; c.outHull.geometry = house.outside.hull; }
    if (old) { old.outside.solid.dispose(); old.outside.hull.dispose(); }
    PLACES.home.x = house.entry.x; PLACES.home.z = house.entry.y + 2;
    floodFromHome();
    // inside, one pair of meshes per room
    for (const m of [...interior.children]) { interior.remove(m); m.geometry.dispose(); }
    insideRooms = rooms.map(r => {
      const b = buildRoomInterior(r, rooms), floor = addMesh(interior, b.floor, mat), up = addMesh(interior, b.up, mat);
      return { room: r, up };
    });
  }
  rebuildHouse();
  window.addEventListener('house-changed', rebuildHouse);

  function say(s) { bubble = { s, until: performance.now() + 2600 }; }
  function setIntent(s) { $('.w-intent').textContent = s; }
  function goTo(x, z, then) {
    const pts = findPath(G.x, G.z, x, z);
    if (!pts) { say('can\'t get there'); return; }
    if (!pts.length) pts.push([x, z]); // already on that tile
    G.path = pts; G.arrive = then || null; G.idle = 7 + Math.random() * 5;
  }
  function goPlace(key, intentNote = '') {
    const p = PLACES[key];
    setIntent(`move_to(entity: "${key}")${intentNote}`);
    const [px, pz] = p.go || [p.x, p.z];
    goTo(G.x + wrap(px - G.x), G.z + wrap(pz - G.z), () => say(p.say));
  }
  const homeImage = () => [Math.round(G.x / L) * L, Math.round(G.z / L) * L];
  /** Dips the view through a fade while the scene swaps, since the inside doesn't sit in the world at its real size. */
  function fadeThrough(swap) {
    const el = $('.w-fade');
    el.classList.add('on');
    setTimeout(() => { swap(); el.classList.remove('on'); }, 260);
  }
  function goInside() {
    if (G.inside) return;
    setIntent('do(activity: "go_inside")');
    const [hx, hz] = homeImage(), e = house.entry;
    goTo(hx + e.x, hz + e.y + 1.8, () => fadeThrough(() => {
      const way = houseEntry(window.HOUSE.rooms);
      G.outX = G.x; G.outZ = G.z;
      G.x = way.cx * CELL; G.z = way.cy * CELL - 0.8;
      G.inside = true; G.room = way.room; G.path = []; G.idle = 1; view.plan = false;
      cam.in = 1; cam.tx = G.x; cam.tz = G.z; cam.ty = 0.7;
      say('home'); renderPlaces();
    }));
  }
  function goOutside() {
    setIntent('do(activity: "go_outside")');
    const way = houseEntry(window.HOUSE.rooms), pts = housePath(G.room, way.room);
    pts.push([way.cx * CELL, way.cy * CELL - 0.6]);
    G.path = pts; G.idle = 8; G.room = way.room;
    G.arrive = () => fadeThrough(() => {
      G.x = G.outX; G.z = G.outZ; G.inside = false; G.room = null; view.plan = false; G.path = [];
      cam.in = 0; cam.tx = G.x; cam.tz = G.z; cam.ty = heightAt(G.x, G.z) + 0.9;
      renderPlaces(); say('nice out');
    });
  }
  function wanderInside() {
    const rooms = window.HOUSE.rooms, to = rooms[Math.floor(Math.random() * rooms.length)];
    const pts = housePath(G.room, to);
    pts.push(freeSpot(to));
    G.path = pts; G.room = to;
    setIntent(`do(activity: "potter", in: "${to.def.id}")`);
  }

  // the panel
  const placeBox = $('.w-places');
  function renderPlaces() {
    placeBox.innerHTML = G.inside
      ? `<button data-act="out">Go outside</button><button data-act="plan">${view.plan ? 'His room' : 'Whole house'}</button>`
      : `<button data-act="in" class="w-primary">Go inside</button>` + Object.entries(PLACES).map(([k, p]) => `<button data-place="${k}">${p.name}</button>`).join('');
  }
  renderPlaces();
  placeBox.addEventListener('click', e => {
    const b = e.target.closest('button');
    if (!b) return;
    if (b.dataset.place) goPlace(b.dataset.place);
    if (b.dataset.act === 'in') goInside();
    if (b.dataset.act === 'out') goOutside();
    if (b.dataset.act === 'plan') { view.plan = !view.plan; renderPlaces(); }
  });
  const syncButtons = () => root.querySelectorAll('[data-look]').forEach(b => b.classList.toggle('on', Number(b.dataset.look) === view.crayon));
  root.querySelectorAll('[data-look]').forEach(b => b.addEventListener('click', () => { view.crayon = Number(b.dataset.look); syncButtons(); }));
  root.querySelectorAll('[data-clock]').forEach(b => b.addEventListener('click', () => { view.clock = Number(b.dataset.clock); }));
  $('input[type=range]').addEventListener('input', e => { view.round = e.target.value / 100; });
  syncButtons();

  // the camera and pointer
  const ray = new THREE.Raycaster(), ndc = new THREE.Vector2();
  function unbend(q) {
    const k = U.uK.value;
    if (k < 1e-4) return { x: q.x, z: q.z, alt: q.y };
    const R = 1 / k, cx = U.uC.value.x, cz = U.uC.value.z, rx = q.x - cx, rz = q.z - cz, ry = q.y + R, hr = Math.hypot(rx, rz), a = Math.atan2(hr, ry);
    const s = hr > 1e-5 ? a * R / hr : 0;
    return { x: cx + rx * s, z: cz + rz * s, alt: Math.hypot(rx, ry, rz) - R };
  }
  function bendPt(x, y, z) {
    const k = U.uK.value;
    if (k < 1e-4) return new THREE.Vector3(x, y, z);
    const R = 1 / k, dx = x - U.uC.value.x, dz = z - U.uC.value.z, r = Math.hypot(dx, dz), a = r * k, rad = R + y, s = r > 1e-5 ? rad * Math.sin(a) / r : 0;
    return new THREE.Vector3(U.uC.value.x + dx * s, rad * Math.cos(a) - R, U.uC.value.z + dz * s);
  }
  function groundAt(cx, cy) {
    const b = cv.getBoundingClientRect();
    ndc.set((cx - b.left) / b.width * 2 - 1, -((cy - b.top) / b.height) * 2 + 1);
    ray.setFromCamera(ndc, camera);
    const q = new THREE.Vector3();
    for (let t = 1; t < 240; t += 0.3) {
      q.copy(ray.ray.origin).addScaledVector(ray.ray.direction, t);
      const u = unbend(q);
      if (u.alt <= heightAt(u.x, u.z)) return u;
    }
    return null;
  }
  let drag = null;
  cv.addEventListener('pointerdown', e => { drag = { x: e.clientX, y: e.clientY, moved: 0 }; cv.setPointerCapture(e.pointerId); cv.focus({ preventScroll: true }); });
  cv.addEventListener('pointermove', e => {
    if (!drag) return;
    const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
    drag.moved += Math.abs(dx) + Math.abs(dy); drag.x = e.clientX; drag.y = e.clientY;
    if (!G.inside) { cam.yaw -= dx * 0.006; cam.pitch = clamp(cam.pitch + dy * 0.005, 0.12, 1.35); }
  });
  cv.addEventListener('pointerup', e => {
    const wasClick = drag && drag.moved < 6;
    drag = null;
    if (!wasClick || G.inside) return;
    const hit = groundAt(e.clientX, e.clientY);
    if (!hit) return;
    setIntent(`move_to(tile: ${Math.round(wrap(hit.x))}, ${Math.round(wrap(hit.z))})`);
    goTo(hit.x, hit.z);
  });
  cv.addEventListener('wheel', e => {
    if (document.activeElement !== cv) return;
    e.preventDefault();
    cam.dist = clamp(cam.dist * Math.exp(e.deltaY * 0.0012), 5, 70);
  }, { passive: false });
  const MOVE = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight']);
  cv.addEventListener('keydown', e => { if (MOVE.has(e.code)) { keys.add(e.code); e.preventDefault(); } });
  cv.addEventListener('keyup', e => keys.delete(e.code));
  cv.addEventListener('blur', () => keys.clear());
  new ResizeObserver(() => {
    const w = root.clientWidth, h = root.clientHeight;
    renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix();
  }).observe(root);
  new IntersectionObserver(es => { visible = es[0].isIntersecting; }).observe(root);

  // labels over places
  const labelBox = $('.w-labels'), bubbleEl = $('.w-bubble'), tags = {};
  for (const [k, p] of Object.entries(PLACES)) { const d = document.createElement('div'); d.className = 'w-tag'; d.textContent = p.name; labelBox.append(d); tags[k] = d; }
  function toScreen(v) { const p = v.clone().project(camera); return [(p.x + 1) / 2 * root.clientWidth, (1 - p.y) / 2 * root.clientHeight, p.z]; }

  // the day
  const light = new THREE.Vector3();
  function setDay(dt) {
    if (view.auto) view.clock = (view.clock + dt / 150) % 1;
    const sk = skyAt(view.clock), n = sk.night;
    skyU.uTop.value.set(sk.top); skyU.uLow.value.set(sk.low);
    U.uHaze.value.set(sk.low); U.uNight.value = n;
    const u = clamp((view.clock - 0.2) / 0.6, 0, 1), day = view.clock > 0.2 && view.clock < 0.8, a = Math.PI * u;
    const mu = ((view.clock + 0.2) % 1) / 0.4, ma = Math.PI * clamp(mu, 0, 1);
    if (day) light.set(-Math.cos(a) * 0.82, Math.sin(a) + 0.12, 0.42); else light.set(-Math.cos(ma) * 0.7, Math.sin(ma) + 0.2, 0.5);
    light.normalize(); U.uLight.value.copy(light); skyU.uSun.value.copy(light);
    const dusk = Math.max(0, 1 - Math.abs(view.clock - 0.74) / 0.09) + Math.max(0, 1 - Math.abs(view.clock - 0.25) / 0.09);
    U.uTint.value.setRGB(lerp(lerp(1, 1.04, dusk), 0.3, n), lerp(lerp(1, 0.84, dusk), 0.34, n), lerp(lerp(1, 0.78, dusk), 0.58, n));
    if (G.inside) {
      // indoors is the dioramas' soft backdrop and even light, whatever the hour
      skyU.uTop.value.set('#efe6f6'); skyU.uLow.value.set('#fde9dc'); skyU.uSun.value.set(0, -1, 0);
      U.uHaze.value.set('#fde9dc'); U.uNight.value = 0; U.uTint.value.setRGB(1, 1, 1);
      U.uLight.value.set(-0.45, 0.65, 0.6).normalize();
    }
  }

  // the frame
  let last = performance.now(), t = 0, statT = 0;
  function frame(now) {
    requestAnimationFrame(frame);
    if (!visible) { last = now; return; }
    const dt = Math.min(0.05, (now - last) / 1000); last = now; t += dt;
    setDay(dt);

    // he moves
    let mx = 0, mz = 0;
    if (!G.inside) {
      if (keys.has('KeyW') || keys.has('ArrowUp')) mz -= 1;
      if (keys.has('KeyS') || keys.has('ArrowDown')) mz += 1;
      if (keys.has('KeyA') || keys.has('ArrowLeft')) mx -= 1;
      if (keys.has('KeyD') || keys.has('ArrowRight')) mx += 1;
    }
    G.walk = false;
    const speed = G.inside ? 2.6 : 4.6;
    let tYaw = G.yaw;
    if (mx || mz) {
      const c = Math.cos(cam.yaw), s = Math.sin(cam.yaw), wx = (mx * c + mz * s) / Math.hypot(mx, mz), wz = (-mx * s + mz * c) / Math.hypot(mx, mz);
      const nx = G.x + wx * speed * dt, nz = G.z + wz * speed * dt;
      G.path = []; G.arrive = null; G.idle = 7;
      if (!isBlocked(idx(ci(nx), ci(G.z)))) G.x = nx;
      if (!isBlocked(idx(ci(G.x), ci(nz)))) G.z = nz;
      tYaw = Math.atan2(wx, wz); G.walk = true;
      setIntent('you\'re driving (WASD)');
    } else if (G.path.length) {
      const [px, pz] = G.path[0], dx = px - G.x, dz = pz - G.z, d = Math.hypot(dx, dz);
      if (d < 0.18) { G.path.shift(); if (!G.path.length && G.arrive) { const f = G.arrive; G.arrive = null; f(); } }
      else { const st = Math.min(d, speed * dt); G.x += dx / d * st; G.z += dz / d * st; tYaw = Math.atan2(dx, dz); G.walk = true; }
    } else if ((G.idle -= dt) <= 0) {
      G.idle = 5 + Math.random() * 5;
      if (G.inside) wanderInside();
      else { const ks = Object.keys(PLACES); goPlace(ks[Math.floor(Math.random() * ks.length)], '  (he chose)'); }
    }
    let dyaw = tYaw - G.yaw; dyaw = Math.atan2(Math.sin(dyaw), Math.cos(dyaw)); G.yaw += dyaw * (1 - Math.exp(-dt * 11));
    const indoors = G.inside;
    G.y = indoors ? 0 : heightAt(G.x, G.z);

    // the world bends around him
    view.roundSmooth = lerp(view.roundSmooth, view.round, 1 - Math.exp(-dt * 5));
    const rs = view.roundSmooth, k0 = rs < 0.01 ? 0 : 1 / lerp(220, 24, Math.pow(rs, 1.15));
    U.uK.value = indoors ? 0 : k0;
    U.uC.value.set(G.x, 0, G.z);
    U.uTime.value = t; U.uBoil.value = Math.floor(t * 7) % 3;
    U.uCrayon.value = lerp(U.uCrayon.value, view.crayon, 1 - Math.exp(-dt * 7));

    // the camera
    const follow = 1 - Math.exp(-dt * 5), rooms = window.HOUSE.rooms;
    // Inside, the camera frames the room he's in (or the whole house), and the other rooms flatten to plans.
    const focus = rooms.find(r => roomContains(r, G.x, G.z)) || G.room || rooms[0], framed = view.plan ? rooms : [focus];
    let bx0 = 1e9, bx1 = -1e9, bz0 = 1e9, bz1 = -1e9;
    for (const r of framed) { bx0 = Math.min(bx0, r.gx * CELL); bx1 = Math.max(bx1, (r.gx + r.w) * CELL); bz0 = Math.min(bz0, r.gy * CELL); bz1 = Math.max(bz1, (r.gy + r.h) * CELL); }
    const inDist = (bx1 - bx0 + bz1 - bz0) * 1.55 + 6, ci2 = cam.in;
    if (indoors) for (const { room, up } of insideRooms) {
      const want = view.plan ? 0.16 : room === focus ? 1 : 0.05;
      up.scale.y = Math.max(0.001, lerp(up.scale.y, want, 1 - Math.exp(-dt * 6)));
    }
    cam.dist3 = lerp(cam.dist3 ?? inDist, inDist, follow);
    // Zoomed out on a round world, look at the globe rather than at him on top of it.
    const globeLift = U.uK.value > 1e-4 ? clamp((cam.dist - 18) / 40, 0, 1) * Math.min(1 / U.uK.value, 45) * 0.8 : 0;
    const wantX = indoors ? (bx0 + bx1) / 2 : G.x, wantZ = indoors ? (bz0 + bz1) / 2 : G.z, wantY = indoors ? 0.7 : G.y + 0.9 - globeLift;
    cam.tx = lerp(cam.tx, wantX, follow); cam.tz = lerp(cam.tz, wantZ, follow); cam.ty = lerp(cam.ty, wantY, follow);
    const yaw = indoors ? Math.PI / 4 : cam.yaw, pitch = indoors ? 0.62 : cam.pitch, dist = indoors ? cam.dist3 : cam.dist;
    camera.fov = indoors ? 22 : 40; camera.updateProjectionMatrix();
    camera.position.set(cam.tx + Math.sin(yaw) * Math.cos(pitch) * dist, cam.ty + Math.sin(pitch) * dist, cam.tz + Math.cos(yaw) * Math.cos(pitch) * dist);
    camera.lookAt(cam.tx, cam.ty, cam.tz);
    sky.position.copy(camera.position);

    // tiles
    const i0 = Math.floor(G.x / L), j0 = Math.floor(G.z / L);
    interior.visible = indoors;
    copies.forEach((c, n) => {
      c.grp.visible = !indoors;
      c.grp.position.set((i0 + (n & 1)) * L, 0, (j0 + (n >> 1)) * L);
      c.sail.rotation.z = t * 0.8;
      c.clouds.position.x = (t * 0.5) % L;
      c.landHull.visible = U.uCrayon.value > 0.5;
    });

    guyRig.pose(t, G.walk, G.yaw, G.x, G.y, G.z);
    renderer.render(scene, camera);

    // labels and the bubble
    const eye = camera.position.clone();
    for (const [k, p] of Object.entries(PLACES)) {
      const x = G.x + wrap(p.x - G.x), z = G.z + wrap(p.z - G.z), d = Math.hypot(x - G.x, z - G.z), el = tags[k];
      const y = (p.flat ? p.y : heightAt(x, z)) + 3.2, q = bendPt(x, y, z);
      const up = U.uK.value > 1e-4 ? q.clone().sub(new THREE.Vector3(G.x, -1 / U.uK.value, G.z)).normalize() : new THREE.Vector3(0, 1, 0);
      const seen = up.dot(eye.clone().sub(q)) > 0 && d > 3 && d < 42 && !indoors;
      if (seen) { const [sx, sy, sz] = toScreen(q); el.style.transform = `translate(${sx}px, ${sy}px) translate(-50%, -100%)`; el.style.opacity = sz < 1 ? clamp((42 - d) / 12, 0, 1) : 0; }
      else el.style.opacity = 0;
    }
    if (bubble && performance.now() < bubble.until) {
      const [sx, sy] = toScreen(bendPt(G.x, G.y + 1.6, G.z));
      bubbleEl.hidden = false; bubbleEl.textContent = bubble.s; bubbleEl.style.transform = `translate(${sx}px, ${sy}px) translate(-50%, -100%)`;
    } else bubbleEl.hidden = true;
    if (now - statT > 500) {
      statT = now;
      const inf = renderer.info.render;
      $('.w-stats').textContent = `${inf.calls} draws · ${Math.round(inf.triangles / 1000)}k tris (this browser, not the TV)`;
    }
  }
  requestAnimationFrame(frame);
  // Exposed for poking at it from the console.
  window.WORLD = { G, cam, view, U, renderer, keys };
  window.WORLD_API = { goPlace, goInside, goOutside, say, findPath, isBlocked, idx, ci };
}

const root = document.querySelector('.world3d');
if (root) mount(root);
