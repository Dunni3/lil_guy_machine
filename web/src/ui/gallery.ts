import type { ArtList, DrawingView, LifeState, PieceView } from "../../../protocol/ts/protocol";

/**
 * Every piece he has made in this run, to scroll through: a panel of DOM over the room like the
 * letters', opened from the "His art" button. The host's `GET /v1/lives/{life}/art` has the whole
 * run's from its database; the stream's drawings (this session's) are merged in as they come, so a
 * piece finished while it's open shows at once. A click on one shows it large, with what it shows
 * and whether it hangs, is with him or is gone; the arrow keys step through them there. It has the
 * keys while it's open; Escape steps back, then closes it.
 */
export class ArtGallery {
  private readonly button = el<HTMLButtonElement>("art-open");
  private readonly panel = el<HTMLElement>("art");
  private readonly count = el<HTMLElement>("art-count");
  private readonly grid = el<HTMLOListElement>("art-grid");
  private readonly note = el<HTMLElement>("art-note");
  private readonly order = el<HTMLButtonElement>("art-order");
  private readonly big = el<HTMLElement>("art-big");
  /** The run's pieces by piece number: the host's, then the stream's over them. */
  private readonly pieces = new Map<number, DrawingView>();
  private run: string | null = null;
  private drawingsFrom: DrawingView[] | null = null;
  private galleryFrom: PieceView[] | null = null;
  private owned = new Map<number, PieceView>();
  private newestFirst = true;
  /** The piece shown large, or null. */
  private picked: number | null = null;
  private loading = false;
  private dirty = true;

  constructor(private readonly life: string, broadcast: boolean) {
    if (broadcast) return;
    this.button.hidden = false;
    this.button.addEventListener("click", () => this.show());
    el("art-close").addEventListener("click", () => this.close());
    el("art-big-close").addEventListener("click", () => this.pick(null));
    el("art-prev").addEventListener("click", () => this.step(-1));
    el("art-next").addEventListener("click", () => this.step(1));
    this.order.addEventListener("click", () => {
      this.newestFirst = !this.newestFirst;
      this.dirty = true;
      this.render();
    });
    this.grid.addEventListener("click", (e) => {
      const li = (e.target as HTMLElement).closest<HTMLElement>("li[data-piece]");
      if (li) this.pick(Number(li.dataset.piece));
    });
    // The room's keys stay out of the panel.
    this.panel.addEventListener("keydown", (e) => {
      if (e.key === "Escape") {
        e.preventDefault();
        if (this.picked !== null) this.pick(null);
        else this.close();
      } else if (this.picked !== null && (e.key === "ArrowLeft" || e.key === "ArrowRight")) {
        e.preventDefault();
        this.step(e.key === "ArrowRight" ? 1 : -1);
      }
      e.stopPropagation();
    });
  }

  get open(): boolean {
    return !this.panel.hidden;
  }

  show() {
    this.panel.hidden = false;
    this.button.hidden = true;
    this.panel.focus();
    void this.fetch();
    this.render();
  }

  close() {
    this.pick(null);
    this.panel.hidden = true;
    this.button.hidden = false;
  }

  /** Each frame: a new run starts afresh, and new drawings and what became of each piece go in. Cheap when nothing changed. */
  update(life: LifeState) {
    if (life.run.run_id !== this.run) {
      this.run = life.run.run_id;
      this.pieces.clear();
      this.drawingsFrom = null;
      this.dirty = true;
      if (this.open) void this.fetch();
    }
    if (life.logs.drawings !== this.drawingsFrom) {
      this.drawingsFrom = life.logs.drawings;
      for (const d of life.logs.drawings) if (d.piece !== null && d.size > 0) this.pieces.set(d.piece, d);
      this.dirty = true;
    }
    if (life.gallery.art !== this.galleryFrom) {
      this.galleryFrom = life.gallery.art;
      this.owned = new Map(life.gallery.art.map((p) => [p.id, p]));
      this.dirty = true;
    }
    this.button.textContent = this.pieces.size ? `His art (${this.pieces.size})` : "His art";
    if (this.open) this.render();
  }

  private async fetch() {
    if (this.loading) return;
    this.loading = true;
    this.note.textContent = this.pieces.size ? "" : "Fetching his art...";
    try {
      const r = await fetch(`/v1/lives/${encodeURIComponent(this.life)}/art`);
      if (!r.ok) throw new Error(`${r.status}`);
      const list = (await r.json()) as ArtList;
      if (list.run_id === null || list.run_id === this.run) {
        // The stream's copy of a piece is as new as the host's, so it stays where both have one.
        for (const d of list.pieces) if (d.piece !== null && d.size > 0 && !this.streamed(d.piece)) this.pieces.set(d.piece, d);
      }
      this.note.textContent = "";
    } catch (e) {
      this.note.textContent = `Couldn't fetch the whole run's art (${e instanceof Error ? e.message : e}): showing this session's.`;
    } finally {
      this.loading = false;
      this.dirty = true;
      this.render();
    }
  }

  private streamed(piece: number): boolean {
    return this.drawingsFrom?.some((d) => d.piece === piece) ?? false;
  }

  /** The pieces in the order the grid shows them. */
  private sorted(): DrawingView[] {
    const all = [...this.pieces.values()].sort((a, b) => a.piece! - b.piece!);
    return this.newestFirst ? all.reverse() : all;
  }

  private render() {
    if (!this.dirty) return;
    this.dirty = false;
    const all = this.sorted();
    this.count.textContent = all.length === 1 ? "1 piece" : `${all.length} pieces`;
    this.order.textContent = this.newestFirst ? "Newest first" : "Oldest first";
    if (!all.length && !this.loading && !this.note.textContent) this.note.textContent = "He hasn't made any art yet in this run.";
    else if (all.length && this.note.textContent === "He hasn't made any art yet in this run.") this.note.textContent = "";
    // Cards are kept by piece and only moved, so a picture is drawn once.
    const have = new Map<number, HTMLLIElement>();
    for (const li of this.grid.querySelectorAll<HTMLLIElement>("li[data-piece]")) have.set(Number(li.dataset.piece), li);
    const keep = new Set<number>();
    for (const d of all) {
      const piece = d.piece!;
      keep.add(piece);
      let li = have.get(piece);
      if (!li || li.dataset.seq !== String(d.seq)) {
        const fresh = card(d);
        if (li) li.replaceWith(fresh);
        li = fresh;
      }
      li.querySelector(".art-status")!.textContent = status(this.owned.get(piece), this.run !== null && this.galleryFrom !== null);
      this.grid.append(li);
    }
    for (const [piece, li] of have) if (!keep.has(piece)) li.remove();
    if (this.picked !== null) this.showBig();
  }

  private pick(piece: number | null) {
    this.picked = piece;
    this.big.hidden = piece === null;
    if (piece !== null) this.showBig();
  }

  private step(by: number) {
    const all = this.sorted();
    const at = all.findIndex((d) => d.piece === this.picked);
    if (at < 0 || !all.length) return;
    this.pick(all[(at + by + all.length) % all.length].piece);
  }

  private showBig() {
    const d = this.picked === null ? undefined : this.pieces.get(this.picked);
    if (!d) return this.pick(null);
    const frame = el("art-big-frame");
    if (frame.dataset.seq !== String(d.seq)) {
      frame.dataset.seq = String(d.seq);
      frame.replaceChildren(picture(d));
    }
    el("art-big-title").textContent = d.title ?? "Untitled";
    el("art-big-when").textContent = when(d);
    el("art-big-status").textContent = status(this.owned.get(d.piece!), this.galleryFrom !== null);
    el("art-big-shows").textContent = d.shows ?? "";
    el("art-big-tags").replaceChildren(...d.tags.map((t) => Object.assign(document.createElement("span"), { textContent: t })));
    const all = this.sorted();
    el("art-big-place").textContent = `${all.findIndex((x) => x.piece === d.piece) + 1} of ${all.length}`;
  }
}

function card(d: DrawingView): HTMLLIElement {
  const li = document.createElement("li");
  li.dataset.piece = String(d.piece);
  li.dataset.seq = String(d.seq);
  li.tabIndex = 0;
  li.title = d.shows ?? d.title ?? "";
  const title = document.createElement("p");
  title.className = "art-title";
  title.textContent = d.title ?? "Untitled";
  const meta = document.createElement("p");
  meta.className = "art-meta";
  meta.textContent = when(d);
  const state = document.createElement("p");
  state.className = "art-status";
  li.append(picture(d), title, meta, state);
  li.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      li.click();
    }
  });
  return li;
}

/** The piece as a canvas a cell a pixel, which CSS scales up crisp. */
function picture(d: DrawingView): HTMLCanvasElement {
  const c = document.createElement("canvas");
  c.width = d.size;
  c.height = d.size;
  const g = c.getContext("2d")!;
  const img = g.createImageData(d.size, d.size);
  for (let y = 0; y < d.size; y++) {
    const row = d.rows[y] ?? "";
    for (let x = 0; x < d.size; x++) {
      const rgb = d.palette[row.charCodeAt(x) - 97] ?? 0xffffff;
      const i = (y * d.size + x) * 4;
      img.data[i] = (rgb >> 16) & 255;
      img.data[i + 1] = (rgb >> 8) & 255;
      img.data[i + 2] = rgb & 255;
      img.data[i + 3] = 255;
    }
  }
  g.putImageData(img, 0, 0);
  c.setAttribute("role", "img");
  c.setAttribute("aria-label", d.title ?? "Untitled");
  return c;
}

function when(d: DrawingView): string {
  return [`#${d.piece}`, d.item, d.day !== null ? `day ${d.day}` : null, d.time].filter(Boolean).join(" · ");
}

/** What became of it, from the world's pieces: [known] is false until the stream has said. */
function status(p: PieceView | undefined, known: boolean): string {
  if (!known) return "";
  if (!p) return "sold or gone";
  if (p.hung) return "on his wall";
  return p.sells_for !== null ? `with him · would sell for $${p.sells_for}` : "with him";
}

function el<T extends HTMLElement = HTMLElement>(id: string): T {
  return document.getElementById(id) as T;
}
