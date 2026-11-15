import { expect, test } from "vitest";
import exported from "../../../parts/export/parts.json";
import type { Part } from "./geometry";
import { ClayModel } from "./renderer";

// Every part list the export has, clay or crayon, builds in clay: no shape the builder can't make, no NaN.
test("every exported model builds into clay", () => {
  const all = exported as unknown as Record<string, unknown>;
  const lists: [string, Part[]][] = [];
  for (const section of ["items", "kinds", "props", "clay_items", "clay_props"]) {
    for (const [name, parts] of Object.entries(all[section] as Record<string, Part[]>)) lists.push([`${section}/${name}`, parts]);
  }
  expect(lists.length).toBeGreaterThan(40);
  for (const [name, parts] of lists) {
    const model = new ClayModel(parts.map((p) => (p.color === "$color" ? { ...p, color: "#808080" } : p)));
    expect(model.bounds.every(Number.isFinite), name).toBe(true);
  }
});
