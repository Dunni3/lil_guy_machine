import { Box3, Matrix4, MeshStandardMaterial, Vector3, type Mesh, type Ray } from "three";
import type { Content, Entity, LifeState, PlaceView, RectView, WorldView } from "../../../protocol/ts/protocol";
import { ClayModel, type ClayRenderer, type Glow, type Placed } from "../clay/renderer";
import type { ClayTextures } from "../clay/textures";
import { Blob, newPose } from "../guy/blob";
import { Y_AXIS, Z_AXIS, rotate, scale, translate } from "../math";
import type { Place } from "../stream/track";
import { ArtLayer } from "./art";
import { CameraRig, type CameraMode } from "./camera";
import { Models, SAMPLE_COLORS, constants, wallItem } from "./parts";
import { ADDED_ROOM, BEDROOM } from "./room";
import { groundTexture, landParts, treeParts } from "./land";
import { Terrain, paneParts, shellParts } from "./shell";
import { Rain } from "./weather";
import { SKIES } from "../settings";

/** The record's turn (degrees a second), and the notes: seconds apart, how long each lasts, and how fast it rises (metres a second). */
const DISC_SPEED = 200;
const NOTE_EVERY = 0.9;
const NOTE_LIFE = 2.6;
const NOTE_RISE = 0.3;
/** Notes are drawn this much bigger than their part list, to read from across the room. */
const NOTE_SIZE = 1.7;
const NOTE_COLORS = ["#f2928a", "#8fb0ec", "#9fd49a", "#c9a0e6"];
/** What he stands, sits or lies on, by item id, and how high its top is. */
const SURFACES: Record<string, number> = { bed: 0.38, chair: 0.38 };
/** How far out from the wall's tiles a wall's inside face is: its blush panel. */
const WALL_FACE = 0.03;
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

/** One thing on the map: its model, and where it stands. */
interface Thing {
  placed: Placed;
  at: Matrix4;
  key: string;
}

/**
 * The room in 3D, in clay: the Field Lab's isometric cutaway (the back and left walls standing, the
 * near ones cut to a sill) seen from the viewer's own [CameraRig], furniture placed where the state
 * says, the guy as an animated [Blob] with his lantern, and the light following the game clock: the
 * sun's angle and colour, the sky, the window's glass, and the lamps glowing and lighting the room at
 * night. What he's busy with shows: the book in his hands, the guitar off its peg and in his arms, the
 * record turning and notes rising from it.
 *
 * His art is [ArtLayer]'s, and the room's layout beyond its map (walls, window, clock, shelf, where
 * pieces are made and hang) is its [RoomDef]. The HUD is drawn over it (`ui/`), from [speech] and [making].
 */
export class RoomView {
  private readonly models = new Models();
  private readonly blob: Blob;
  private readonly pose = newPose();
  private readonly art: ArtLayer;
  private readonly pane = new MeshStandardMaterial({ roughness: 0.4, metalness: 0 });

  private framedWorld: WorldView | null = null;
  private map: Terrain | null = null;
  /** The bedroom on the map, its walls included: the room's layout ([RoomDef]) is from its top-left. */
  private roomAt: RectView = { x0: 0, y0: 0, x1: 1, y1: 1 };
  /** The place the camera frames: where he is. */
  private framedPlace = "";
  /** Where the sun's shadows are aimed for the place framed, and where they are aimed now (the view's middle, looking around). */
  private readonly placeAim = { x: 0, z: 0, reach: 11 };
  private readonly aimed = { x: NaN, z: NaN, reach: 0 };
  private land: Placed | null = null;
  private trees: Placed | null = null;
  private shell: Placed | null = null;
  private glass: Mesh | null = null;
  private clock: Placed | null = null;
  private hourHand: Placed | null = null;
  private minuteHand: Placed | null = null;

  private readonly things = new Map<string, Thing>();
  private thingsFrom: LifeState["things"] | null = null;
  private glows: Glow[] = [];

  private shelf: Placed | null = null;
  private shelfFrom: LifeState["shop"] | null = null;
  private shelfWallet: LifeState["wallet"] | null = null;
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
  private readonly middle = new Vector3();
  private readonly at = new Matrix4();
  readonly camera: CameraRig;
  private readonly room = BEDROOM;
  private aspect = 16 / 9;

  /** For his bubble: where his head is, and whether he's asleep or thinking. The finished piece's title is [ArtLayer.announced]. */
  readonly speech = { head: new Vector3(), asleep: false, thinking: false, announced: null as string | null };
  /** The stand of the piece he's at this frame, or null: the HUD says how far along it is. */
  making: { canvas: boolean } | null = null;

  /** Light as at this hour whatever the clock says (`?hour=`), or null to follow the clock. */
  lightHour: number | null = null;
  /** This weather whatever the sim's is (`?sky=`), or null to follow the sim. */
  sky: string | null = null;
  private readonly rain: Rain;

  constructor(private readonly r: ClayRenderer, camera: CameraMode, private readonly textures: ClayTextures) {
    this.camera = new CameraRig(camera);
    this.blob = new Blob(r, this.models, textures, constants.TOOL_TIP);
    this.art = new ArtLayer(r, this.models, this.room);
    this.rain = new Rain(r.scene);
  }

  /** The screen is this wide for its height. */
  resize(aspect: number) {
    this.aspect = aspect;
  }

  /** One frame of the live world; [place] is where he is between publishes. */
  render(state: LifeState, content: Content, place: Place, delta: number) {
    const fast = state.fast;
    const guy = fast.entities.find((e) => e.kind === "guy");
    if (!guy) return;
    this.frame(state.world);
    this.framePlace(state.world, guy.place);
    const weather = this.sky ? { sky: this.sky, daylight: SKIES[this.sky] } : (fast.weather ?? null);
    this.lightFor(fast.clock?.time ?? null, weather);
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
    const station = pose.doing ? this.room.work[pose.doing] : undefined;
    const stand = station ? state.things.entities.find((e) => e.id === station.id) : undefined;
    pose.workAt = station && stand ? this.workAt.set(stand.x + station.x, station.y, stand.y + station.z) : null;
    pose.workFlat = station?.flat ?? true;
    pose.workSlide = station?.slide ?? 0;
    this.art.pose(pose, making, making ? stand : undefined);
    this.blob.update(delta, pose);
    this.camera.update(this.r.camera, place.x, place.y, this.aspect, delta);
    this.aimShadows();
    this.making = making;

    this.r.setLamps(this.glows, this.middle);
    this.rain.update(weather?.sky ?? null, place.x, place.y, this.map, delta);
    this.art.draw(state, making, (id) => this.things.get(id)?.at ?? null, this.blob, delta);
    this.drawHeld(intent?.type === "do" ? intent.item : null);
    this.drawMusic(fast.music !== null, delta);
    const speech = this.speech;
    speech.head.copy(this.blob.headTop);
    speech.asleep = pose.doing === "sleep" && !pose.moving;
    speech.thinking = pose.thinking;
    speech.announced = this.art.announced;
  }

  /**
   * The thing [ray] meets first, by the boxes its models take up where they stand, and the middle of
   * that box's top (for a label to point at); null if it meets none. The guy and the shelf aren't things.
   */
  pick(ray: Ray): { id: string; top: Vector3 } | null {
    let best: { id: string; top: Vector3 } | null = null;
    let nearest = Infinity;
    const box = new Box3();
    const hit = new Vector3();
    for (const [id, t] of this.things) {
      const b = t.placed.model.bounds;
      box.min.set(b[0], b[1], b[2]);
      box.max.set(b[3], b[4], b[5]);
      if (box.isEmpty()) continue;
      box.applyMatrix4(t.at);
      if (!ray.intersectBox(box, hit)) continue;
      const d = hit.distanceTo(ray.origin);
      if (d >= nearest) continue;
      nearest = d;
      best = { id, top: new Vector3((box.min.x + box.max.x) / 2, box.max.y, (box.min.z + box.max.z) / 2) };
    }
    return best;
  }

  /** Builds the land and the room (once per world). The camera frames the place he's in ([framePlace]). */
  private frame(world: WorldView) {
    if (world === this.framedWorld) return;
    this.framedWorld = world;
    this.framedPlace = "";
    const map = (this.map = new Terrain(world.rows));
    this.camera.land(0, 0, world.width, world.height);
    const room = this.room;
    const rooms = world.places.filter((p) => p.kind === "room");
    const at = (this.roomAt = rooms[0]?.bounds ?? { x0: 0, y0: 0, x1: map.width - 1, y1: map.height - 1 });
    const floor = this.textures.texture("floor")!;
    floor.repeat.set((at.x1 - at.x0 - 1) / 3, (at.y1 - at.y0 - 1) / 3);
    this.middle.set((at.x0 + at.x1 + 1) / 2, 0, (at.y0 + at.y1 + 1) / 2);

    // The land, when there is any round the room: its ground painted from the world's letters, and its trees.
    if (this.land) this.r.remove(this.land);
    if (this.trees) this.r.remove(this.trees);
    this.land = this.trees = null;
    if (world.places.some((p) => p.kind === "outdoor")) {
      this.textures.set("ground", groundTexture(world));
      this.land = this.r.add(new ClayModel(landParts(world))).set(this.at.identity());
      const trees = treeParts(world);
      if (trees.length) this.trees = this.r.add(new ClayModel(trees)).set(this.at.identity());
    }

    if (this.shell) this.r.remove(this.shell);
    const doors = world.doors.filter((d) => d.y === at.y1 && d.x > at.x0 && d.x < at.x1).map((d) => d.x);
    // Rooms he built onto the house, each beside the room it joins: low on that side, so it shows.
    const added = rooms.slice(1).flatMap((p) => {
      const b = p.bounds;
      const front = world.doors.filter((d) => d.y === b.y1 && d.x > b.x0 && d.x < b.x1).map((d) => d.x);
      const joined = rooms.some((o) => o !== p && o.bounds.x1 === b.x0);
      return shellParts(b, ADDED_ROOM, front, joined);
    });
    this.shell = this.r.add(new ClayModel([...shellParts(at, room, doors), ...added]));
    this.shell.set(this.at.identity());
    if (this.glass) this.r.removeMesh(this.glass);
    const pane = new ClayModel(paneParts(at, room)).batches[0].geometry;
    this.glass = this.r.mesh(pane, this.pane);
    this.glass.matrix.identity();
    const wall = at.y0 + room.backWall + WALL_FACE;
    this.clock ??= this.r.add(this.models.prop("wall_clock"));
    this.clock.set(this.at.makeTranslation(at.x0 + room.clock.x, 0, wall));
    this.hourHand ??= this.r.add(this.models.prop("hand_hour"), false);
    this.minuteHand ??= this.r.add(this.models.prop("hand_minute"), false);
    this.guitarAt.makeTranslation(at.x0 + room.guitar.x, room.guitar.y, wall);
    this.thingsFrom = null;
    this.shelfKey = "";
    this.shelfFrom = null;
  }

  /**
   * The camera frames the place he's in, as the Field Lab reframes per place: a room by its walls,
   * a part of the land by its tiles. The sun's shadows and his lantern's bounds go with it. Moving
   * between places eases; the first framing is at once.
   */
  private framePlace(world: WorldView, id: string | null) {
    const key = id ?? "";
    if (key === this.framedPlace) return;
    const first = this.framedPlace === "";
    this.framedPlace = key;
    const place: PlaceView | undefined = world.places.find((p) => p.id === id) ?? world.places.find((p) => p.kind === "room");
    const room = this.room;
    let box: [number, number, number, number, number];
    if (!place || place.kind === "room") {
      const at = place?.bounds ?? this.roomAt;
      box = [at.x0 + room.backWall - 0.4, at.y0 + room.backWall - 0.4, at.x1 + 0.4, at.y1 + 0.4, room.wallHeight];
      this.blob.lanternBounds = [at.x0 + room.backWall + 0.35, at.y0 + room.backWall + 0.35, at.x1 - 0.35, at.y1 - 0.35];
    } else {
      const b = place.bounds;
      box = [b.x0, b.y0, b.x1 + 1, b.y1 + 1, 2];
      this.blob.lanternBounds = [b.x0 + 0.5, b.y0 + 0.5, b.x1 + 0.5, b.y1 + 0.5];
    }
    this.camera.frame(box[0], box[1], box[2], box[3], box[4], !first);
    const reach = Math.min(24, Math.max(11, Math.max(box[2] - box[0], box[3] - box[1]) / 2 + 3));
    Object.assign(this.placeAim, { x: (box[0] + box[2]) / 2, z: (box[1] + box[3]) / 2, reach });
  }

  /**
   * The sun's shadows cover the place framed, or, looking around, what's in view: aimed again only
   * when that has moved a couple of metres or grown, since each aim redraws the shadow map.
   */
  private aimShadows() {
    let { x, z, reach } = this.placeAim;
    if (this.camera.mode === "free") {
      const f = this.camera.focus;
      x = f.x;
      z = f.z;
      reach = Math.min(32, Math.max(11, Math.ceil(f.reach + 3)));
    }
    const a = this.aimed;
    if (Math.hypot(x - a.x, z - a.z) < 2 && reach === a.reach) return;
    Object.assign(a, { x, z, reach });
    this.r.aim(x, z, reach);
  }

  /** The light, the window's glass and the wall clock for the time of day; [time] "HH:MM", or noon without one. [weather] dims and greys it. */
  private lightFor(time: string | null, weather: { sky: string; daylight: number } | null) {
    let hour = 12;
    if (time) {
      const [h, m] = time.split(":").map((v) => parseInt(v, 10) || 0);
      hour = h + m / 60;
    }
    this.r.setHour(this.lightHour ?? hour);
    this.r.setWeather(weather);
    const day = this.r.light;
    this.pane.color.copy(day.pane);
    this.pane.emissive.copy(day.pane);
    this.pane.emissiveIntensity = 0.5;
    const { clock, backWall } = this.room;
    const z = this.roomAt.y0 + backWall + WALL_FACE + 0.06;
    const x = this.roomAt.x0 + clock.x;
    this.hourHand!.set(rotate(this.at.makeTranslation(x, clock.y, z), Z_AXIS, -30 * hour));
    this.minuteHand!.set(rotate(this.at.makeTranslation(x, clock.y, z + 0.01), Z_AXIS, -360 * hour));
  }

  /** Things go at their tile; wall items against the wall face that looks into the room. Runs when [state]'s things change. */
  private syncThings(state: LifeState) {
    this.models.inventions(state.shop.inventions ?? []);
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
      this.things.delete(id);
    }
    // What glows lights the room at night: each lamp's glow, where it stands.
    this.glows = [];
    for (const t of this.things.values()) for (const g of t.placed.model.glows) this.glows.push({ at: g.at.clone().applyMatrix4(t.at), strength: g.strength });
  }

  private place(e: Entity) {
    const key = `${e.kind}/${e.x}/${e.y}/${e.state ?? ""}/${e.color ?? ""}`;
    if (this.things.get(e.id)?.key === key) return;
    const old = this.things.get(e.id);
    if (old) this.r.remove(old.placed);
    const map = this.map!;
    const model = this.models.item(e.id, e.kind, e.state, e.color ?? null);
    const at = new Matrix4();
    const tx = Math.trunc(e.x);
    const ty = Math.trunc(e.y);
    if (map.at(tx, ty) === "#" && (wallItem(e.id, e.kind) || this.models.onWall(e.id))) {
      const [dx, dy] = INWARD.find(([ix, iy]) => map.at(tx + ix, ty + iy) === ".") ?? [0, 1];
      const yaw = dx === 1 ? 90 : dx === -1 ? -90 : dy === -1 ? 180 : 0;
      const out = 0.5 + WALL_FACE;
      rotate(at.makeTranslation(tx + 0.5 + dx * out, 0, ty + 0.5 + dy * out), Y_AXIS, yaw);
    } else at.makeTranslation(e.x, 0, e.y);
    this.things.set(e.id, { placed: this.r.add(model).set(at), at, key });
  }

  /** His books and records on the wall shelf, and his guitar on the wall, once he owns any. Runs when what the shop knows changes. */
  private syncShelf(state: LifeState, content: Content) {
    if (state.shop === this.shelfFrom && state.wallet === this.shelfWallet) return;
    this.shelfFrom = state.shop;
    this.shelfWallet = state.wallet;
    const group = new Map(content.catalog.map((c) => [c.id, c.group]));
    // What he invented isn't in the catalogue unless the shop restocks it; what he has of it is in his wallet.
    const made = (state.shop.inventions ?? []).filter((i) => (state.wallet.owns[i.id] ?? 0) > 0 && !state.shop.catalog.some((c) => c.id === i.id));
    for (const i of made) group.set(i.id, i.group);
    const owned = [...state.shop.catalog.filter((c) => c.owned > 0), ...made];
    const books = owned.filter((c) => group.get(c.id) === "book").map((c) => c.id);
    const records = owned.filter((c) => group.get(c.id) === "record").map((c) => c.id);
    const jars = Object.keys(state.wallet.owns).filter((id) => id in SAMPLE_COLORS && state.wallet.owns[id] > 0);
    const key = `${books.join(",")}/${records.join(",")}/${jars.join(",")}`;
    if (key !== this.shelfKey) {
      this.shelfKey = key;
      if (this.shelf) this.r.remove(this.shelf);
      this.shelf = books.length || records.length || jars.length ? this.r.add(this.models.shelf(books, records, jars)) : null;
      const { shelf, backWall } = this.room;
      this.shelf?.set(this.at.makeTranslation(this.roomAt.x0 + shelf.x, shelf.y, this.roomAt.y0 + backWall + WALL_FACE));
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
      this.heldBook = this.r.add(this.models.prop("held_book", color), false);
      this.heldBookColor = color;
    }
    this.heldBook?.show(blob.held === "book");
    if (blob.held === "book") this.heldBook!.set(blob.heldAt);
    if (blob.held === "guitar") this.heldGuitar ??= this.r.add(this.models.prop("held_guitar"), false);
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
      this.disc ??= this.r.add(this.models.prop("disc"), false);
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
    const placed = this.r.add(this.models.prop("note", color), false);
    this.notes.push({ placed, x: x + (Math.random() - 0.5) * 0.24, y, z, phase: Math.random() * Math.PI * 2, age: 0 });
  }
}
