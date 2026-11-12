import { Matrix4, Vector3, type BufferGeometry, type Mesh } from "three";
import type { Content, Entity, LifeState } from "../../../protocol/ts/protocol";
import { Y_AXIS, Z_AXIS, rotate, scale, translate } from "../crayon/math";
import type { CrayonRenderer, Placed } from "../crayon/renderer";
import { Blob, newPose } from "../guy/blob";
import type { Place } from "../stream/track";
import { ArtLayer } from "./art";
import { lightAt, type Light } from "./light";
import { RoomPaint } from "./paint";
import { Models, constants, flatItem, wallItem } from "./parts";
import { BEDROOM } from "./room";
import { Terrain, buildShell } from "./shell";

const FOV = 30;
const WIDE = 16 / 9;
/** Degrees the camera looks down. */
const PITCH = 40;
/** Room beyond the side walls, in tiles, so the walls don't touch the screen edge. */
const SIDE_MARGIN = 0.9;

/** The record's turn (degrees a second), and the notes: seconds apart, how long each lasts, and how fast it rises (metres a second). */
const DISC_SPEED = 200;
const NOTE_EVERY = 0.9;
const NOTE_LIFE = 2.6;
const NOTE_RISE = 0.3;
/** Notes are drawn this much bigger than their part list, to read from across the room. */
const NOTE_SIZE = 1.7;
const NOTE_COLORS = ["#f2928a", "#8fb0ec", "#9fd49a", "#c9a0e6"];
/** What he stands, sits or lies on, by item id, and how high its top is. */
const SURFACES: Record<string, number> = { bed: 0.3, chair: 0.38 };
/** Furniture shadows: how high over the floor (above the rug), and how much wider than the footprint. */
const SHADOW_LIFT = 0.04;
const SHADOW_SPREAD = 1.2;
/** (dx, dy) toward the room, from a wall tile. */
const INWARD: [number, number][] = [[0, 1], [1, 0], [-1, 0], [0, -1]];
const ACTIVE = new Set(["executing", "validated", "submitted"]);

/** One note on its way up, from where it started. */
interface Note {
  placed: Placed;
  x: number;
  y: number;
  z: number;
  phase: number;
  age: number;
}

/** One thing on the map: its model and shadow, and where it stands. */
interface Thing {
  placed: Placed;
  shadow: Mesh | null;
  at: Matrix4;
  key: string;
}

/**
 * The room in 3D, ported from `RoomView.kt`: a fixed-camera cutaway of the room (no front wall, no
 * ceiling), furniture placed where the state says, the guy as an animated [Blob], light and the
 * window's sky following the game clock. What he's busy with shows: the book in his hands, the guitar
 * off its peg and in his arms, the record turning and notes rising from it.
 *
 * Everything is drawn in the hand-drawn look by one [CrayonRenderer]. His art is [ArtLayer]'s, and
 * where pieces are made and hang is the room's [RoomDef]. The app's HUD isn't here yet:
 * [Blob.headTop] is where the bubble's anchored, and [ArtLayer.announced] a finished piece's title, for when it is.
 */
export class RoomView {
  private readonly paint = new RoomPaint();
  private readonly models = new Models((name) => this.paint.texture(name));
  private readonly blob: Blob;
  private readonly pose = newPose();
  private readonly art: ArtLayer;
  private readonly glassTint = { value: new Vector3(1, 1, 1) };

  private framedRows = "";
  private map: Terrain | null = null;
  private surfaces: Mesh[] = [];
  private glass: Mesh | null = null;
  private fixtures: Placed | null = null;
  private hourHand: Placed | null = null;
  private minuteHand: Placed | null = null;

  private readonly things = new Map<string, Thing>();
  private thingsFrom: LifeState["things"] | null = null;

  private shelf: Placed | null = null;
  private shelfFrom: LifeState["shop"] | null = null;
  private shelfKey = "";
  private guitar: Placed | null = null;
  private readonly guitarAt = new Matrix4();

  private heldBook: Placed | null = null;
  private heldBookColor = "";
  private heldGuitar: Placed | null = null;

  private disc: Placed | null = null;
  private discTurn = 0;
  private readonly notes: Note[] = [];
  private recordNoteIn = 0;
  private guitarNoteIn = 0;

  private readonly workAt = new Vector3();
  private readonly at = new Matrix4();
  private light: Light = lightAt(12);

  constructor(private readonly r: CrayonRenderer) {
    this.blob = new Blob(r, constants.TOOL_TIP);
    this.art = new ArtLayer(r, this.models, BEDROOM);
  }

  /** What's beyond the room, for clearing to. */
  get background() {
    return this.light.background;
  }

  /** One frame of the live world; [place] is where he is between publishes. */
  render(state: LifeState, content: Content, place: Place, delta: number) {
    const fast = state.fast;
    const guy = fast.entities.find((e) => e.kind === "guy");
    if (!guy) return;
    this.frame(content);
    this.lightFor(fast.clock?.time ?? null);
    this.syncThings(state);
    this.syncShelf(state, content);

    const agent = fast.agents[0] ?? null;
    const intent = agent?.intent ?? null;
    const pose = this.pose;
    pose.x = place.x;
    pose.z = place.y;
    pose.facing = guy.facing_degrees;
    pose.moving = place.moving;
    // At a piece, he stays in its pose after its verb while the clock waits for it to finish.
    const making = this.art.follow(state, place.moving, delta);
    pose.doing = making?.activity ?? fast.doing;
    pose.energy = fast.needs?.energy ?? 70;
    pose.mood = fast.needs?.mood ?? 60;
    pose.music = fast.music !== null;
    pose.thinking = fast.thinking_ms !== null || (intent?.type === "do" && intent.activity === "think" && ACTIVE.has(agent?.status?.state ?? ""));
    // On the bed's tile he is on the mattress; standing still on a chair's tile, he sits on it.
    const under = state.things.entities.find((e) => e.id in SURFACES && Math.trunc(e.x) === Math.trunc(place.x) && Math.trunc(e.y) === Math.trunc(place.y))?.id;
    pose.standY = under === undefined || (under === "chair" && pose.moving) ? 0 : SURFACES[under];
    // Drawing, writing or painting, his hand goes to the page or the canvas.
    const station = pose.doing ? BEDROOM.work[pose.doing] : undefined;
    const stand = station ? state.things.entities.find((e) => e.id === station.id) : undefined;
    pose.workAt = station && stand ? this.workAt.set(stand.x + station.x, station.y, stand.y + station.z) : null;
    pose.workFlat = station?.flat ?? true;
    pose.workSlide = station?.slide ?? 0;
    this.art.pose(pose, making, making ? stand : undefined);
    this.blob.update(delta, pose);

    this.r.begin(this.light.tint, delta);
    this.art.draw(state, making, (id) => this.things.get(id)?.at ?? null, this.blob, delta);
    this.drawHeld(intent?.type === "do" ? intent.item : null);
    this.drawMusic(fast.music !== null, delta);
  }

  /** Points the camera at the room and builds it (once per map): a front view from above, the whole room in frame. */
  private frame(content: Content) {
    const rows = content.map.rows.join("\n");
    if (rows === this.framedRows) return;
    this.framedRows = rows;
    const map = (this.map = new Terrain(content.map.rows));
    const halfFovX = Math.tan((FOV / 2) * (Math.PI / 180)) * WIDE;
    const distance = (map.width / 2 + SIDE_MARGIN) / halfFovX;
    const target = new Vector3(map.width / 2, 1.1, map.height / 2 + 0.2);
    const pitch = PITCH * (Math.PI / 180);
    const camera = this.r.camera;
    camera.position.copy(target).add(new Vector3(0, Math.sin(pitch) * distance, Math.cos(pitch) * distance));
    camera.up.set(0, 1, 0);
    camera.lookAt(target);
    camera.updateMatrixWorld();

    for (const m of this.surfaces) this.r.removeMesh(m);
    if (this.fixtures) this.r.remove(this.fixtures);
    const shell = buildShell(map, this.r.worldLight());
    const surface = (g: BufferGeometry, name: string) => this.r.surface(g, this.paint.texture(name)!);
    this.glass = this.r.surface(shell.glass, this.paint.texture("white")!, this.glassTint);
    const glassAt = new Matrix4().makeTranslation(map.width * constants.WINDOW_AT, (constants.WINDOW_BOTTOM + constants.WINDOW_TOP) / 2, 1.012);
    this.glass.matrix.copy(glassAt);
    this.glass.matrixWorldNeedsUpdate = true;
    this.surfaces = [surface(shell.floor, "floor"), surface(shell.walls, "wall"), surface(shell.section, "section"), this.glass];
    this.fixtures = this.r.add(this.models.fixtures(map.width));
    this.hourHand ??= this.r.add(this.models.prop("hand_hour"));
    this.minuteHand ??= this.r.add(this.models.prop("hand_minute"));
    this.guitarAt.makeTranslation(map.width * constants.GUITAR_AT, 0, 1);
    this.thingsFrom = null;
    this.shelfKey = "";
    this.shelfFrom = null;
  }

  /** Ambient, sun, sky, background and the wall clock for the time of day; [time] "HH:MM", or noon without one. */
  private lightFor(time: string | null) {
    let hour = 12;
    if (time) {
      const [h, m] = time.split(":").map((v) => parseInt(v, 10) || 0);
      hour = h + m / 60;
    }
    this.light = lightAt(hour);
    const { r, g, b } = this.light.sky;
    this.glassTint.value.set(r, g, b);
    const clockX = this.map!.width * constants.CLOCK_AT;
    this.hourHand!.set(rotate(this.at.makeTranslation(clockX, constants.CLOCK_Y, 1.06), Z_AXIS, -30 * hour));
    this.minuteHand!.set(rotate(this.at.makeTranslation(clockX, constants.CLOCK_Y, 1.07), Z_AXIS, -360 * hour));
  }

  /**
   * Things go at their tile; wall items sit against the wall face that looks into the room. Things
   * standing on the floor get a hatched shadow the size of their footprint. Runs when [state]'s things change.
   */
  private syncThings(state: LifeState) {
    if (state.things === this.thingsFrom) return;
    this.thingsFrom = state.things;
    const seen = new Set<string>();
    for (const e of state.things.entities) {
      if (e.carried_by !== null || (e.kind === "door" && e.state === "open")) continue;
      seen.add(e.id);
      this.place(e);
    }
    for (const [id, t] of this.things) {
      if (seen.has(id)) continue;
      this.r.remove(t.placed);
      if (t.shadow) this.r.removeMesh(t.shadow);
      this.things.delete(id);
    }
  }

  private place(e: Entity) {
    const key = `${e.kind}/${e.x}/${e.y}`;
    if (this.things.get(e.id)?.key === key) return;
    const old = this.things.get(e.id);
    if (old) {
      this.r.remove(old.placed);
      if (old.shadow) this.r.removeMesh(old.shadow);
    }
    const map = this.map!;
    const model = this.models.item(e.id, e.kind);
    const at = new Matrix4();
    const tx = Math.trunc(e.x);
    const ty = Math.trunc(e.y);
    let shadow: Mesh | null = null;
    if (map.at(tx, ty) === "#" && wallItem(e.id, e.kind)) {
      const [dx, dy] = INWARD.find(([ix, iy]) => map.at(tx + ix, ty + iy) === ".") ?? [0, 1];
      const yaw = dx === 1 ? 90 : dx === -1 ? -90 : dy === -1 ? 180 : 0;
      rotate(at.makeTranslation(tx + 0.5 + dx * 0.5, 0, ty + 0.5 + dy * 0.5), Y_AXIS, yaw);
    } else {
      at.makeTranslation(e.x, 0, e.y);
      if (!flatItem(e.id, e.kind)) {
        const b = model.bounds;
        shadow = this.r.shadow();
        shadow.matrix.copy(at);
        translate(shadow.matrix, (b[0] + b[3]) / 2, SHADOW_LIFT, (b[2] + b[5]) / 2);
        scale(shadow.matrix, (b[3] - b[0]) * SHADOW_SPREAD, 1, (b[5] - b[2]) * SHADOW_SPREAD);
        shadow.matrixWorldNeedsUpdate = true;
      }
    }
    this.things.set(e.id, { placed: this.r.add(model).set(at), shadow, at, key });
  }

  /** His books and records on the wall shelf, and his guitar on the wall, once he owns any. Runs when what the shop knows changes. */
  private syncShelf(state: LifeState, content: Content) {
    if (state.shop === this.shelfFrom) return;
    this.shelfFrom = state.shop;
    const group = new Map(content.catalog.map((c) => [c.id, c.group]));
    const owned = state.shop.catalog.filter((c) => c.owned > 0);
    const books = owned.filter((c) => group.get(c.id) === "book").map((c) => c.id);
    const records = owned.filter((c) => group.get(c.id) === "record").map((c) => c.id);
    const key = `${books.join(",")}/${records.join(",")}`;
    if (key !== this.shelfKey) {
      this.shelfKey = key;
      if (this.shelf) this.r.remove(this.shelf);
      this.shelf = books.length || records.length ? this.r.add(this.models.shelf(books, records)) : null;
      this.shelf?.set(this.at.makeTranslation(this.map!.width * constants.SHELF_AT, 0, 1));
    }
    if (owned.some((c) => c.id === "guitar") && !this.guitar) this.guitar = this.r.add(this.models.prop("guitar")).set(this.guitarAt);
    else if (!owned.some((c) => c.id === "guitar") && this.guitar) {
      this.r.remove(this.guitar);
      this.guitar = null;
    }
  }

  /** The book in his hands, its spine's colour, or the guitar, off its peg while he plays it. */
  private drawHeld(what: string | null) {
    const blob = this.blob;
    const color = this.models.bookColor(what);
    if (blob.held === "book" && color !== this.heldBookColor) {
      if (this.heldBook) this.r.remove(this.heldBook);
      this.heldBook = this.r.add(this.models.prop("held_book", color));
      this.heldBookColor = color;
    }
    this.heldBook?.show(blob.held === "book");
    if (blob.held === "book") this.heldBook!.set(blob.heldAt);
    if (blob.held === "guitar") this.heldGuitar ??= this.r.add(this.models.prop("held_guitar"));
    this.heldGuitar?.show(blob.held === "guitar");
    if (blob.held === "guitar") this.heldGuitar!.set(blob.heldAt);
    this.guitar?.show(blob.held !== "guitar");
  }

  /**
   * The record turns while music plays, and notes rise from the player; more from his guitar while
   * he plays it. Each drifts up, wavering, and shrinks away.
   */
  private drawMusic(playing: boolean, delta: number) {
    const player = this.things.get("record_player")?.at ?? null;
    if (playing) this.discTurn = (this.discTurn + delta * DISC_SPEED) % 360;
    if (player) {
      this.disc ??= this.r.add(this.models.prop("disc"));
      this.at.copy(player);
      rotate(translate(this.at, constants.DISC_X, constants.DISC_Y, 0), Y_AXIS, -this.discTurn);
      this.disc.show(true).set(this.at);
    } else this.disc?.show(false);

    this.recordNoteIn -= delta;
    if (playing && player && this.recordNoteIn <= 0) {
      this.recordNoteIn = NOTE_EVERY;
      const p = new Vector3().setFromMatrixPosition(player);
      this.spawnNote(p.x + constants.DISC_X, p.y + constants.DISC_Y + 0.12, p.z + 0.1);
    }
    this.guitarNoteIn -= delta;
    if (this.blob.held === "guitar" && this.guitarNoteIn <= 0) {
      this.guitarNoteIn = NOTE_EVERY * 0.7;
      const h = this.blob.headTop;
      this.spawnNote(h.x + 0.1, h.y - 0.05, h.z + 0.15);
    }
    for (let i = this.notes.length - 1; i >= 0; i--) {
      const n = this.notes[i];
      n.age += delta;
      if (n.age > NOTE_LIFE) {
        this.r.remove(n.placed);
        this.notes.splice(i, 1);
        continue;
      }
      const grow = NOTE_SIZE * Math.min(n.age / 0.25, 1) * Math.min((NOTE_LIFE - n.age) / 0.7, 1);
      const k = n.age * 2.4 + n.phase;
      this.at.makeTranslation(n.x + 0.08 * Math.sin(k), n.y + NOTE_RISE * n.age, n.z);
      scale(rotate(this.at, Z_AXIS, 14 * Math.sin(k + 1)), grow, grow, grow);
      n.placed.set(this.at);
    }
  }

  private spawnNote(x: number, y: number, z: number) {
    const color = NOTE_COLORS[Math.floor(Math.random() * NOTE_COLORS.length)];
    const placed = this.r.add(this.models.prop("note", color));
    this.notes.push({ placed, x: x + (Math.random() - 0.5) * 0.24, y, z, phase: Math.random() * Math.PI * 2, age: 0 });
  }
}
