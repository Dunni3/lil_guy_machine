import { Matrix4, Vector3 } from "three";

export const DEG = Math.PI / 180;
export const X_AXIS = new Vector3(1, 0, 0);
export const Y_AXIS = new Vector3(0, 1, 0);
export const Z_AXIS = new Vector3(0, 0, 1);

const scratch = new Matrix4();
const scaling = new Vector3();

// Matrices compose as the app's do: each call applies in the frame of what came before.

export function translate(m: Matrix4, x: number, y: number, z: number): Matrix4 {
  return m.multiply(scratch.makeTranslation(x, y, z));
}

/** [axis] needn't be unit length; a zero axis or angle leaves [m] as it is. */
export function rotate(m: Matrix4, axis: Vector3, degrees: number): Matrix4 {
  const len = axis.length();
  if (degrees === 0 || len === 0) return m;
  scaling.copy(axis).divideScalar(len);
  return m.multiply(scratch.makeRotationAxis(scaling, degrees * DEG));
}

export function scale(m: Matrix4, x: number, y: number, z: number): Matrix4 {
  return m.scale(scaling.set(x, y, z));
}

export const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
export const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
