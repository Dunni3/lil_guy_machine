/* Generated from schema/protocol.schema.json (protocol/.../*.kt) by gen_types.sh. Don't edit; run `make protocol-types`.
 * schema sha256: 26f55fa6e591b6dd1012dd8d0ee26183a6b39281744afe4fa81fdbbe388a6bbc
 */

/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "ServerMessage".
 */
export type ServerMessage = Content | Delta | Refused | Snapshot | Welcome;
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "RunState".
 */
export type RunState = "ready" | "running" | "paused" | "ended";
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Command".
 */
export type Command = Configure | Pause | Replay | Restart | Resume | Start | Step;

/**
 * The lil guy machine sim host's wire format. Generated from protocol/.../*.kt; don't edit.
 */
export interface Protocol {
  server: ServerMessage;
  command: Command;
  result: CommandResult;
  viewer: Viewer;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Content".
 */
export interface Content {
  type: "content";
  hash: string;
  scenario: string;
  map: MapView;
  catalog: CatalogItem[];
  verbs: VerbInfo[];
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "MapView".
 */
export interface MapView {
  width: number;
  height: number;
  rows: string[];
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "CatalogItem".
 */
export interface CatalogItem {
  id: string;
  name: string;
  group: string;
  price: number;
  pack: number;
  stackable: boolean;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "VerbInfo".
 */
export interface VerbInfo {
  id: string;
  phrase: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Delta".
 */
export interface Delta {
  type: "delta";
  life: string;
  seq: number;
  run: RunInfo | null;
  fast: FastState | null;
  easel: Easel | null;
  wallet: Wallet | null;
  growth: Growth | null;
  gallery: Gallery | null;
  shop: ShopState | null;
  mind: Mind | null;
  tokens: Tokens | null;
  logs: Logs | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "RunInfo".
 */
export interface RunInfo {
  run_id: string;
  run: number;
  scenario: string;
  state: RunState;
  settings: Settings;
  starts: Settings[];
  ticks_per_minute: number;
  end_tick: number | null;
  min_days: number;
  ended_because: string | null;
  notices: string[];
  models: Models;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Settings".
 */
export interface Settings {
  days: number | null;
  speed: number;
  ticks_per_minute: number;
  decide_cap: number | null;
  quiet: number;
  quiet_verbs: string[];
  hold_for_art: boolean;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Models".
 */
export interface Models {
  decision: string;
  memory: string | null;
  thoughts: string | null;
  self: string | null;
  scorer: string | null;
  artist: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "FastState".
 */
export interface FastState {
  tick: number;
  clock: Clock | null;
  needs: Needs | null;
  doing: string | null;
  progress: number | null;
  music: string | null;
  entities: Entity[];
  agents: AgentView[];
  completed: boolean;
  held: boolean;
  held_for: string[];
  fast_forward: number;
  thinking_ms: number | null;
  easel_time: number;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Clock".
 */
export interface Clock {
  day: number;
  time: string;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Needs".
 */
export interface Needs {
  energy: number;
  fullness: number;
  mood: number;
  stimulation: number;
  frustration: number;
  mood_target: number;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Entity".
 */
export interface Entity {
  id: string;
  kind: string;
  x: number;
  y: number;
  height: number;
  facing_degrees: number;
  state: string | null;
  carried_by: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "AgentView".
 */
export interface AgentView {
  id: string;
  intent: IntentView | null;
  status: StatusView | null;
  inventory: string[];
  path: CellView[];
  saying: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "IntentView".
 */
export interface IntentView {
  type: string;
  text: string;
  activity: string | null;
  item: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "StatusView".
 */
export interface StatusView {
  state: string;
  reason: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "CellView".
 */
export interface CellView {
  x: number;
  y: number;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Easel".
 */
export interface Easel {
  underway: UnderwayView | null;
  phase: string;
  phase_seconds: number;
  shown: number;
  drawn: number;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "UnderwayView".
 */
export interface UnderwayView {
  tick: number;
  item: string;
  title: string;
  verb: string | null;
  palette: number[];
  strokes: number;
  of: number;
  crayon: number | null;
  state: string;
  size: number;
  rows: string[];
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Wallet".
 */
export interface Wallet {
  money: number;
  holding: string[];
  owns: {
    [k: string]: number;
  };
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Growth".
 */
export interface Growth {
  skills: {
    [k: string]: number;
  };
  books: BookView[];
  done: {
    [k: string]: Tally;
  };
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "BookView".
 */
export interface BookView {
  id: string;
  title: string;
  read: number;
  chapters: number;
  raises: string[] | null;
  teaches: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Tally".
 */
export interface Tally {
  count: number;
  first_tick: number;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Gallery".
 */
export interface Gallery {
  art: PieceView[];
  commission: CommissionView | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "PieceView".
 */
export interface PieceView {
  id: number;
  item: string;
  title: string | null;
  hung: boolean;
  sells_for: number | null;
  commission: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "CommissionView".
 */
export interface CommissionView {
  id: number;
  wants: string;
  request: string | null;
  reward: number;
  until: string;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "ShopState".
 */
export interface ShopState {
  catalog: CatalogState[];
  verbs: VerbState[];
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "CatalogState".
 */
export interface CatalogState {
  id: string;
  owned: number;
  affordable: boolean;
  gives: string;
  taught_by: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "VerbState".
 */
export interface VerbState {
  id: string;
  locked: string | null;
  gives: string | null;
  taught_by: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Mind".
 */
export interface Mind {
  plan: ThoughtView | null;
  self_text: string | null;
  self_model: SelfModelView | null;
  summarizing: boolean;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "ThoughtView".
 */
export interface ThoughtView {
  seq: number;
  tick: number;
  day: number | null;
  time: string | null;
  thinker: string;
  text: string | null;
  error: string | null;
  asked: AnswerView[];
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "AnswerView".
 */
export interface AnswerView {
  question: string;
  opened: string[];
  answer: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "SelfModelView".
 */
export interface SelfModelView {
  who: string;
  cares: string;
  believes: string;
  working_toward: string;
  learned: string;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Tokens".
 */
export interface Tokens {
  total: TokenTotals;
  by_purpose: {
    [k: string]: TokenTotals;
  };
  days: TokenDay[];
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "TokenTotals".
 */
export interface TokenTotals {
  decides: number;
  calls: number;
  prompt_tokens: number;
  completion_tokens: number;
  latency_ms: number;
  cost_usd: number | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "TokenDay".
 */
export interface TokenDay {
  day: number;
  total: TokenTotals;
  by_purpose: {
    [k: string]: TokenTotals;
  };
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Logs".
 */
export interface Logs {
  transactions: TransactionView[];
  events: EventView[];
  decisions: DecisionView[];
  thoughts: ThoughtView[];
  memories: MemoryView[];
  revisions: RevisionView[];
  trips: TripView[];
  drawings: DrawingView[];
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "TransactionView".
 */
export interface TransactionView {
  seq: number;
  tick: number;
  day: number;
  time: string;
  amount: number;
  balance: number;
  reason: string;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "EventView".
 */
export interface EventView {
  seq: number;
  tick: number;
  text: string;
  outcome: boolean;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "DecisionView".
 */
export interface DecisionView {
  seq: number;
  tick: number;
  intent: IntentView;
  rationale: string | null;
  error: string | null;
  latency_ms: number;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "MemoryView".
 */
export interface MemoryView {
  seq: number;
  level: number;
  span: string;
  from_day: number | null;
  to_day: number | null;
  text: string;
  importance: number | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "RevisionView".
 */
export interface RevisionView {
  seq: number;
  day: number | null;
  changed: boolean;
  note: string | null;
  self: SelfModelView | null;
  error: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "TripView".
 */
export interface TripView {
  seq: number;
  tick: number;
  day: number | null;
  time: string | null;
  shopper: string;
  catalog: string[];
  buy: PickView[];
  save_for: string | null;
  chose_saving: boolean;
  note: string | null;
  error: string | null;
  fell_back: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "PickView".
 */
export interface PickView {
  item: string;
  why: string | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "DrawingView".
 */
export interface DrawingView {
  seq: number;
  piece: number | null;
  tick: number;
  day: number | null;
  time: string | null;
  item: string;
  title: string | null;
  tags: string[];
  commission: string | null;
  shows: string | null;
  palette: number[];
  size: number;
  rows: string[];
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Refused".
 */
export interface Refused {
  type: "refused";
  version: number;
  reason: string;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Snapshot".
 */
export interface Snapshot {
  type: "snapshot";
  state: LifeState;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "LifeState".
 */
export interface LifeState {
  life: string;
  seq: number;
  content: string;
  run: RunInfo;
  fast: FastState;
  easel: Easel;
  wallet: Wallet;
  growth: Growth;
  gallery: Gallery;
  shop: ShopState;
  mind: Mind;
  tokens: Tokens;
  logs: Logs;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Welcome".
 */
export interface Welcome {
  type: "welcome";
  version: number;
  lives: LifeSummary[];
  caps: {
    [k: string]: number;
  };
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "LifeSummary".
 */
export interface LifeSummary {
  id: string;
  scenario: string;
  run_id: string;
  state: RunState;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Configure".
 */
export interface Configure {
  type: "configure";
  settings: Settings;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Pause".
 */
export interface Pause {
  type: "pause";
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Replay".
 */
export interface Replay {
  type: "replay";
  run: number;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Restart".
 */
export interface Restart {
  type: "restart";
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Resume".
 */
export interface Resume {
  type: "resume";
  run: string;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Start".
 */
export interface Start {
  type: "start";
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Step".
 */
export interface Step {
  type: "step";
  ticks: number;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "CommandResult".
 */
export interface CommandResult {
  ok: boolean;
  error: string | null;
  seq: number | null;
  state: RunState | null;
}
/**
 * This interface was referenced by `Protocol`'s JSON-Schema
 * via the `definition` "Viewer".
 */
export interface Viewer {
  admin: boolean;
}

/** The protocol version these types are for; send it as ?v= when joining a stream. */
export const PROTOCOL_VERSION = 1;
