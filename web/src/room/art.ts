import { DataTexture, Matrix4, NearestFilter, RGBAFormat, UnsignedByteType, type Mesh, type ShaderMaterial, type Texture } from "three";
import type { DrawingView, Entity, LifeState } from "../../../protocol/ts/protocol";
import { Z_AXIS, rotate, scale, translate } from "../crayon/math";
import type { CrayonModel } from "../crayon/model";
import type { CrayonRenderer, Placed } from "../crayon/renderer";
import type { Blob, Pose } from "../guy/blob";
import { EaselPlay, pictureColors, toBytes } from "./easel";
import { constants, type Models } from "./parts";
import type { RoomDef, Stand } from "./room";
import { quad } from "./shell";

/** A finished piece: how many sparkles fly off it, how far, and how long its title stays over his head. */
const SPARKLES = 7;
const SPARKLE_SPREAD = 0.35;
const GOLD = 0xf6d26b;
const ANNOUNCE_SECONDS = 4;
/** Sparkles last, grow and rise as the music notes do, a little slower. */
const SPARKLE_LIFE = 2.6;
const SPARKLE_SIZE = 1.7;
const SPARKLE_RISE = 0.18;

const hex = (rgb: number) => `#${(rgb & 0xffffff).toString(16).padStart(6, "0")}`;
/** How light 0xRRGGBB [rgb] is, 0..1. */
const luma = (rgb: number) => (0.3 * ((rgb >> 16) & 0xff) + 0.59 * ((rgb >> 8) & 0xff) + 0.11 * (rgb & 0xff)) / 255;
/** [rgb] moved [by] of the way to white. */
const lighten = (rgb: number, by: number) =>
  [16, 8, 0].reduce((out, shift) => out | (Math.trunc(((rgb >> shift) & 0xff) + (255 - ((rgb >> shift) & 0xff)) * by) << shift), 0);

/** A picture's texture: nearest-filtered, so the cells stay crisp. */
function pictureTexture(bytes: Uint8Array, size: number): DataTexture {
  const t = new DataTexture(bytes, size, size, RGBAFormat, UnsignedByteType);
  t.magFilter = t.minFilter = NearestFilter;
  t.needsUpdate = true;
  return t;
}

/** What a picture's square holds until it has a picture: it doesn't show then. */
const BLANK = pictureTexture(new Uint8Array([255, 255, 255, 255]), 1);

interface Sparkle {
  placed: Placed;
  x: number;
  y: number;
  z: number;
  phase: number;
  age: number;
}

/**
 * His art in the room, ported from `RoomView.kt` and `PaintScene.kt`: hung pieces in frames on the
 * room's wall spots, oldest first; on each stand (the desk for a sketch, the easel for a painting)
 * the piece he's making there, played out stroke by stroke at the host's pace ([EaselPlay]), or
 * else his newest unsold piece of its item; the brush or crayon in his hand and the palette in the
 * other; and when a piece is done, sparkles off it in its own colours and its title announced.
 *
 * [follow] runs before the guy is posed, [draw] after. Pieces' pictures come from the drawings log
 * (`logs.drawings`, the newest hundred); one older than that hangs as an empty frame.
 */
export class ArtLayer {
  readonly play = new EaselPlay();
  /** The title of the piece just finished, for his bubble, and how long ago it was announced. */
  announced: string | null = null;
  announceAge = 0;

  private run = "";
  private readonly textures = new Map<number, Texture>();
  private drawingsFrom: DrawingView[] | null = null;
  private readonly drawings = new Map<number, DrawingView>();

  private work: DataTexture | null = null;
  private workVersion = -1;
  private celebrated: number | null = null;

  private readonly frames: Placed[] = [];
  private readonly framePictures: Mesh[] = [];
  private readonly standPictures = new Map<Stand, Mesh>();
  private canvas: Placed | null = null;
  private tool: { key: string; placed: Placed } | null = null;
  private palette: { key: string; placed: Placed } | null = null;
  private readonly sparkles: Sparkle[] = [];
  private readonly at = new Matrix4();

  constructor(private readonly r: CrayonRenderer, private readonly models: Models, private readonly room: RoomDef) {}

  /**
   * Plays the easel on by [delta] seconds and returns the stand of the piece he's at, or null. He's
   * at it while its verb is under way, and after, while the clock waits on the drawing or on the
   * show (`held_for` "draw" or "art") as the last strokes go on and he flourishes.
   */
  follow(state: LifeState, moving: boolean, delta: number): Stand | null {
    this.play.update(state.easel, state.fast.easel_time, delta);
    const piece = this.play.piece;
    const stand = piece ? this.room.stands.find((s) => s.item === piece.item) : undefined;
    if (!stand || moving || !state.things.entities.some((e) => e.id === stand.id)) return null;
    const doing = state.fast.doing;
    const held = state.fast.held_for;
    return doing === stand.activity || (doing === null && (held.includes("draw") || held.includes("art"))) ? stand : null;
  }

  /** Where his tool's tip is on the piece at [stand] (on [e]), for [pose]. */
  pose(pose: Pose, stand: Stand | null, e: Entity | undefined) {
    const play = this.play;
    pose.art = stand && play.piece ? play.phase : null;
    pose.artTime = play.phaseTime;
    pose.brush = stand?.canvas ?? true;
    pose.tipLifted = play.lifted;
    if (!stand || !e) return;
    const s = stand.size;
    if (stand.flat) pose.tip.set(e.x + stand.x + (play.u - 0.5) * s, stand.y, e.y + stand.z + (play.v - 0.5) * s);
    else pose.tip.set(e.x + stand.x + (play.u - 0.5) * s, stand.y + (0.5 - play.v) * s, e.y + stand.z);
  }

  /** Frames, pictures, the canvas, what's in his hands and the sparkles, for this frame; [placed] is where each thing stands. */
  draw(state: LifeState, making: Stand | null, placed: (id: string) => Matrix4 | null, blob: Blob, delta: number) {
    this.syncDrawings(state);
    this.drawFrames(state, placed);
    this.drawStands(state, making, placed);
    this.drawHands(blob, making?.canvas ?? true);
    if (making && this.play.phase === "flourish" && this.play.piece && this.celebrated !== this.play.piece.tick) {
      this.celebrated = this.play.piece.tick;
      const e = state.things.entities.find((t) => t.id === making.id);
      if (e) this.celebrate(making, e);
    }
    this.announceAge += delta;
    if (this.announced !== null && this.announceAge > ANNOUNCE_SECONDS) this.announced = null;
    this.drawSparkles(delta);
  }

  /** The kept drawings by piece, and their textures, built the first time each shows; a new run starts afresh. */
  private syncDrawings(state: LifeState) {
    if (state.run.run_id !== this.run) {
      this.run = state.run.run_id;
      for (const t of this.textures.values()) t.dispose();
      this.textures.clear();
      this.celebrated = null;
    }
    if (state.logs.drawings === this.drawingsFrom) return;
    this.drawingsFrom = state.logs.drawings;
    this.drawings.clear();
    for (const d of state.logs.drawings) if (d.piece !== null && d.size > 0) this.drawings.set(d.piece, d);
  }

  private texture(piece: number): Texture | null {
    let t = this.textures.get(piece);
    if (!t) {
      const d = this.drawings.get(piece);
      if (!d) return null;
      t = pictureTexture(toBytes(pictureColors(d.rows, d.palette)), d.size);
      this.textures.set(piece, t);
    }
    return t;
  }

  private drawFrames(state: LifeState, placed: (id: string) => Matrix4 | null) {
    const spots = this.room.wallSpots;
    const hung = state.gallery.art.filter((p) => p.hung).slice(0, spots.length);
    for (let i = 0; i < spots.length; i++) {
      const spot = spots[i];
      const anchor = placed(spot.on);
      const piece = anchor ? hung[i] : undefined;
      const frame = (this.frames[i] ??= this.r.add(this.models.prop("frame")));
      const picture = (this.framePictures[i] ??= this.picture(constants.FRAME_ART, false));
      frame.show(piece !== undefined);
      picture.visible = false;
      if (!piece) continue;
      translate(this.at.copy(anchor!), spot.x, spot.y, spot.z);
      frame.set(this.at);
      this.setPicture(picture, this.texture(piece.id), translate(this.at, 0, constants.FRAME / 2, constants.FRAME_ART_Z));
    }
  }

  /**
   * Each stand: the piece he's making there while he's at it, or else his newest unsold one of its
   * item. The easel holds a blank canvas while he has one, is painting, or has a painting to show.
   */
  private drawStands(state: LifeState, making: Stand | null, placed: (id: string) => Matrix4 | null) {
    const pieces = state.gallery.art;
    let canvasAt: Matrix4 | null = null;
    for (const stand of this.room.stands) {
      let picture = this.standPictures.get(stand);
      if (!picture) this.standPictures.set(stand, (picture = this.picture(stand.size, stand.flat)));
      picture.visible = false;
      const e = state.things.entities.find((t) => t.id === stand.id);
      if (!e) continue;
      const here = making === stand;
      let texture: Texture | null = null;
      if (here) texture = this.workTexture();
      else {
        const newest = pieces.findLast((p) => !p.hung && p.item === stand.item);
        if (newest) texture = this.texture(newest.id);
      }
      if (stand.canvas && (here || texture !== null || (state.wallet.owns["canvas"] ?? 0) > 0)) canvasAt = placed(stand.id);
      this.setPicture(picture, texture, this.at.makeTranslation(e.x + stand.x, stand.y, e.y + stand.z));
    }
    if (canvasAt) this.canvas ??= this.r.add(this.models.prop("canvas"));
    this.canvas?.show(canvasAt !== null);
    if (canvasAt) this.canvas!.set(canvasAt);
  }

  /** The piece under way as far as it has gone on. */
  private workTexture(): Texture | null {
    const play = this.play;
    if (!play.piece || play.size === 0) return null;
    if (!this.work || this.work.image.data !== play.bytes) {
      this.work?.dispose();
      this.work = pictureTexture(play.bytes, play.size);
      this.workVersion = play.version;
    } else if (this.workVersion !== play.version) {
      this.work.needsUpdate = true;
      this.workVersion = play.version;
    }
    return this.work;
  }

  /** The brush ([brush]) or crayon in his working hand, in the brush's colour, and the palette with the piece's colours in the other. */
  private drawHands(blob: Blob, brush: boolean) {
    const play = this.play;
    this.tool = this.swap(this.tool, blob.toolShown, `${brush}/${play.color}`, () => this.models.prop(brush ? "tool_brush" : "tool_crayon", hex(play.color)), blob.toolAt);
    const colors = play.piece?.palette ?? [];
    this.palette = this.swap(this.palette, blob.paletteShown && colors.length > 0, colors.join(","), () => this.models.palette(colors.map(hex)), blob.paletteAt);
  }

  /** [current] shown at [at] when [show], rebuilt when its [key] changes. */
  private swap(current: { key: string; placed: Placed } | null, show: boolean, key: string, model: () => CrayonModel, at: Matrix4) {
    if (show && current?.key !== key) {
      if (current) this.r.remove(current.placed);
      current = { key, placed: this.r.add(model()) };
    }
    current?.placed.show(show);
    if (show) current!.placed.set(at);
    return current;
  }

  /** The piece is done: sparkles off it in its own colours, and its title announced. */
  private celebrate(stand: Stand, e: Entity) {
    const p = this.play.piece!;
    this.announced = `"${p.title}"`;
    this.announceAge = 0;
    // Its colours lightened, leaving out the dark ones (a black star reads as a cross); gold when none are left.
    const light = p.palette.slice(1).filter((c) => luma(c) > 0.3).map((c) => lighten(c, 0.35));
    const colors = light.length ? light : [GOLD];
    const y = stand.flat ? stand.y + 0.05 : stand.y + stand.size / 2;
    for (let i = 0; i < SPARKLES; i++) {
      const placed = this.r.add(this.models.prop("sparkle", hex(colors[i % colors.length]))).show(false);
      this.sparkles.push({ placed, x: e.x + stand.x, y, z: e.y + stand.z + 0.05, phase: Math.random() * Math.PI * 2, age: 0 });
    }
  }

  /** Sparkles burst out from the piece, drift up and spin as they fade. */
  private drawSparkles(delta: number) {
    for (let i = this.sparkles.length - 1; i >= 0; i--) {
      const s = this.sparkles[i];
      s.age += delta;
      if (s.age > SPARKLE_LIFE) {
        this.r.remove(s.placed);
        this.sparkles.splice(i, 1);
        continue;
      }
      const grow = SPARKLE_SIZE * Math.min(s.age / 0.25, 1) * Math.min((SPARKLE_LIFE - s.age) / 0.7, 1);
      // Scale 0 has no inverse for the shader's normals.
      if (grow <= 0) {
        s.placed.show(false);
        continue;
      }
      const out = SPARKLE_SPREAD * (1 - 1 / (1 + 3 * s.age));
      const twinkle = grow * (0.75 + 0.2 * Math.sin(s.age * 14 + s.phase));
      this.at.makeTranslation(s.x + out * Math.cos(s.phase), s.y + out * Math.sin(s.phase) + SPARKLE_RISE * s.age, s.z);
      scale(rotate(this.at, Z_AXIS, 18 * Math.sin(s.age * 3 + s.phase)), twinkle, twinkle, twinkle);
      s.placed.show(true).set(this.at);
    }
  }

  /** A square for a piece's picture, [size] wide, upright or flat; hidden until it has one. */
  private picture(size: number, flat: boolean): Mesh {
    const mesh = this.r.surface(quad(size, flat), BLANK);
    mesh.visible = false;
    return mesh;
  }

  private setPicture(mesh: Mesh, texture: Texture | null, at: Matrix4) {
    mesh.visible = texture !== null;
    if (!texture) return;
    (mesh.material as ShaderMaterial).uniforms.u_texture.value = texture;
    mesh.matrix.copy(at);
    mesh.matrixWorldNeedsUpdate = true;
  }
}
