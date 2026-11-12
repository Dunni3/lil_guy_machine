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

/**
 * One room's layout beyond its map: where pieces are made and where they hang. The size, walls,
 * window and door come from the map; this is what the map doesn't say. Only the bedroom so far, so
 * nothing in the protocol names a room yet: a second room is a second one of these.
 */
export interface RoomDef {
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

export const BEDROOM: RoomDef = {
  stands: [SKETCH, PAINTING],
  work: { sketch: SKETCH, journal: SKETCH, paint: PAINTING },
  wallSpots: constants.FRAME_XS.map((x) => ({ on: "picture_rail", x, y: constants.FRAME_BOTTOM, z: 0 })),
};
