import { constants } from "./parts";

/**
 * Where a piece of [item] is made ([activity]) and his newest unsold one shows: on the thing
 * [id], offset from its tile centre, [size] square, lying flat or standing up (facing +Z), whether
 * the stand needs a canvas for it, and how far he moves in from his tile to work at it.
 */
export interface Stand {
  id: string;
  item: string;
  activity: string;
  x: number;
  y: number;
  z: number;
  size: number;
  flat: boolean;
  canvas: boolean;
  slide: number;
}

/** A place on a wall for a framed piece: on the thing [on], offset from where it's placed (its frame's bottom centre). */
export interface WallSpot {
  on: string;
  x: number;
  y: number;
  z: number;
}

/** A fixture on the back wall, its centre [x] metres from the map's left edge, [y] up from the floor. */
export interface WallFixture {
  x: number;
  y: number;
}

/** The window in the back wall: its centre [x] metres from the map's left edge, and its size in metres. */
export interface WindowDef {
  x: number;
  bottom: number;
  top: number;
  width: number;
}

/**
 * One room's layout beyond its map: its walls' height, what hangs on the back wall, where pieces
 * are made and where they hang. The size and the walls' plan come from the map; this is what the
 * map doesn't say, so it follows the map's wall slots (`room.yaml`). Only the bedroom so far, so
 * nothing in the protocol names a room yet: a second room is a second one of these.
 */
export interface RoomDef {
  /** The back and left walls stand this tall; the right and front walls, nearer the camera, are cut down to [lip]. */
  wallHeight: number;
  lip: number;
  /** How far into the room the back and left walls' faces are, from the map's edge (their tiles' depth). */
  backWall: number;
  window: WindowDef;
  clock: WallFixture;
  /** The wall shelf his books and records go on, and the peg his guitar hangs from, at floor height (their part lists stand on the floor). */
  shelf: WallFixture;
  guitar: WallFixture;
  stands: Stand[];
  /** Activities done by hand at a stand, and which: his working hand goes to it. */
  work: Record<string, Stand>;
  /** Hung pieces fill these in order, oldest first. */
  wallSpots: WallSpot[];
}

const SKETCH: Stand = {
  id: "desk", item: "sketch", activity: "sketch",
  x: constants.SKETCH_X, y: 0.567, z: constants.SKETCH_Z, size: 0.2, flat: true, canvas: false, slide: 0.14,
};
const PAINTING: Stand = {
  id: "easel", item: "painting", activity: "paint",
  x: 0, y: constants.CANVAS_Y, z: constants.CANVAS_Z + 0.025, size: 0.44, flat: false, canvas: true, slide: 0.38,
};

/** The bedroom: 12 x 6 inside. Along the back wall, between its wall slots: the clock, the guitar's peg, the window (over the sills' plants) and the shelf. */
export const BEDROOM: RoomDef = {
  wallHeight: 2.4,
  lip: 0.22,
  backWall: 1,
  window: { x: 9.5, bottom: 1.05, top: 1.95, width: 1.0 },
  clock: { x: 3.5, y: constants.CLOCK_Y },
  shelf: { x: 12.45, y: 0 },
  guitar: { x: 8.42, y: 0 },
  stands: [SKETCH, PAINTING],
  work: { sketch: SKETCH, journal: SKETCH, paint: PAINTING },
  wallSpots: constants.FRAME_XS.map((x) => ({ on: "picture_rail", x, y: constants.FRAME_BOTTOM, z: 0 })),
};
