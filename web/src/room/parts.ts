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

/** [base] painted [paint]: the paint's hue, with the base's lightness kept a little so the parts still tell apart. */
function tinted(base: string, paint: string): string {
  const rgb = (h: string) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  if (!/^#[0-9a-f]{6}$/i.test(base)) return paint;
  const b = rgb(base);
  const p = rgb(paint);
  const light = (b[0] + b[1] + b[2]) / 3 / 255;
  const mixed = p.map((c, i) => Math.round(Math.min(255, Math.max(0, c * (0.7 + 0.6 * (light - 0.5)) * 0.85 + b[i] * 0.15))));
  return "#" + mixed.map((c) => c.toString(16).padStart(2, "0")).join("");
}

/**
 * The room's models, each built into meshes once: an item's or prop's clay list (`ClayParts`) where
 * it has one, else its crayon list (`RoomParts`), which draws in clay just as well, only in the
 * crayon palette; and an invention's look, from the stream.
 */
export class Models {
  private readonly cache = new Map<string, ClayModel>();
  /** Each model's part list, for painting it another colour. */
  private readonly lists = new Map<ClayModel, Part[]>();
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

  /**
   * The model for item [id] (of [kind], for the fallback); a patch that's [state] "bare" has its own.
   * [color]: the colour his code painted it, which every part takes on while keeping its light and dark.
   */
  item(id: string, kind: string, state: string | null = null, color: string | null = null): ClayModel {
    if (color) return this.cached(`painted/${kind}/${id}/${state ?? ""}/${color}`, () => this.lists.get(this.item(id, kind, state))!.map((p) => ({ ...p, color: tinted(p.color, color) })));
    const made = this.invented.get(id);
    if (made && made.look.length > 0) return this.cached(`invented/${id}`, () => lookParts(made));
    const bare = state === "bare" ? parts.clay_items[`${id}_bare`] : undefined;
    if (bare) return this.cached(`${kind}/${id}_bare`, () => bare);
    return this.cached(`${kind}/${id}`, () => parts.clay_items[id] ?? parts.items[id] ?? parts.kinds[kind] ?? parts.kinds["*"]);
  }

  /** A prop by its name in the export; [color] for the ones that take one. */
  prop(name: string, color?: string): ClayModel {
    return this.cached(`prop/${name}/${color ?? ""}`, () =>
      (parts.clay_props[name] ?? parts.props[name]).map((p) => (p.color === COLOR ? { ...p, color: color! } : p)),
    );
  }

  /** What he owns to read and play, on the wall shelf (`RoomParts.shelf`). */
  shelf(books: string[], records: string[], jars: string[] = []): ClayModel {
    return this.cached(`shelf/${books.join(",")}/${records.join(",")}/${jars.join(",")}`, () => {
      const lists = [parts.clay_props.shelf_boards];
      let x = constants.SHELF_START;
      for (const id of books.slice(0, constants.SHELF_BOOKS)) {
        const book = parts.shelf_books[pick(id, parts.shelf_books.length)];
        lists.push(shifted(book.parts, x));
        x += book.width + constants.SHELF_GAP;
      }
      records.slice(0, constants.SHELF_RECORDS).forEach((id, i) => lists.push(parts.shelf_records[pick(id, parts.shelf_records.length)][i]));
      // His samples in jars, from the lower board's right end inward.
      jars.slice(0, JAR_MOST).forEach((id, i) => lists.push(jar(id, 0.43 - i * 0.12)));
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
      const l = list();
      m = new ClayModel(l);
      this.cache.set(key, m);
      this.lists.set(m, l);
    }
    return m;
  }
}

export const wallItem = (id: string, kind: string) => constants.WALL_ITEMS.includes(id) || constants.WALL_KINDS.includes(kind);
export const flatItem = (id: string, kind: string) => constants.FLAT_ITEMS.includes(id) || constants.FLAT_KINDS.includes(kind);

/** What's in each sample's jar, by item id; samples he keeps stand on his shelf. */
export const SAMPLE_COLORS: Record<string, string> = {
  glowcap: "#bfeee0",
  moss_ball: "#86b96a",
  blue_fern: "#6fb3c9",
  violet_beetle: "#8f7fd6",
  pond_water: "#9fd0d6",
  amber_seed_pod: "#ffc47a",
};
const JAR_MOST = 6;

/** A glass jar with a brass lid and the sample inside, standing on the shelf's lower board at [x]. */
function jar(id: string, x: number): Part[] {
  const base = { rotX: 0, rotY: 0, rotZ: 0, texture: null, taper: 1 };
  const y = 1.04;
  const z = 0.1;
  const glows = id === "glowcap" || id === "amber_seed_pod";
  return [
    { ...base, shape: "sphere", x, y: y + 0.01, z, w: 0.06, h: 0.06, d: 0.06, color: SAMPLE_COLORS[id] ?? "#cccccc", round: -1, ...(glows ? { glow: 0.5 } : {}) },
    { ...base, shape: "cylinder", x, y, z, w: 0.09, h: 0.12, d: 0.09, color: "#e8f4f6", round: 0.02, opacity: 0.45 },
    { ...base, shape: "cylinder", x, y: y + 0.12, z, w: 0.08, h: 0.025, d: 0.08, color: "#d9a95a", round: 0.008 },
  ];
}
