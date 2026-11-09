import type { LifeState } from "../../../protocol/ts/protocol";
import type { CrayonRenderer, Placed } from "../crayon/renderer";
import type { Matrix4 } from "three";
import type { Models } from "./parts";

/**
 * His art in the room. So far only the blank canvas on the easel. The next piece of this renderer
 * goes here: hung frames on the picture rail (`RoomParts.frame`, FRAME_XS), the piece under way on the
 * desk or easel, played out stroke by stroke from `state.easel` (`PaintScene.kt`, at the host's
 * `phase_seconds` and `fast.easel_time`), and setting the [Blob]'s `pose.art` fields, which already
 * drive his hands, the tool and the palette.
 */
export class ArtLayer {
  private canvas: Placed | null = null;

  constructor(private readonly r: CrayonRenderer, private readonly models: Models) {}

  /** [easel] is where the easel stands, or null when he has none. */
  update(state: LifeState, easel: Matrix4 | null) {
    const show = easel !== null && (state.wallet.owns["canvas"] ?? 0) > 0;
    if (show && !this.canvas) this.canvas = this.r.add(this.models.prop("canvas"));
    if (this.canvas) {
      this.canvas.show(show);
      if (show) this.canvas.set(easel!);
    }
  }
}
