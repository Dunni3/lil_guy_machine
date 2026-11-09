import type { Content, Delta, LifeState, Logs, ServerMessage } from "../../../protocol/ts/protocol";

/** What a message means for the socket: carry on, or leave and rejoin for a fresh snapshot. */
export type Outcome = "ok" | "rejoin" | "refused";

/**
 * What a client holds of one life, from the host's messages in order: the mirror of the host's
 * `LifeState.apply` and `Logs.merge`. A delta with a gap before it means a message was missed, and
 * only a fresh snapshot can fix that.
 */
export class StreamState {
  life: LifeState | null = null;
  content: Content | null = null;
  caps: Record<string, number> = {};
  refused: { version: number; reason: string } | null = null;

  receive(m: ServerMessage): Outcome {
    switch (m.type) {
      case "welcome":
        this.caps = m.caps;
        this.refused = null;
        return "ok";
      case "refused":
        this.refused = { version: m.version, reason: m.reason };
        return "refused";
      case "content":
        this.content = m;
        return "ok";
      case "snapshot":
        this.life = m.state;
        // It said we had this content (?have=) and we don't: only a join without it gets it again.
        return this.content?.hash === m.state.content ? "ok" : "rejoin";
      case "delta":
        return this.apply(m);
    }
  }

  private apply(d: Delta): Outcome {
    const s = this.life;
    if (!s || d.life !== s.life) return "rejoin";
    if (d.seq <= s.seq) return "ok";
    if (d.seq !== s.seq + 1) return "rejoin";
    this.life = {
      ...s,
      seq: d.seq,
      run: d.run ?? s.run,
      fast: d.fast ?? s.fast,
      things: d.things ?? s.things,
      easel: d.easel ?? s.easel,
      wallet: d.wallet ?? s.wallet,
      growth: d.growth ?? s.growth,
      gallery: d.gallery ?? s.gallery,
      shop: d.shop ?? s.shop,
      mind: d.mind ?? s.mind,
      tokens: d.tokens ?? s.tokens,
      logs: d.logs ? mergeLogs(s.logs, d.logs, this.caps) : s.logs,
    };
    return "ok";
  }
}

const LOGS = ["transactions", "events", "decisions", "thoughts", "memories", "revisions", "trips", "drawings"] as const;

function mergeLogs(have: Logs, add: Logs, caps: Record<string, number>): Logs {
  const out = { ...have };
  for (const log of LOGS) {
    if (add[log].length) (out as Record<string, unknown>)[log] = mergeBySeq<{ seq: number }>(have[log], add[log], caps[log]);
  }
  return out;
}

function mergeBySeq<T extends { seq: number }>(have: T[], add: T[], cap: number): T[] {
  const bySeq = new Map<number, T>();
  for (const e of have) bySeq.set(e.seq, e);
  for (const e of add) bySeq.set(e.seq, e);
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq).slice(-cap);
}
