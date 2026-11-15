import {
  CircleGeometry, Color, DirectionalLight, DoubleSide, Fog, Group, HemisphereLight, Matrix4, Mesh, MeshBasicMaterial, MeshStandardMaterial,
  NoToneMapping, OrthographicCamera, PCFSoftShadowMap, PointLight, Scene, SRGBColorSpace, Vector3, WebGLRenderer,
  type BufferGeometry, type Camera, type Material, type Texture,
} from "three";
import { merged, partGeometry, placement, type Part } from "./geometry";
import { dayAt, type Day } from "./day";

/**
 * How many lamps light the scene at once (a fixed number, so the shaders never recompile), how bright one
 * of the sim's light 4 is at full night, and how far past the sim's radius (in tiles, a metre each) its
 * light reaches, since the sim's falls to nothing a tile past it.
 */
const LAMPS = 4;
const LAMP_INTENSITY = 5;
const LAMP_RANGE = 9;
const LAMP_PAST = 1.5;
const SHADOW_MAP = 2048;
/** The sun has to move this far (metres, at its distance) before the shadow map is drawn again. */
const SUN_MOVES = 0.25;

/** A glowing part at a point in a model, and how bright. */
export interface Glow {
  at: Vector3;
  strength: number;
}

/** A light from the sim (the protocol's `Entity.light`), where it shines from in the scene: [strength] 0 to 10, [radius] in tiles. */
export interface Lamp {
  at: Vector3;
  strength: number;
  radius: number;
}

interface Batch {
  geometry: BufferGeometry;
  /** What the parts share: a texture, a glow, a glassiness, or none of them. */
  texture: string | null;
  glow: number;
  opacity: number;
}

/**
 * A part list built into meshes once: the parts merged into one mesh per kind of surface (plain, by
 * texture, glowing, glass), the colour of each part in its vertices. An item costs a handful of draw
 * calls whatever its part count.
 */
export class ClayModel {
  readonly batches: Batch[];
  /** x0, y0, z0, x1, y1, z1. */
  readonly bounds: number[];
  /** Where it glows, for lighting the room from its lamps. */
  readonly glows: Glow[];

  constructor(parts: Part[]) {
    const groups = new Map<string, { parts: Part[]; batch: Omit<Batch, "geometry"> }>();
    const glows: Glow[] = [];
    const m = new Matrix4();
    for (const p of parts) {
      const glow = p.glow ?? 0;
      const opacity = p.opacity ?? 1;
      const key = `${p.texture ?? ""}/${glow}/${opacity}`;
      let g = groups.get(key);
      if (!g) groups.set(key, (g = { parts: [], batch: { texture: p.texture, glow, opacity } }));
      g.parts.push(p);
      if (glow > 0) glows.push({ at: new Vector3(0, 0, 0).applyMatrix4(placement(p, m)), strength: glow });
    }
    const c = new Color();
    this.batches = [...groups.values()].map(({ parts: ps, batch }) => ({
      ...batch,
      geometry: merged(
        ps.map((p) => partGeometry(p).applyMatrix4(placement(p, m))),
        ps.map((p) => (c.set(p.color), [c.r, c.g, c.b] as [number, number, number])),
      ),
    }));
    const b = [Infinity, Infinity, Infinity, -Infinity, -Infinity, -Infinity];
    for (const { geometry } of this.batches) {
      const box = geometry.boundingBox!;
      b[0] = Math.min(b[0], box.min.x); b[1] = Math.min(b[1], box.min.y); b[2] = Math.min(b[2], box.min.z);
      b[3] = Math.max(b[3], box.max.x); b[4] = Math.max(b[4], box.max.y); b[5] = Math.max(b[5], box.max.z);
    }
    this.bounds = b;
    // Nearby glows are one lamp: the string lights' bulbs, a lava lamp's glass and blobs.
    this.glows = [];
    for (const g of glows) {
      const near = this.glows.find((o) => o.at.distanceTo(g.at) < 0.8);
      if (near) near.strength = Math.max(near.strength, g.strength);
      else this.glows.push({ at: g.at.clone(), strength: g.strength });
    }
  }
}

/** A model standing in the scene; [set] places it. Static things cast shadows, and moving one redraws the shadow map. */
export class Placed {
  readonly group = new Group();
  /** The material of its "face" batch, if it has one: the view swaps its map as his expression changes. */
  face: MeshStandardMaterial | null = null;

  constructor(readonly model: ClayModel, private readonly r: ClayRenderer, readonly casts: boolean) {
    this.group.matrixAutoUpdate = false;
  }

  set(matrix: Matrix4): this {
    if (this.group.matrix.equals(matrix)) return this;
    this.group.matrix.copy(matrix);
    this.group.matrixWorldNeedsUpdate = true;
    if (this.casts && this.group.visible) this.r.shadowsChanged();
    return this;
  }

  show(visible: boolean): this {
    if (this.group.visible !== visible && this.casts) this.r.shadowsChanged();
    this.group.visible = visible;
    return this;
  }
}

/**
 * Draws the room in clay, as `world_sketches/fable_field_lab.html` does: Three's standard materials,
 * a sun with a soft shadow map, sky light from above and the ground's bounce, and a few point lights
 * for lamps, all following the game hour ([day]). Glowing parts brighten at night.
 *
 * The camera is orthographic; the view points it. The shadow map is drawn again only when the sun has
 * moved or something that casts a shadow has (the guy and what he holds don't cast; he has a soft
 * blob under him instead).
 */
export class ClayRenderer {
  readonly webgl: WebGLRenderer;
  readonly scene = new Scene();
  readonly camera = new OrthographicCamera(-8, 8, 4.5, -4.5, 0.1, 400);
  /** The light that follows him; the view moves it. */
  readonly lantern = new PointLight("#ffcf8a", 0, 7, 2);
  /** How far into night it is, 0 by day to 1. */
  night = 0;
  light: Day = dayAt(12);

  private readonly hemi = new HemisphereLight("#e2eef2", "#dccfae", 1.5);
  private readonly sun = new DirectionalLight("#fff6e6", 2.3);
  private readonly lamps: PointLight[] = [];
  private readonly solid: MeshStandardMaterial;
  private readonly textured = new Map<Texture, MeshStandardMaterial>();
  private readonly glowing = new Map<number, MeshStandardMaterial>();
  private readonly glass = new Map<string, MeshStandardMaterial>();
  private readonly clear = new Color();
  private readonly shadowSun = new Vector3(Infinity, 0, 0);
  private dirty = true;
  private readonly blobGeometry = new CircleGeometry(0.5, 24).rotateX(-Math.PI / 2);
  private readonly blobMaterial = new MeshBasicMaterial({ color: "#3a2c40", transparent: true, opacity: 0.16, depthWrite: false });

  constructor(canvas: HTMLCanvasElement, private readonly texture: (name: string) => Texture | null) {
    this.webgl = new WebGLRenderer({ canvas, antialias: true, alpha: false, powerPreference: "high-performance" });
    this.webgl.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.webgl.outputColorSpace = SRGBColorSpace;
    this.webgl.toneMapping = NoToneMapping;
    this.webgl.shadowMap.enabled = true;
    this.webgl.shadowMap.type = PCFSoftShadowMap;
    this.webgl.shadowMap.autoUpdate = false;

    this.solid = new MeshStandardMaterial({ vertexColors: true, roughness: 0.92, metalness: 0 });
    const sun = this.sun;
    sun.castShadow = true;
    sun.shadow.mapSize.set(SHADOW_MAP, SHADOW_MAP);
    sun.shadow.bias = -0.0004;
    sun.shadow.normalBias = 0.03;
    Object.assign(sun.shadow.camera, { left: -11, right: 11, top: 11, bottom: -11, near: 1, far: 140 });
    this.scene.add(this.hemi, sun, sun.target, this.lantern);
    for (let i = 0; i < LAMPS; i++) {
      const l = new PointLight("#ffd6a0", 0, LAMP_RANGE, 2);
      this.lamps.push(l);
      this.scene.add(l);
    }
  }

  /** The sun swings round ([x], [z]), the middle of what it lights, and its shadows reach [reach] metres from there. */
  aim(x: number, z: number, reach = 11) {
    this.sun.target.position.set(x, 0, z);
    this.sun.target.updateMatrixWorld();
    const cam = this.sun.shadow.camera;
    if (cam.right !== reach) {
      Object.assign(cam, { left: -reach, right: reach, top: reach, bottom: -reach });
      cam.updateProjectionMatrix();
    }
    this.dirty = true;
  }

  /** The light at [hour], from the sketch's keys: sun, sky, background, how far into night, and the glows. */
  setHour(hour: number) {
    const d = (this.light = dayAt(hour));
    this.night = d.night;
    this.sun.color.copy(d.sun);
    this.sun.intensity = d.sunIntensity;
    this.hemi.color.copy(d.sky);
    this.hemi.groundColor.copy(d.ground);
    this.hemi.intensity = d.skyIntensity;
    const t = this.sun.target.position;
    this.sun.position.set(t.x + d.sunFrom.x, d.sunFrom.y, t.z + d.sunFrom.z);
    if (this.sun.position.distanceTo(this.shadowSun) > SUN_MOVES) this.dirty = true;
    for (const [strength, m] of this.glowing) m.emissiveIntensity = strength * (0.3 + 1.2 * d.night);
    this.lanternBy();
  }

  /** His lantern's light from the sim ([setLantern]); null when he carries none. */
  private lanternLight: { strength: number; radius: number } | null = null;

  /** His lantern as the sim has it: as bright and as far as the light he carries there, so the dark the sim sees is the dark on screen. */
  setLantern(light: { strength: number; radius: number } | null) {
    if (light?.strength === this.lanternLight?.strength && light?.radius === this.lanternLight?.radius) return;
    this.lanternLight = light;
    this.lanternBy();
  }

  private lanternBy() {
    const l = this.lanternLight;
    this.lantern.intensity = l ? LAMP_INTENSITY * (l.strength / 4) * clamp01((this.night - 0.1) / 0.7) + 0.2 : 0;
    this.lantern.distance = l ? l.radius + LAMP_PAST : 0.01;
  }

  /**
   * What the sky does to the hour's light, after [setHour]: cloud and rain let only [daylight] of the
   * sun through and grey the sky and background; fog is the scene's linear fog (per fragment, no
   * extra pass), the background's colour. Null for no weather.
   */
  setWeather(w: { sky: string; daylight: number } | null) {
    const d = this.light;
    const dull = w ? 1 - w.daylight : 0;
    this.sun.intensity = d.sunIntensity * (1 - dull);
    if (dull > 0) {
      d.background.lerp(OVERCAST, dull * 0.75);
      d.pane.lerp(OVERCAST, dull * 0.6);
      this.hemi.color.lerp(OVERCAST, dull * 0.6);
    }
    if (w?.sky === "fog") {
      this.fog.color.copy(d.background);
      this.scene.fog = this.fog;
    } else this.scene.fog = null;
  }

  private readonly fog = new Fog("#d9d6d2", FOG_NEAR, FOG_FAR);

  /**
   * The sim's lamps: the first [LAMPS] of [lamps] (in world space, the caller's order: those in the place
   * framed, brightest first) light the scene, more as night comes, each as bright and as far as the sim
   * says. Each light sits a little out from its lamp toward [middle], the room's middle, so a lamp
   * against a wall lights the room rather than a hot spot on the wall.
   */
  setLamps(lamps: Lamp[], middle: Vector3) {
    const lit = lamps.slice(0, LAMPS);
    const k = LAMP_INTENSITY * clamp01((this.night - 0.15) / 0.7);
    this.lamps.forEach((l, i) => {
      const g = lit[i];
      l.intensity = g ? k * Math.min(2, g.strength / 4) : 0;
      if (!g) return;
      l.distance = Math.min(LAMP_RANGE, g.radius + LAMP_PAST);
      l.position.copy(g.at);
      const toward = scratch.set(middle.x - g.at.x, 0, middle.z - g.at.z);
      l.position.addScaledVector(toward.normalize(), LAMP_OUT);
      l.position.y += 0.1;
    });
  }

  /** Something that casts a shadow moved: the shadow map is drawn again next frame. */
  shadowsChanged() {
    this.dirty = true;
  }

  /** Puts [model] in the scene; [casts] for things that stand still. A "face" batch gets a material of its own ([Placed.face]). */
  add(model: ClayModel, casts = true): Placed {
    const p = new Placed(model, this, casts);
    for (const b of model.batches) {
      let material: Material;
      if (b.texture === "face") material = p.face = new MeshStandardMaterial({ map: this.texture("face"), vertexColors: true, roughness: 0.9, metalness: 0 });
      else material = this.material(b);
      const mesh = new Mesh(b.geometry, material);
      mesh.castShadow = casts && b.opacity >= 1;
      mesh.receiveShadow = b.opacity >= 1;
      mesh.matrixAutoUpdate = false;
      p.group.add(mesh);
    }
    this.scene.add(p.group);
    if (casts) this.dirty = true;
    return p;
  }

  remove(p: Placed) {
    this.scene.remove(p.group);
    if (p.casts) this.dirty = true;
  }

  /** A mesh in the scene, placed by its matrix: [material], or one for [texture]. */
  mesh(geometry: BufferGeometry, material: Material, opts: { casts?: boolean; receives?: boolean } = {}): Mesh {
    const mesh = new Mesh(geometry, material);
    mesh.matrixAutoUpdate = false;
    mesh.castShadow = opts.casts ?? false;
    mesh.receiveShadow = opts.receives ?? true;
    this.scene.add(mesh);
    if (mesh.castShadow) this.dirty = true;
    return mesh;
  }

  /** A flat picture: [geometry] showing [texture] in the room's light. */
  picture(geometry: BufferGeometry, texture: Texture): Mesh {
    const m = new MeshStandardMaterial({ map: texture, roughness: 0.9, side: DoubleSide });
    return this.mesh(geometry, m);
  }

  /** A soft round shadow on the floor, placed (and stretched) by its matrix: under the guy, who casts none of his own. */
  blob(): Mesh {
    const mesh = new Mesh(this.blobGeometry, this.blobMaterial);
    mesh.matrixAutoUpdate = false;
    mesh.renderOrder = 1;
    this.scene.add(mesh);
    return mesh;
  }

  removeMesh(mesh: Mesh) {
    this.scene.remove(mesh);
    if (mesh.castShadow) this.dirty = true;
  }

  /** The screen is [width] x [height] CSS pixels; the view frames the camera for it. */
  resize(width: number, height: number) {
    this.webgl.setSize(width, height, false);
  }

  render() {
    const w = this.webgl;
    if (this.dirty) {
      w.shadowMap.needsUpdate = true;
      this.shadowSun.copy(this.sun.position);
      this.dirty = false;
    }
    w.setClearColor(this.clear.copy(this.light.background), 1);
    w.render(this.scene, this.camera);
  }

  /** Draws [scene] over the frame just rendered, through [camera], keeping what's there (the HUD). */
  overlay(scene: Scene, camera: Camera) {
    const w = this.webgl;
    w.autoClear = false;
    w.render(scene, camera);
    w.autoClear = true;
  }

  private material(b: Batch): Material {
    if (b.texture) {
      const t = this.texture(b.texture);
      if (!t) return this.solid;
      let m = this.textured.get(t);
      // A cut-out texture (the land's ground) is clear where it's off the land.
      if (!m) this.textured.set(t, (m = new MeshStandardMaterial({ map: t, vertexColors: true, roughness: 0.92, metalness: 0, alphaTest: t.userData.cutout ? 0.5 : 0 })));
      return m;
    }
    if (b.opacity < 1) {
      const key = `${b.opacity}/${b.glow}`;
      let m = this.glass.get(key);
      if (!m) {
        m = new MeshStandardMaterial({ vertexColors: true, roughness: 0.15, metalness: 0, transparent: true, opacity: b.opacity, depthWrite: false });
        this.glass.set(key, m);
      }
      return m;
    }
    if (b.glow > 0) {
      let m = this.glowing.get(b.glow);
      if (!m) {
        m = new MeshStandardMaterial({ vertexColors: true, roughness: 0.8, metalness: 0, emissive: "#ffffff" });
        // Each part glows in its own colour: the emissive is the vertex colour, not one for all.
        m.onBeforeCompile = (s) => {
          s.fragmentShader = s.fragmentShader.replace("vec3 totalEmissiveRadiance = emissive;", "vec3 totalEmissiveRadiance = emissive * vColor;");
        };
        m.customProgramCacheKey = () => "clay-glow";
        m.emissiveIntensity = b.glow * (0.3 + 1.2 * this.night);
        this.glowing.set(b.glow, m);
      }
      return m;
    }
    return this.solid;
  }
}

/** How far out from its glow, toward the room's middle, a lamp's light is. */
const LAMP_OUT = 0.9;
/** The grey cloud and rain mix into the sky. */
const OVERCAST = new Color("#b4b8c0");
/** Fog in metres from the camera, which stands 120 m off (camera.ts). */
const FOG_NEAR = 105;
const FOG_FAR = 150;
const scratch = new Vector3();
const clamp01 = (v: number) => Math.min(1, Math.max(0, v));
