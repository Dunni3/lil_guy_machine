import { Matrix4, Quaternion, Vector3, Vector4, type Mesh } from "three";
import { hex, linear, shade, type Rgba } from "../crayon/color";
import { lumpySphere, taterBody, type BodyMeasure, type PartGeometry } from "../crayon/geometry";
import { X_AXIS, Y_AXIS, Z_AXIS, clamp, lerp, rotate, scale, translate } from "../crayon/math";
import { CrayonModel } from "../crayon/model";
import { rng } from "../crayon/noise";
import { Overlay, type CrayonRenderer, type Placed } from "../crayon/renderer";
import { BODY, DESIGN_H, DESIGN_W, FACE_H, FACE_W, FACE_X, FACE_Y, Faces, paintGrain, type Face } from "./face";

/** What he's doing at a piece played out stroke by stroke: the host's `easel.phase`. */
export type ArtPhase = "ponder" | "dip" | "stroke" | "flourish" | "admire";
export type Held = "none" | "book" | "guitar";

/** Everything about him the view reads from the state, each frame. */
export interface Pose {
  x: number;
  z: number;
  /** Compass degrees, as in the sim: 0 east, 90 north (away from the camera). */
  facing: number;
  moving: boolean;
  doing: string | null;
  energy: number;
  mood: number;
  thinking: boolean;
  music: boolean;
  /** Height of what he stands or lies on: the floor, or the bed. */
  standY: number;
  /** Where his working hand goes, in world space, while he draws, writes or paints; null otherwise. */
  workAt: Vector3 | null;
  /** The work lies flat (a page on the desk) rather than standing up (a canvas facing +Z). */
  workFlat: boolean;
  /** How far he steps or slides toward the work, in metres. */
  workSlide: number;
  /** At a piece played out stroke by stroke: what he's doing at it, and for how long; null to dab at random. */
  art: ArtPhase | null;
  artTime: number;
  /** Where his tool's tip touches the work during a stroke, in world space, and whether it's lifted off. */
  tip: Vector3;
  tipLifted: boolean;
  /** Painting, he holds a brush and a palette; drawing at the desk, a crayon. */
  brush: boolean;
}

export const newPose = (): Pose => ({
  x: 0, z: 0, facing: 270, moving: false, doing: null, energy: 70, mood: 60, thinking: false, music: false, standY: 0,
  workAt: null, workFlat: true, workSlide: 0, art: null, artTime: 0, tip: new Vector3(), tipLifted: false, brush: true,
});

/** His size: the body is [RADIUS] across before this. */
const SCALE = 1.25;
const RADIUS = 0.3;
/** How far the body sits above his feet' soles. */
const FEET = 0.03;
const SHADOW_LIFT = 0.045;
/** His shadow's size, before it's widened to his arms and shrunk as he hops. */
const SHADOW_W = 0.62;
const SHADOW_D = 0.48;
const TURN_SPEED = 600;
const PLAY_TO_CAMERA = 0.4;
const PLAY_HELD = 0.8;
/** At work: how far he leans (degrees), and his arm's reach (his own units). */
const LEAN_FLAT = 8;
const LEAN_UPRIGHT = 3;
const PLAY_AT_WORK = 0.25;
const PLAY_PONDER = 0.55;
const REACH = 0.48;
/** Seconds a stroke takes, and the part of it his hand is down. */
const STROKE = 0.85;
const STROKE_DOWN = 0.8;
/** Seconds to write a line. */
const LINE = 1.6;
/** Every this many strokes, he dips his brush in the palette. */
const PALETTE_EVERY = 6;
/** Considering a piece: how far he steps back (metres), how far his head tips (degrees), and how often (seconds) he holds the brush up to size it and for how long. */
const STEP_BACK = 0.22;
const TILT = 9;
const SIZE_UP_EVERY = 7;
const SIZE_UP = 1.8;
/** How far the tool's tip lifts off the work between cells (metres). */
const LIFT = 0.03;
/** The flourish's hops: how many, seconds each, and how high (metres). */
const FLOURISH_HOPS = 2;
const HOP = 0.42;
const HOP_HEIGHT = 0.09;
/** Done by hand at a page, these write in lines rather than sketch: the journal. */
const WRITING = new Set(["journal"]);
/** A held book: from his shoulders, how high and how far out its bottom is, and where his hands hold it. */
const BOOK_Y = -0.12;
const BOOK_Z = 0.31;
const BOOK_HAND_X = 0.15;
const BOOK_HAND_Y = 0.14;
/** Seconds between page turns, and how long one takes. */
const PAGE_EVERY = 4.5;
const PAGE_TURN = 0.7;
/** The held guitar's body, from his shoulders, and how far its neck tips up (degrees). */
const GUITAR_X = 0.08;
const GUITAR_Y = -0.04;
const GUITAR_Z = 0.3;
const GUITAR_TILT = -28;
/** Strums (radians a second), and seconds a chord is held. */
const STRUM = 13;
const CHORD = 1.6;
/** The beat he nods and taps to, listening (radians a second). */
const BEAT = 5.2;
const WALK_RATE = 13;
const SPRING = 160;
const DAMPING = 9;
const BLINK = 0.13;
const SIDES = [-1, 1];
const TWO_PI = Math.PI * 2;

const LIMB = shade(BODY, -0.02);
const FOOT = shade(BODY, -0.055, 0.02);

const angleDiff = (target: number, from: number) => ((((target - from + 540) % 360) + 360) % 360) - 180;

/**
 * The little guy in 3D: a butter-yellow tater with stubby arms and feet, drawn in the hand-drawn look:
 * crayon shading and hatching, a boiling ink outline, and a face painted in pen on the body's
 * texture. Animated entirely in code, ported from `Blob.kt`, so he moves the same.
 *
 * What drives him is state only ([Pose]): needs pick the expression and how he breathes; walking hops
 * him with squash and stretch; the activity under way picks a pose.
 */
export class Blob {
  private readonly measure: BodyMeasure;
  private readonly body: Placed;
  private readonly arms: Placed[];
  private readonly feet: Placed[];
  private readonly faces: Faces;
  private readonly face: Overlay;
  private readonly shadow: Mesh;
  private readonly shadowWidth: number;

  private readonly bodyAt = new Matrix4();
  private readonly limbAt = new Matrix4();
  private time = 0;
  private yaw = 0;
  private walkPhase = 0;
  private wasMoving = false;
  private bounce = 0;
  private bounceVelocity = 0;
  private blinkIn = 3;
  private blinkLeft = 0;
  private lie = 0;
  /** 0..1, how far he has settled in to the work: slid up to it and leaning over it. */
  private settle = 0;
  /** Toward the work, flat, and the axis he leans on to bend toward it. */
  private readonly workDir = new Vector3();
  private readonly leanAxis = new Vector3();
  private slideTo = 0;
  /** 0..1, how far he has stepped back from the work to look at it, and his head's tilt (degrees) as he does. */
  private back = 0;
  private tilt = 0;
  private readonly tipLocal = new Vector3();
  private readonly toolDir = new Vector3();
  private readonly turn = new Quaternion();
  private readonly root = new Matrix4();
  private readonly inverse = new Matrix4();
  private readonly hold = new Vector3();
  private readonly rotation = new Matrix4();
  private readonly strokeNow = { t: 0, n: 0, lifted: false, nod: 0 };

  /** The current expression, after blinking. */
  expression: Face = "neutral";
  private shut = false;
  /** The top of his head in world space, for anchoring a bubble. */
  readonly headTop = new Vector3();
  /** What he has in his hands this frame, for the view to draw at [heldAt]. */
  held: Held = "none";
  /** Where the [held] thing goes, in his own (scaled) frame: a book's bottom centre, the guitar's body. */
  readonly heldAt = new Matrix4();
  /** True while he makes a piece ([Pose.art]): the brush or crayon in his working hand goes at [toolAt]. */
  toolShown = false;
  readonly toolAt = new Matrix4();
  /** True while he paints: the palette in his other hand goes at [paletteAt]. */
  paletteShown = false;
  readonly paletteAt = new Matrix4();
  private readonly toolTip: number;

  constructor(private readonly r: CrayonRenderer, toolTip: number) {
    this.toolTip = toolTip;
    const grain = paintGrain();
    this.faces = new Faces(grain);
    this.face = new Overlay(new Vector4(FACE_X / DESIGN_W, FACE_Y / DESIGN_H, FACE_W / DESIGN_W, FACE_H / DESIGN_H));

    // Hull seeds count up per part: body, then each side's arm and foot.
    let seed = 1;
    const part = (geo: PartGeometry, color: Rgba, base: number, vary: number, texture: ReturnType<typeof grain.texture> | null = null) =>
      new CrayonModel([{ geo, color: linear(color), texture, outline: { base, vary, seed: seed++, wander: 0 }, place: new Matrix4() }]);
    const bodyGeo = taterBody(RADIUS);
    this.measure = bodyGeo.measure();
    // The body's colour is all in its texture.
    this.body = r.add(part(bodyGeo, hex("#ffffff"), 0.009, 0.006, grain.texture()), this.face);
    this.arms = [];
    this.feet = [];
    SIDES.forEach((_, i) => {
      this.arms.push(r.add(part(lumpySphere(0.065, 0.075, 0.065, 16, 12, 20 + i, 0.07), LIMB, 0.008, 0.004)));
      this.feet.push(r.add(part(lumpySphere(0.075, 0.045, 0.095, 16, 12, 30 + i, 0.07), FOOT, 0.008, 0.004)));
    });
    this.shadow = r.shadow();
    this.shadowWidth = Math.max(this.measure.armX, 0.25) / RADIUS;
  }

  /** Advances his animation by [delta] seconds toward [pose], and places him. */
  update(delta: number, pose: Pose) {
    const dt = Math.min(delta, 0.1);
    this.time += dt;
    const time = this.time;
    const asleep = pose.doing === "sleep" && !pose.moving;
    const tired = clamp((40 - pose.energy) / 30, 0, 1);
    const glum = clamp((45 - pose.mood) / 30, 0, 1);

    const still = !pose.moving && !asleep;
    this.held = !still ? "none" : pose.doing === "read" ? "book" : pose.doing === "play_guitar" ? "guitar" : "none";
    const held = this.held;

    // Turn toward where he faces, the short way round. Model +Z is compass 270 (south, the camera).
    // Standing still he turns partway toward the camera, so his face reads; less with his hands on
    // his work, more with something in his hands to show.
    let target = pose.facing + 90;
    if (!pose.moving) {
      const play = pose.art === "flourish" ? PLAY_HELD
        // Considering the piece, he glances round, so the thinking shows.
        : pose.art === "ponder" ? PLAY_PONDER
        : pose.workAt !== null ? PLAY_AT_WORK
        : held !== "none" ? PLAY_HELD
        : PLAY_TO_CAMERA;
      target += angleDiff(0, target) * play;
    }
    this.yaw += clamp(angleDiff(target, this.yaw), -TURN_SPEED * dt, TURN_SPEED * dt);

    // A little landing squash whenever he starts or stops walking.
    if (pose.moving !== this.wasMoving) this.bounceVelocity -= 1.6;
    this.wasMoving = pose.moving;
    this.bounceVelocity += (-SPRING * this.bounce - DAMPING * this.bounceVelocity) * dt;
    this.bounce += this.bounceVelocity * dt;
    if (pose.moving) this.walkPhase += dt * WALK_RATE;
    else this.walkPhase = 0;
    const walkPhase = this.walkPhase;

    // Blinking, every few seconds; not while asleep, when the eyes are shut anyway.
    this.blinkIn -= dt;
    if (this.blinkIn <= 0) {
      this.blinkLeft = BLINK;
      this.blinkIn = 2.2 + Math.random() * 3.3;
    }
    this.blinkLeft -= dt;
    this.expression = asleep ? "asleep"
      : pose.art === "flourish" || pose.art === "admire" ? "happy"
      : pose.thinking || pose.doing === "think" || pose.art === "ponder" ? "thinking"
      : pose.energy < 20 ? "tired"
      : pose.mood < 30 ? "sad"
      : pose.mood >= 65 || pose.music || pose.doing === "play_guitar" ? "happy"
      : "neutral";
    this.shut = this.blinkLeft > 0 && this.expression !== "asleep";

    // Squash and stretch: breathing, the walk cycle, chewing, and the landing spring.
    const breathRate = asleep ? 1.1 : lerp(2.3, 1.3, tired);
    const breathAmp = asleep ? 0.045 : 0.025;
    let squash = 1 + breathAmp * Math.sin(time * breathRate) + this.bounce;
    squash -= 0.06 * tired + 0.03 * glum;
    if (pose.moving) squash -= 0.07 * Math.cos(2 * walkPhase);
    if (pose.doing === "eat" && !pose.moving) squash += 0.04 * Math.sin(time * 10);
    const stretchXZ = 1 / Math.sqrt(squash);
    let hop = pose.moving ? Math.abs(Math.sin(walkPhase)) * 0.07 : 0;
    // A piece done: two little hops of joy.
    if (pose.art === "flourish" && pose.artTime < FLOURISH_HOPS * HOP) hop += HOP_HEIGHT * Math.abs(Math.sin((pose.artTime * Math.PI) / HOP));

    // He sleeps propped on his pillow, leaning back far enough that his face still shows.
    this.lie = lerp(this.lie, asleep ? 1 : 0, Math.min(dt * 4, 1));
    const lie = this.lie;
    // When music plays and he's idle, or he plays the guitar, he sways; listening, he nods along.
    const sway = (pose.music || held === "guitar") && still ? 6 * Math.sin(time * 2.6) : 0;
    const listening = still && pose.doing === "listen";
    const nod = listening ? 6 * (0.5 + 0.5 * Math.sin(time * BEAT)) : 0;

    // At work he slides up to it (off the chair's middle, toward the desk) and leans toward it, more over a page.
    const work = !pose.moving && !asleep ? pose.workAt : null;
    this.settle = lerp(this.settle, work !== null ? 1 : 0, Math.min(dt * 3, 1));
    const settle = this.settle;
    const workDir = this.workDir;
    if (work !== null) workDir.set(work.x - pose.x, 0, work.z - pose.z).normalize();
    const stroke = this.strokeAt(time);
    if (work !== null) this.slideTo = pose.workSlide;
    // Between strokes, and with the piece done, he steps back to look at it, his head on one side.
    const looking = work !== null && (pose.art === "ponder" || pose.art === "admire" || pose.art === "flourish");
    this.back = lerp(this.back, looking ? 1 : 0, Math.min(dt * 2.5, 1));
    this.tilt = lerp(this.tilt, work !== null && pose.art === "ponder" ? TILT * Math.sin(pose.artTime * 1.1 + 0.6) : 0, Math.min(dt * 3, 1));
    const slide = (this.slideTo - STEP_BACK * this.back) * settle;
    const lean = settle * (
      pose.art === "stroke" ? (pose.workFlat ? LEAN_FLAT + 2 : LEAN_UPRIGHT + 4)
      : pose.art !== null ? -3 * this.back
      : pose.workFlat ? LEAN_FLAT + 3 * stroke.nod
      : LEAN_UPRIGHT
    );

    const root = this.root;
    root.makeTranslation(pose.x + workDir.x * slide, pose.standY + hop, pose.z + workDir.z * slide + lie * 0.08);
    rotate(root, this.leanAxis.set(workDir.z, 0, -workDir.x), lean);
    rotate(root, Y_AXIS, asleep ? lerp(this.yaw, 0, lie) : this.yaw);
    rotate(root, X_AXIS, -38 * lie);
    rotate(root, Z_AXIS, sway + this.tilt);
    rotate(root, X_AXIS, nod);
    scale(root, SCALE, SCALE, SCALE);
    const m = this.measure;
    scale(translate(this.bodyAt.copy(root), 0, FEET + m.bottom * squash, 0), stretchXZ, squash, stretchXZ);
    this.body.set(this.bodyAt);
    const shoulder = FEET + (m.bottom + m.armY) * squash;
    const flip = pageTurn(time);
    // Held up in front of his tummy, its top tipped toward him, tipping more as he turns a page.
    if (held === "book") rotate(translate(this.heldAt.copy(root), 0, shoulder + BOOK_Y, BOOK_Z), X_AXIS, -14 - 8 * flip);
    // Across his front, the neck up to one side, rocking a little with each strum.
    else if (held === "guitar") rotate(translate(this.heldAt.copy(root), GUITAR_X, shoulder + GUITAR_Y, GUITAR_Z), Z_AXIS, GUITAR_TILT + 2 * Math.sin(time * STRUM));

    // At work, the hand nearer the camera does it, so it shows.
    const hold = this.hold;
    const handZ = (side: number) => hold.set(side, 0, 0).transformDirection(root).z;
    const hand = work === null ? -1 : handZ(SIDES[0]) >= handZ(SIDES[1]) ? 0 : 1;
    SIDES.forEach((side, i) => {
      const step = pose.moving ? Math.sin(walkPhase + i * Math.PI) * 0.09 : 0;
      const tap = listening && side > 0 ? 0.035 * Math.max(0, Math.sin(time * BEAT)) : 0;
      this.feet[i].set(translate(this.limbAt.copy(root), side * 0.13, 0.045 + (step > 0 ? step * 0.3 : 0) + tap, m.footZ + step));

      // Arms: swing while walking; activity poses otherwise.
      let ax = side * (m.armX * stretchXZ + 0.015);
      let ay = FEET + (m.bottom + m.armY) * squash;
      let az = pose.moving ? -step * 0.8 : 0;
      if (!pose.moving && !asleep) {
        switch (pose.doing) {
          case "sketch": case "journal": case "paint":
            if (side > 0) { ax *= 0.75; az = 0.24; ay += 0.06 + 0.025 * Math.sin(time * 15); }
            break;
          case "eat":
            if (Math.trunc(time * 2.5) % 2 === i) { ax *= 0.6; az = 0.26; ay += 0.15; }
            break;
          case "read":
            // Either side of the book; now and then the near hand reaches over to turn a page.
            hold.set(side * BOOK_HAND_X, BOOK_HAND_Y, 0.01).applyMatrix4(this.heldAt);
            this.toLocal(hold);
            ax = hold.x - (side > 0 ? 2 * BOOK_HAND_X * Math.sin(flip * Math.PI) : 0);
            ay = hold.y + (side > 0 ? 0.06 * Math.sin(flip * Math.PI) : 0);
            az = hold.z + 0.02;
            break;
          case "play_guitar":
            // One hand strums over the sound hole; the other holds a chord up the neck, moving to the next every so often.
            if (side > 0) hold.set(0.02, -0.02 + 0.05 * Math.sin(time * STRUM), 0.06);
            else hold.set(-0.36 - 0.06 * (Math.trunc(time / CHORD) % 3), 0, 0.04);
            hold.applyMatrix4(this.heldAt);
            this.toLocal(hold);
            ax = hold.x;
            ay = hold.y;
            az = hold.z;
            break;
          case "sell_art":
            if (side > 0) { az = 0.22; ay += 0.2 + 0.04 * Math.sin(time * 6); }
            break;
          default:
            ay += pose.music ? 0.03 * Math.sin(time * 5.2 + i * Math.PI) : 0.008 * Math.sin(time * 2.3 + i);
        }
      }
      if (work !== null) {
        if (pose.art !== null) this.artHand(pose, side, i === hand);
        else this.workHand(work, pose, stroke, side, i === hand);
        ax = lerp(ax, hold.x, settle);
        ay = lerp(ay, hold.y, settle);
        az = lerp(az, hold.z, settle);
      }
      this.arms[i].set(translate(this.limbAt.copy(root), ax, ay, az));
      if (work !== null && pose.art !== null) this.props(pose, i === hand, ax, ay, az);
    });
    this.toolShown = work !== null && pose.art !== null;
    this.paletteShown = this.toolShown && pose.brush && !pose.workFlat;

    const shrink = SCALE * (1 - hop * 2);
    this.shadow.matrix.makeTranslation(pose.x, pose.standY + SHADOW_LIFT, pose.z);
    scale(this.shadow.matrix, shrink * this.shadowWidth * SHADOW_W, 1, shrink * SHADOW_D);
    this.shadow.matrixWorldNeedsUpdate = true;
    // Not while he sleeps.
    this.shadow.visible = !asleep;
    this.headTop.set(0, FEET + m.bottom + m.top + 0.2, 0).applyMatrix4(root);
    if (asleep) this.headTop.set(pose.x, pose.standY + 0.6, pose.z);

    this.face.texture.value = this.faces.get(this.expression, this.shut, this.r.frame);
  }

  /** One stroke of the pen or brush: where in it he is (0..1), which stroke it is, and whether his hand is lifted. */
  private strokeAt(time: number) {
    const s = this.strokeNow;
    const cycle = time / STROKE;
    s.n = Math.trunc(cycle);
    const f = cycle - s.n;
    s.lifted = f > STROKE_DOWN;
    s.t = s.lifted ? 1 : f / STROKE_DOWN;
    s.nod = s.lifted ? -1 : Math.sin(s.t * Math.PI);
    return s;
  }

  /**
   * Sets [hold] to where hand [side] goes at [work], in his own (root) frame. The [working] hand
   * goes to the work: scribbling over a sketch, writing lines in the journal, or dabbing at the
   * canvas with a trip to the palette now and then. The other holds the page down, or the palette.
   */
  private workHand(work: Vector3, pose: Pose, stroke: Blob["strokeNow"], side: number, working: boolean) {
    const hold = this.hold;
    const time = this.time;
    const r = rng(stroke.n * 7919 + 17);
    if (pose.workFlat) {
      if (working) {
        if (pose.doing !== null && WRITING.has(pose.doing)) {
          // Writing: along a line, left to right, a line at a time.
          const line = Math.trunc(time / LINE);
          const along = time / LINE - line;
          hold.copy(work).add(tmp.set(-0.07 + 0.14 * along, 0, -0.05 + 0.025 * (line % 5)));
          hold.z += 0.006 * Math.sin(time * 31);
          if (along > 0.9) hold.y += 0.03;
        } else {
          // Sketching: a short loop somewhere on the page, then lift and move.
          const a = stroke.t * TWO_PI * 1.3 + r() * TWO_PI;
          const dx = (r() - 0.5) * 0.12 + 0.025 * Math.cos(a);
          const dz = (r() - 0.5) * 0.08 + 0.018 * Math.sin(1.7 * a);
          hold.copy(work).add(tmp.set(dx, 0, dz));
          if (stroke.lifted) hold.y += 0.035;
        }
        hold.y += 0.02;
      } else {
        // Holding the page down, at its corner nearer that hand.
        hold.copy(work).add(tmp.set(0, 0.02, 0));
        this.toLocal(hold);
        hold.x = side * 0.17;
        return this.reach(side);
      }
    } else if (working) {
      if (stroke.n % PALETTE_EVERY === PALETTE_EVERY - 1) {
        // A dip in the paint: his hands meet at the palette.
        this.palette(-side);
        hold.x += side * 0.08;
        return this.reach(side);
      }
      // A dab: from a spot on the canvas, a short drag, then back off it.
      const angle = r() * TWO_PI;
      const drag = 0.07 * stroke.t;
      const dx = (r() - 0.5) * 0.3 + drag * Math.cos(angle);
      const dy = (r() - 0.5) * 0.34 + drag * Math.sin(angle);
      hold.copy(work).add(tmp.set(dx, dy, 0));
      if (stroke.lifted) hold.z += 0.07;
    } else {
      this.palette(side);
      return this.reach(side);
    }
    this.toLocal(hold);
    this.reach(side);
  }

  /**
   * Sets [hold] to where hand [side] goes while he makes a piece played out stroke by stroke
   * ([Pose.art]), in his own frame. The [working] hand holds the tool: its tip on the cell going on
   * during a stroke, at the palette for a dip, at his chin or held up to size the picture while he
   * considers it, raised high in the flourish, lowered as he admires it. The other holds the palette
   * (painting) or the page down (drawing).
   */
  private artHand(pose: Pose, side: number, working: boolean) {
    const hold = this.hold;
    const t = pose.artTime;
    const m = this.measure;
    const shoulder = FEET + m.bottom + m.armY;
    if (!working) {
      if (pose.workFlat) {
        hold.copy(pose.workAt!).add(tmp.set(0, 0.02, 0));
        this.toLocal(hold);
        hold.x = side * 0.17;
      } else this.palette(side);
      return this.reach(side);
    }
    switch (pose.art!) {
      case "stroke": {
        // His hand a tool's length back from the tip: off the surface and a little toward him.
        const tip = this.tipLocal.copy(pose.tip);
        if (pose.tipLifted) {
          if (pose.workFlat) tip.y += LIFT;
          else tip.z += LIFT;
        }
        const dir = this.toolDir;
        if (pose.workFlat) dir.set(0, 0.7, 0);
        else dir.set(0, 0.18, 0.7);
        dir.sub(tmp.set(this.workDir.x * 0.4, 0, this.workDir.z * 0.4)).normalize();
        hold.copy(tip).addScaledVector(dir, this.toolTip * SCALE);
        this.toLocal(tip);
        this.toLocal(hold);
        break;
      }
      case "dip":
        // Over to the palette in his other hand, and round in the paint.
        this.palette(-side);
        hold.add(tmp.set(side * 0.07 + 0.025 * Math.cos(t * 13), 0.07 + 0.04 * Math.abs(Math.sin(t * 6.5)), 0.02 * Math.sin(t * 13)));
        break;
      case "ponder":
        if (t % SIZE_UP_EVERY > SIZE_UP_EVERY - SIZE_UP) {
          // The painter's look: the brush held up at arm's length against the picture.
          hold.copy(pose.workAt!).add(tmp.set(0, 0.12, 0));
          this.toLocal(hold);
        } else {
          // Brush to his chin, tapping now and then.
          const tap = t % 3 < 1.2 ? 0.014 * Math.sin(t * 11) : 0;
          hold.set(side * 0.13, shoulder + 0.13 + tap, 0.25);
        }
        break;
      case "flourish":
        hold.set(side * (m.armX + 0.06 + 0.05 * Math.sin(t * 15)), shoulder + 0.46, 0.16);
        break;
      case "admire":
        hold.set(side * (m.armX + 0.05), shoulder - 0.05 + 0.01 * Math.sin(t * 2), 0.13);
        break;
    }
    this.reach(side);
  }

  /** Places the tool in the working hand at (x, y, z) in his own frame, its tip toward the work in a stroke and held up or down otherwise, and the palette in the other hand. */
  private props(pose: Pose, working: boolean, x: number, y: number, z: number) {
    if (!working) {
      rotate(translate(this.paletteAt.copy(this.root), x, y - 0.035, z + 0.03), X_AXIS, 14);
      return;
    }
    const dir = this.toolDir;
    switch (pose.art!) {
      case "stroke": dir.copy(this.tipLocal).sub(tmp.set(x, y, z)); break;
      case "dip": dir.set(0, -1, 0.35); break;
      case "ponder": dir.set(0.15, 1, 0.35); break;
      case "flourish": dir.set(0.3, 1, 0.15); break;
      case "admire": dir.set(0, -0.6, 1); break;
    }
    translate(this.toolAt.copy(this.root), x, y, z);
    this.toolAt.multiply(this.rotation.makeRotationFromQuaternion(this.turn.setFromUnitVectors(Z_AXIS, dir.normalize())));
  }

  /** Where hand [side] holds the palette, out to his side and low, in his own frame. */
  private palette(side: number) {
    this.hold.set(side * (this.measure.armX + 0.05), this.measure.bottom + this.measure.armY - 0.06, 0.16);
  }

  private toLocal(v: Vector3) {
    v.applyMatrix4(this.inverse.copy(this.root).invert());
  }

  /** Pulls [hold] back within arm's length of shoulder [side]. */
  private reach(side: number) {
    const m = this.measure;
    const hold = this.hold;
    const sx = side * m.armX;
    const sy = FEET + m.bottom + m.armY;
    const dx = hold.x - sx;
    const dy = hold.y - sy;
    const dz = hold.z;
    const d = Math.hypot(dx, dy, dz);
    if (d > REACH) hold.set(sx + (dx / d) * REACH, sy + (dy / d) * REACH, (dz / d) * REACH);
  }
}

const tmp = new Vector3();

/** 0 most of the time; 0..1 while he turns a page, every [PAGE_EVERY] seconds. */
function pageTurn(time: number): number {
  const f = (time % PAGE_EVERY) / PAGE_TURN;
  return f < 1 ? f : 0;
}
