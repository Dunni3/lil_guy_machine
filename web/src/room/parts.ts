import exported from "../../../parts/export/parts.json";
import type { Invented } from "../../../protocol/ts/protocol";
import type { Part } from "../clay/geometry";
import { ClayModel } from "../clay/renderer";

/** The export's placeholder for a colour chosen as he lives (`PartExport.COLOR`). */
const COLOR = "$color";

const parts = exported as unknown as {
  constants: Record<string, number> & { PLAIN_BOOK: string; SPINES: string[]; WALL_ITEMS: string[]; WALL_KINDS: string[]; FLAT_ITEMS: string[]; FLAT_KINDS: string[]; FRAME_XS: number[] };
  clay_constants: Record<string, number>;
  items: Record<string, Part[]>;
  kinds: Record<string, Part[]>;
  props: Record<string, Part[]>;
  clay_items: Record<string, Part[]>;
  clay_props: Record<string, Part[]>;
  shelf_books: { width: number; parts: Part[] }[];
  shelf_records: Part[][][];
};

export const constants = parts.constants;
export const clay = parts.clay_constants;

/** Java's `String.hashCode`, which picks a book's or record's colour in the part lists. */
function javaHash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  return h;
}

const pick = (id: string, n: number) => ((javaHash(id) % n) + n) % n;
const shifted = (list: Part[], x: number) => list.map((p) => ({ ...p, x: p.x + x }));

/** How far up a wall the things he invents for it hang, and the most they stand out from it. */
const INVENTED_WALL_Y = 1.0;
const INVENTED_WALL_DEPTH = 0.25;

/**
 * An invention's look as a part list. A design is drawn standing on a floor tile; one for a wall
 * is lifted up it and flattened against it, its back at z = 0 as the wall items' part lists are.
 */
function lookParts(made: Invented): Part[] {
  return made.look.map((p) => ({
    shape: p.shape,
    w: p.w,
    h: p.h,
    d: made.wall ? Math.min(p.d, INVENTED_WALL_DEPTH) : p.d,
    x: p.x,
    y: p.y + (made.wall ? INVENTED_WALL_Y : 0),
    z: made.wall ? 0.01 + (p.z + 0.5) * INVENTED_WALL_DEPTH : p.z,
    rotX: p.rot_x,
    rotY: p.rot_y,
    rotZ: p.rot_z,
    color: p.color,
    texture: null,
    round: -1,
    // A look from a host before these fields came has none of them: the defaults.
    taper: p.taper ?? 1,
    cut: p.cut ?? 1,
    hole: p.hole ?? 0,
    arc: p.arc ?? 360,
    glow: p.glow ?? 0,
    opacity: p.opacity ?? 1,
  }));
}

/**
 * The room's models, each built into meshes once: an item's or prop's clay list (`ClayParts`) where
 * it has one, else its crayon list (`RoomParts`), which draws in clay just as well, only in the
 * crayon palette; and an invention's look, from the stream.
 */
export class Models {
  private readonly cache = new Map<string, ClayModel>();
  private invented = new Map<string, Invented>();
  private inventedFrom: Invented[] | null = null;

  /** What he has invented: their looks are their models, which the export can't have. */
  inventions(list: Invented[]) {
    if (list === this.inventedFrom) return;
    this.inventedFrom = list;
    this.invented = new Map(list.map((i) => [i.id, i]));
  }

  /** True for an invention that hangs in a wall. */
  onWall(id: string): boolean {
    return this.invented.get(id)?.wall ?? false;
  }

  /** The model for item [id] (of [kind], for the fallback). */
  item(id: string, kind: string): ClayModel {
    const made = this.invented.get(id);
    if (made && made.look.length > 0) return this.cached(`invented/${id}`, () => lookParts(made));
    return this.cached(`${kind}/${id}`, () => parts.clay_items[id] ?? parts.items[id] ?? parts.kinds[kind] ?? parts.kinds["*"]);
  }

  /** A prop by its name in the export; [color] for the ones that take one. */
  prop(name: string, color?: string): ClayModel {
    return this.cached(`prop/${name}/${color ?? ""}`, () =>
      (parts.clay_props[name] ?? parts.props[name]).map((p) => (p.color === COLOR ? { ...p, color: color! } : p)),
    );
  }

  /** What he owns to read and play, on the wall shelf (`RoomParts.shelf`). */
  shelf(books: string[], records: string[]): ClayModel {
    return this.cached(`shelf/${books.join(",")}/${records.join(",")}`, () => {
      const lists = [parts.clay_props.shelf_boards];
      let x = constants.SHELF_START;
      for (const id of books.slice(0, constants.SHELF_BOOKS)) {
        const book = parts.shelf_books[pick(id, parts.shelf_books.length)];
        lists.push(shifted(book.parts, x));
        x += book.width + constants.SHELF_GAP;
      }
      records.slice(0, constants.SHELF_RECORDS).forEach((id, i) => lists.push(parts.shelf_records[pick(id, parts.shelf_records.length)][i]));
      return lists.flat();
    });
  }

  /** A painter's palette with a dab of each of [colors] round its far edge (`RoomParts.palette`). */
  palette(colors: string[]): ClayModel {
    return this.cached(`palette/${colors.join(",")}`, () => {
      const dabs = colors.map((c, i) => {
        const a = Math.PI * (1.1 + (0.8 * i) / Math.max(1, colors.length - 1));
        return parts.props.palette_dab.map((p) => ({ ...p, color: c, x: p.x + 0.085 * Math.cos(a), z: p.z + 0.058 * Math.sin(a) }));
      });
      return [parts.props.palette_board, ...dabs].flat();
    });
  }

  /** The colour of book [id]'s spine on the shelf, so the one in his hands matches; the plain book colour without one. */
  bookColor(id: string | null): string {
    return id === null ? constants.PLAIN_BOOK : constants.SPINES[pick(id, constants.SPINES.length)];
  }

  private cached(key: string, list: () => Part[]): ClayModel {
    let m = this.cache.get(key);
    if (!m) {
      m = new ClayModel(list());
      this.cache.set(key, m);
    }
    return m;
  }
}

export const wallItem = (id: string, kind: string) => constants.WALL_ITEMS.includes(id) || constants.WALL_KINDS.includes(kind);
export const flatItem = (id: string, kind: string) => constants.FLAT_ITEMS.includes(id) || constants.FLAT_KINDS.includes(kind);
