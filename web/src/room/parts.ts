import type { Texture } from "three";
import { CrayonModel, setMaxRound, type Part } from "../crayon/model";
import exported from "../../../parts/export/parts.json";

/** The export's placeholder for a colour chosen as he lives (`PartExport.COLOR`). */
const COLOR = "$color";

const parts = exported as unknown as {
  constants: Record<string, number> & { PLAIN_BOOK: string; SPINES: string[]; WALL_ITEMS: string[]; WALL_KINDS: string[]; FLAT_ITEMS: string[]; FLAT_KINDS: string[]; FRAME_XS: number[] };
  items: Record<string, Part[]>;
  kinds: Record<string, Part[]>;
  props: Record<string, Part[]>;
  shelf_books: { width: number; parts: Part[] }[];
  shelf_records: Part[][][];
};

export const constants = parts.constants;
setMaxRound(constants.MAX_ROUND);

/** Java's `String.hashCode`, which picks a book's or record's colour in the part lists. */
function javaHash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  return h;
}

const pick = (id: string, n: number) => ((javaHash(id) % n) + n) % n;

/** [lists] as one model's parts: the seeds count up across them, as `PartList` counts them. */
function joined(...lists: Part[][]): Part[] {
  return lists.flat().map((p, i) => ({ ...p, seed: i + 1 }));
}

const shifted = (list: Part[], x: number) => list.map((p) => ({ ...p, x: p.x + x }));

/** The room's models from the exported part lists, each built into meshes once. */
export class Models {
  private readonly cache = new Map<string, CrayonModel>();

  constructor(private readonly texture: (name: string) => Texture | null) {}

  /** The model for item [id] (of [kind], for the fallback). */
  item(id: string, kind: string): CrayonModel {
    return this.cached(`${kind}/${id}`, () => parts.items[id] ?? parts.kinds[kind] ?? parts.kinds["*"]);
  }

  /** A prop by its name in the export; [color] for the ones that take one. */
  prop(name: string, color?: string): CrayonModel {
    return this.cached(`prop/${name}/${color ?? ""}`, () => parts.props[name].map((p) => (p.color === COLOR ? { ...p, color: color! } : p)));
  }

  /** The window's frame and curtains, and the clock, along a back wall [width] tiles wide. */
  fixtures(width: number): CrayonModel {
    return this.cached(`fixtures/${width}`, () =>
      joined(shifted(parts.props.window_fixtures, width * constants.WINDOW_AT), shifted(parts.props.wall_clock, width * constants.CLOCK_AT)),
    );
  }

  /** What he owns to read and play, on the wall shelf (`RoomParts.shelf`). */
  shelf(books: string[], records: string[]): CrayonModel {
    return this.cached(`shelf/${books.join(",")}/${records.join(",")}`, () => {
      const lists = [parts.props.shelf_boards];
      let x = constants.SHELF_START;
      for (const id of books.slice(0, constants.SHELF_BOOKS)) {
        const book = parts.shelf_books[pick(id, parts.shelf_books.length)];
        lists.push(shifted(book.parts, x));
        x += book.width + constants.SHELF_GAP;
      }
      records.slice(0, constants.SHELF_RECORDS).forEach((id, i) => lists.push(parts.shelf_records[pick(id, parts.shelf_records.length)][i]));
      return joined(...lists);
    });
  }

  /** A painter's palette with a dab of each of [colors] round its far edge (`RoomParts.palette`). */
  palette(colors: string[]): CrayonModel {
    return this.cached(`palette/${colors.join(",")}`, () => {
      const dabs = colors.map((c, i) => {
        const a = Math.PI * (1.1 + (0.8 * i) / Math.max(1, colors.length - 1));
        return parts.props.palette_dab.map((p) => ({ ...p, color: c, x: p.x + 0.085 * Math.cos(a), z: p.z + 0.058 * Math.sin(a) }));
      });
      return joined(parts.props.palette_board, ...dabs);
    });
  }

  /** The colour of book [id]'s spine on the shelf, so the one in his hands matches; the plain book colour without one. */
  bookColor(id: string | null): string {
    return id === null ? constants.PLAIN_BOOK : constants.SPINES[pick(id, constants.SPINES.length)];
  }

  private cached(key: string, list: () => Part[]): CrayonModel {
    let m = this.cache.get(key);
    if (!m) {
      m = CrayonModel.of(list(), this.texture);
      this.cache.set(key, m);
    }
    return m;
  }
}

export const wallItem = (id: string, kind: string) => constants.WALL_ITEMS.includes(id) || constants.WALL_KINDS.includes(kind);
export const flatItem = (id: string, kind: string) => constants.FLAT_ITEMS.includes(id) || constants.FLAT_KINDS.includes(kind);
