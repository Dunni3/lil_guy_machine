import {
  BackSide, BufferAttribute, BufferGeometry, Color, ColorManagement, DoubleSide, FrontSide, LinearSRGBColorSpace, Matrix4, Mesh, PerspectiveCamera, Scene,
  ShaderMaterial, Vector2, Vector3, Vector4, WebGLRenderer, type Material, type Texture,
} from "three";
import { CrayonCanvas, blobPath } from "./canvas";
import { hex, linear, type Rgba } from "./color";
import { BOIL_FRAMES, type CrayonModel } from "./model";
import { rng } from "./noise";
import { CRAYON_FRAGMENT, CRAYON_VERTEX, FLAT_FRAGMENT, FLAT_VERTEX, PAPER_FRAGMENT, PAPER_VERTEX } from "./shaders";

const BOIL_FPS = 7;
export const INK = hex("#4a3a4f");
const INK_LINEAR = linear(INK);
/** What the shaded side is multiplied by, a soft lavender (linear). */
const SHADE_TINT = linear(hex("#e2d2ec"));
/** From up, to the left and toward the camera, in view space. */
const LIGHT = new Vector3(-0.45, 0.65, 0.6).normalize();
const SHADOW = hex("#9d8db0");
/** Paper tooth grains are 1.5 pixels at this many rows on screen, and that size on any other. */
const GRAIN_ROWS = 1080;
const GRAIN_PX = 1.5;

/** The order things draw in: surfaces, crayon parts, ink over them, then the blended shadows. */
const ORDER = { surface: 0, solid: 1, hull: 2, shadow: 3 };

const rgb = (c: Rgba) => new Vector3(c.r, c.g, c.b);

/** The guy's face, swapped into his body's texture inside [rect] (u, v, width, height of the texture). */
export class Overlay {
  readonly texture: { value: Texture | null } = { value: null };
  constructor(readonly rect: Vector4) {}
}

/** A model standing in the scene, one mesh per batch plus its ink hull. */
export class Placed {
  readonly meshes: Mesh[] = [];
  hull: Mesh | null = null;

  constructor(readonly model: CrayonModel) {}

  set(matrix: Matrix4): this {
    for (const m of this.meshes) {
      m.matrix.copy(matrix);
      m.matrixWorldNeedsUpdate = true;
    }
    if (this.hull) {
      this.hull.matrix.copy(matrix);
      this.hull.matrixWorldNeedsUpdate = true;
    }
    return this;
  }

  show(visible: boolean): this {
    for (const m of this.meshes) m.visible = visible;
    if (this.hull) this.hull.visible = visible;
    return this;
  }
}

/**
 * Draws everything in the hand-drawn look. Surfaces use the cheap paper shader, the parts the crayon
 * shader, and every outline hull is drawn in ink (one boil frame for all, cycled at [BOIL_FPS]);
 * then the hatched shadows, blended.
 *
 * One light for everything: fixed to the camera, up and to the left of it, turned into world space.
 * The room's light only tints ([begin]'s tint).
 */
export class CrayonRenderer {
  readonly webgl: WebGLRenderer;
  readonly scene = new Scene();
  readonly camera = new PerspectiveCamera(30, 16 / 9, 1, 60);
  /** Which boil frame this frame draws, for anything (the guy's face) painted per frame. */
  frame = 0;

  private time = 0;
  private readonly placed = new Set<Placed>();
  private readonly materials = new Map<string, ShaderMaterial>();
  private readonly ids = new Map<Texture, number>();
  private readonly shadowTexture: Texture;
  private readonly shadowMaterial: ShaderMaterial;
  private readonly shadowGeometry: BufferGeometry;
  private readonly hullMaterial: ShaderMaterial;

  // Shared by reference across the materials, so setting one sets them all.
  private readonly shared = {
    u_lightDir: { value: new Vector3() },
    u_shadeTint: { value: rgb(SHADE_TINT) },
    u_ink: { value: rgb(INK_LINEAR) },
    u_tint: { value: new Vector3(1, 1, 1) },
    u_boil: { value: 0 },
    u_grain: { value: GRAIN_PX },
  };
  private readonly inkColor = { value: new Vector4() };
  private readonly shadowColor = { value: new Vector4() };

  constructor(canvas: HTMLCanvasElement) {
    this.webgl = new WebGLRenderer({ canvas, antialias: true, alpha: false, powerPreference: "high-performance" });
    // The shaders and the clear colour are sRGB already: three is to pass them straight through.
    ColorManagement.enabled = false;
    this.webgl.outputColorSpace = LinearSRGBColorSpace;
    this.webgl.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));

    this.shadowTexture = paintShadow().texture();
    this.shadowMaterial = new ShaderMaterial({
      vertexShader: FLAT_VERTEX,
      fragmentShader: FLAT_FRAGMENT,
      uniforms: { u_color: this.shadowColor, u_useMap: { value: 1 }, u_texture: { value: this.shadowTexture } },
      transparent: true,
      depthWrite: false,
      side: DoubleSide,
    });
    this.shadowGeometry = new BufferGeometry();
    this.shadowGeometry.setAttribute("position", new BufferAttribute(new Float32Array([-0.5, 0, -0.5, 0.5, 0, -0.5, 0.5, 0, 0.5, -0.5, 0, 0.5]), 3));
    this.shadowGeometry.setAttribute("uv", new BufferAttribute(new Float32Array([0, 0, 1, 0, 1, 1, 0, 1]), 2));
    this.shadowGeometry.setIndex([0, 2, 1, 0, 3, 2]);
    this.hullMaterial = new ShaderMaterial({
      vertexShader: FLAT_VERTEX,
      fragmentShader: FLAT_FRAGMENT,
      uniforms: { u_color: this.inkColor, u_useMap: { value: 0 }, u_texture: { value: null } },
      side: BackSide,
    });
  }

  /** The room's light in world space, from the camera as it stands; call after pointing the camera. */
  worldLight(out: Vector3 = new Vector3()): Vector3 {
    this.camera.updateMatrixWorld();
    return out.copy(LIGHT).transformDirection(this.camera.matrixWorld);
  }

  /** The screen is [width] x [height] CSS pixels. Below 16:9 the frame keeps its width and shows more above and below, which is the same picture letterboxed without bars. */
  resize(width: number, height: number) {
    this.webgl.setSize(width, height, false);
    const aspect = width / height;
    this.camera.aspect = aspect;
    const wide = 16 / 9;
    this.camera.fov = aspect >= wide ? FOV : (2 * Math.atan((Math.tan((FOV / 2) * (Math.PI / 180)) * wide) / aspect) * 180) / Math.PI;
    this.camera.updateProjectionMatrix();
    const size = this.webgl.getDrawingBufferSize(new Vector2());
    this.shared.u_grain.value = GRAIN_PX * Math.max(1, Math.min(size.y, size.x / wide) / GRAIN_ROWS);
  }

  /** Starts a frame in the room's light [tint] (white at midday), [delta] seconds after the last. */
  begin(tint: Rgba, delta: number) {
    this.time += Math.min(delta, 0.1);
    const frame = Math.trunc(this.time * BOIL_FPS) % BOIL_FRAMES;
    if (frame !== this.frame) {
      this.frame = frame;
      for (const p of this.placed) if (p.hull) p.hull.geometry = p.model.hulls[frame];
    }
    this.shared.u_boil.value = frame;
    this.shared.u_tint.value.set(tint.r, tint.g, tint.b);
    this.shared.u_lightDir.value.copy(LIGHT).transformDirection(this.camera.matrixWorld);
    this.inkColor.value.set(INK.r * tint.r, INK.g * tint.g, INK.b * tint.b, 1);
    this.shadowColor.value.set(tint.r, tint.g, tint.b, 1);
  }

  /** Puts [model] in the scene, at the origin until [Placed.set] says where; [overlay] goes on its textured batch. */
  add(model: CrayonModel, overlay?: Overlay): Placed {
    const p = new Placed(model);
    for (const b of model.batches) {
      const mesh = new Mesh(b.geometry, this.crayonMaterial(b.texture, model.hatchScale, b.texture ? overlay : undefined));
      this.place(mesh, ORDER.solid);
      p.meshes.push(mesh);
    }
    if (model.hulls.length) {
      p.hull = new Mesh(model.hulls[this.frame], this.hullMaterial);
      this.place(p.hull, ORDER.hull);
    }
    this.placed.add(p);
    return p;
  }

  remove(p: Placed) {
    for (const m of p.meshes) this.scene.remove(m);
    if (p.hull) this.scene.remove(p.hull);
    this.placed.delete(p);
  }

  /** A paper-shaded mesh (position, uv, a_color) with its painted [texture], in the room's light, or in its own: set [ownTint]'s value. */
  surface(geometry: BufferGeometry, texture: Texture, ownTint?: { value: Vector3 }): Mesh {
    const material = new ShaderMaterial({
      vertexShader: PAPER_VERTEX,
      fragmentShader: PAPER_FRAGMENT,
      uniforms: { u_texture: { value: texture }, u_tint: ownTint ?? this.shared.u_tint },
      precision: "mediump",
      side: FrontSide,
    });
    const mesh = new Mesh(geometry, material);
    this.place(mesh, ORDER.surface);
    return mesh;
  }

  /** A hatched shadow on the unit square, placed (and stretched) by setting its matrix. */
  shadow(): Mesh {
    const mesh = new Mesh(this.shadowGeometry, this.shadowMaterial);
    this.place(mesh, ORDER.shadow);
    return mesh;
  }

  removeMesh(mesh: Mesh, dispose = false) {
    this.scene.remove(mesh);
    if (dispose) {
      mesh.geometry.dispose();
      (mesh.material as Material).dispose();
    }
  }

  render(background: Rgba) {
    this.webgl.setClearColor(new Color(background.r, background.g, background.b), 1);
    this.webgl.render(this.scene, this.camera);
  }

  private place(mesh: Mesh, order: number) {
    mesh.matrixAutoUpdate = false;
    mesh.frustumCulled = false;
    mesh.renderOrder = order;
    this.scene.add(mesh);
  }

  private crayonMaterial(texture: Texture | null, hatchScale: number, overlay?: Overlay): ShaderMaterial {
    const key = `${texture ? this.textureId(texture) : "-"}/${hatchScale}`;
    const cached = overlay ? undefined : this.materials.get(key);
    if (cached) return cached;
    const defines: Record<string, string> = {};
    if (texture) defines.MAP = "";
    if (overlay) defines.FACE = "";
    const uniforms: Record<string, { value: unknown }> = { ...this.shared, u_hatchScale: { value: hatchScale } };
    if (texture) uniforms.u_map = { value: texture };
    if (overlay) {
      uniforms.u_face = overlay.texture;
      uniforms.u_faceRect = { value: overlay.rect };
    }
    const m = new ShaderMaterial({ vertexShader: CRAYON_VERTEX, fragmentShader: CRAYON_FRAGMENT, uniforms, defines, side: FrontSide });
    if (!overlay) this.materials.set(key, m);
    return m;
  }

  private textureId(t: Texture): number {
    let id = this.ids.get(t);
    if (id === undefined) {
      id = this.ids.size;
      this.ids.set(t, id);
    }
    return id;
  }
}

const FOV = 30;

/** A hatched lavender patch, 256 x 200 design units. */
function paintShadow(): CrayonCanvas {
  const x = new CrayonCanvas(128, 100, 0.5);
  const rand = rng(9);
  x.clip(blobPath(128, 100, 110, 80, rand, 0.06));
  for (let k = -260; k < 260; k += 8) {
    const ya = 190 + (rand() - 0.5) * 8;
    const yb = 10 + (rand() - 0.5) * 8;
    x.stroke([128 + k - 90, ya, 128 + k + 90, yb], 3.5, SHADOW, 0.45);
  }
  x.unclip();
  return x;
}
