import {
  BufferAttribute, BufferGeometry, CanvasTexture, Color, DoubleSide, LinearFilter, Mesh, MeshBasicMaterial, OrthographicCamera, PlaneGeometry, Scene,
} from "three";

/**
 * What's drawn over the room in screen space, in the same canvas: each [Panel] is a small 2D
 * canvas uploaded as a texture when it's redrawn, and only moved otherwise, so nothing redraws
 * every frame but what changed. The page keeps one canvas and no DOM over it (each full-screen
 * layer costs the TV about 7 ms). Coordinates are CSS pixels from the top-left.
 */
export class Layer {
  readonly scene = new Scene();
  readonly camera = new OrthographicCamera(0, 1, 0, -1, -10, 10);
  width = 1;
  height = 1;
  /** Drawing-buffer pixels to a CSS pixel, so panels are painted at the size they show. */
  ratio = 1;
  private readonly plane = new PlaneGeometry(1, 1).translate(0.5, -0.5, 0);

  resize(width: number, height: number, ratio: number) {
    this.width = width;
    this.height = height;
    this.ratio = ratio;
    this.camera.right = width;
    this.camera.bottom = -height;
    this.camera.updateProjectionMatrix();
  }

  /** A panel drawn above those with a lower [order]. */
  panel(order: number): Panel {
    return new Panel(this, this.plane, order);
  }

  /** A filled triangle in [color], set each frame by its corners (a bubble's tail). */
  triangle(color: string, order: number): Triangle {
    return new Triangle(this, color, order);
  }

  /** [x] in CSS pixels, snapped to a whole drawing-buffer pixel so a panel's texels land on the screen's. */
  snap(x: number): number {
    return Math.round(x * this.ratio) / this.ratio;
  }
}

/** One 2D canvas over the room: [draw] paints it at a size, [place] moves it. Hidden until drawn. */
export class Panel {
  readonly canvas = document.createElement("canvas");
  private readonly ctx = this.canvas.getContext("2d")!;
  private readonly material = new MeshBasicMaterial({ transparent: true, depthTest: false, depthWrite: false, premultipliedAlpha: true, toneMapped: false });
  private readonly mesh: Mesh;
  private texture: CanvasTexture | null = null;
  /** The size it was last drawn at, in CSS pixels. */
  w = 0;
  h = 0;

  constructor(private readonly layer: Layer, plane: PlaneGeometry, order: number) {
    this.mesh = new Mesh(plane, this.material);
    this.mesh.renderOrder = order;
    this.mesh.frustumCulled = false;
    this.mesh.visible = false;
    layer.scene.add(this.mesh);
  }

  /** Repaints it [w] x [h] CSS pixels; [paint] draws in CSS pixels. */
  draw(w: number, h: number, paint: (ctx: CanvasRenderingContext2D) => void) {
    const r = this.layer.ratio;
    const pw = Math.max(1, Math.ceil(w * r));
    const ph = Math.max(1, Math.ceil(h * r));
    const resized = pw !== this.canvas.width || ph !== this.canvas.height;
    if (resized) {
      this.canvas.width = pw;
      this.canvas.height = ph;
    }
    const ctx = this.ctx;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, pw, ph);
    ctx.setTransform(r, 0, 0, r, 0, 0);
    paint(ctx);
    this.w = pw / r;
    this.h = ph / r;
    // A texture's size is fixed once uploaded: a new size needs a new one.
    if (resized || !this.texture) {
      this.texture?.dispose();
      const t = new CanvasTexture(this.canvas);
      t.minFilter = t.magFilter = LinearFilter;
      t.generateMipmaps = false;
      t.premultiplyAlpha = true;
      this.texture = t;
      this.material.map = t;
      this.material.needsUpdate = true;
    } else this.texture.needsUpdate = true;
    this.mesh.scale.set(this.w, this.h, 1);
    this.mesh.updateMatrix();
  }

  /** Its top-left at ([x], [y]) CSS pixels. */
  place(x: number, y: number) {
    this.mesh.position.set(this.layer.snap(x), -this.layer.snap(y), 0);
    this.mesh.updateMatrix();
    this.mesh.visible = this.texture !== null;
  }

  hide() {
    this.mesh.visible = false;
  }

  get shown(): boolean {
    return this.mesh.visible;
  }
}

export class Triangle {
  private readonly positions = new BufferAttribute(new Float32Array(9), 3);
  private readonly mesh: Mesh;

  constructor(layer: Layer, color: string, order: number) {
    const g = new BufferGeometry();
    g.setAttribute("position", this.positions);
    this.mesh = new Mesh(g, new MeshBasicMaterial({ color: new Color(color), side: DoubleSide, depthTest: false, depthWrite: false, toneMapped: false }));
    this.mesh.renderOrder = order;
    this.mesh.frustumCulled = false;
    this.mesh.visible = false;
    layer.scene.add(this.mesh);
  }

  /** Corners in CSS pixels from the top-left. */
  set(ax: number, ay: number, bx: number, by: number, cx: number, cy: number) {
    const p = this.positions;
    p.setXYZ(0, ax, -ay, 0);
    p.setXYZ(1, cx, -cy, 0);
    p.setXYZ(2, bx, -by, 0);
    p.needsUpdate = true;
    this.mesh.visible = true;
  }

  hide() {
    this.mesh.visible = false;
  }
}
