-- Generated from runlog/src/main/kotlin/dev/lilguy/runlog/Tables.kt by `make server-schema`. Don't edit by hand.

CREATE TABLE IF NOT EXISTS content_versions (
    hash text NOT NULL,
    files jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (hash)
);
ALTER TABLE content_versions ADD COLUMN IF NOT EXISTS files jsonb;
ALTER TABLE content_versions ADD COLUMN IF NOT EXISTS created_at timestamptz;

CREATE TABLE IF NOT EXISTS batches (
    id uuid NOT NULL,
    name text,
    spec jsonb,
    created_at timestamptz NOT NULL,
    notes text,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);
ALTER TABLE batches ADD COLUMN IF NOT EXISTS name text;
ALTER TABLE batches ADD COLUMN IF NOT EXISTS spec jsonb;
ALTER TABLE batches ADD COLUMN IF NOT EXISTS created_at timestamptz;
ALTER TABLE batches ADD COLUMN IF NOT EXISTS notes text;

CREATE TABLE IF NOT EXISTS runs (
    id uuid NOT NULL,
    parent_id uuid,
    forked_from uuid,
    batch_id uuid,
    label text,
    scenario text NOT NULL,
    platform text NOT NULL,
    host text,
    git_sha text,
    git_dirty boolean,
    content_hash text,
    seed bigint NOT NULL,
    ticks_per_minute integer NOT NULL,
    personality jsonb,
    granted text[],
    models jsonb NOT NULL,
    settings jsonb,
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    end_reason text,
    notes text,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);
ALTER TABLE runs ADD COLUMN IF NOT EXISTS parent_id uuid;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS forked_from uuid;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS batch_id uuid;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS label text;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS scenario text;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS platform text;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS host text;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS git_sha text;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS git_dirty boolean;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS content_hash text;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS seed bigint;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS ticks_per_minute integer;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS personality jsonb;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS granted text[];
ALTER TABLE runs ADD COLUMN IF NOT EXISTS models jsonb;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS settings jsonb;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS started_at timestamptz;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS ended_at timestamptz;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS end_reason text;
ALTER TABLE runs ADD COLUMN IF NOT EXISTS notes text;

CREATE TABLE IF NOT EXISTS sessions (
    id uuid NOT NULL,
    run_id uuid NOT NULL,
    from_checkpoint uuid,
    git_sha text,
    git_dirty boolean,
    content_hash text,
    platform text NOT NULL,
    host text,
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS run_id uuid;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS from_checkpoint uuid;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS git_sha text;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS git_dirty boolean;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS content_hash text;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS platform text;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS host text;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS started_at timestamptz;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS ended_at timestamptz;
CREATE INDEX IF NOT EXISTS sessions_run_id ON sessions (run_id);

CREATE TABLE IF NOT EXISTS model_calls (
    id uuid NOT NULL,
    run_id uuid NOT NULL,
    session_id uuid NOT NULL,
    seq bigint NOT NULL,
    tick bigint NOT NULL,
    day integer,
    minute_of_day integer,
    purpose text NOT NULL,
    endpoint text NOT NULL,
    model text NOT NULL,
    calls integer NOT NULL,
    prompt_tokens integer,
    completion_tokens integer,
    reasoning_tokens integer,
    total_tokens integer,
    latency_ms integer,
    price_in double precision,
    price_out double precision,
    cost_usd double precision,
    failed boolean NOT NULL,
    system_blob text,
    request_blob text,
    response_blob text,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS run_id uuid;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS seq bigint;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS tick bigint;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS day integer;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS minute_of_day integer;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS purpose text;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS endpoint text;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS model text;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS calls integer;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS prompt_tokens integer;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS completion_tokens integer;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS reasoning_tokens integer;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS total_tokens integer;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS latency_ms integer;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS price_in double precision;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS price_out double precision;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS cost_usd double precision;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS failed boolean;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS system_blob text;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS request_blob text;
ALTER TABLE model_calls ADD COLUMN IF NOT EXISTS response_blob text;
CREATE INDEX IF NOT EXISTS model_calls_run_id_seq ON model_calls (run_id, seq);

CREATE TABLE IF NOT EXISTS decisions (
    id uuid NOT NULL,
    run_id uuid NOT NULL,
    session_id uuid NOT NULL,
    seq bigint NOT NULL,
    tick bigint NOT NULL,
    day integer,
    minute_of_day integer,
    model_call_id uuid,
    intent jsonb NOT NULL,
    intent_label text NOT NULL,
    thought text,
    choices jsonb,
    fallback boolean NOT NULL,
    error text,
    outcome_status text,
    outcome_text text,
    observation_blob text,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS run_id uuid;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS seq bigint;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS tick bigint;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS day integer;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS minute_of_day integer;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS model_call_id uuid;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS intent jsonb;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS intent_label text;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS thought text;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS choices jsonb;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS fallback boolean;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS error text;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS outcome_status text;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS outcome_text text;
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS observation_blob text;
CREATE INDEX IF NOT EXISTS decisions_run_id_seq ON decisions (run_id, seq);

CREATE TABLE IF NOT EXISTS ledger_entries (
    run_id uuid NOT NULL,
    session_id uuid NOT NULL,
    seq bigint NOT NULL,
    tick bigint NOT NULL,
    day integer,
    minute_of_day integer,
    amount integer NOT NULL,
    balance integer NOT NULL,
    reason text NOT NULL,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (run_id, seq)
);
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS tick bigint;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS day integer;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS minute_of_day integer;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS amount integer;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS balance integer;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS reason text;

CREATE TABLE IF NOT EXISTS need_changes (
    run_id uuid NOT NULL,
    session_id uuid NOT NULL,
    seq bigint NOT NULL,
    tick bigint NOT NULL,
    day integer,
    minute_of_day integer,
    need text NOT NULL,
    delta double precision NOT NULL,
    value double precision NOT NULL,
    reason text NOT NULL,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (run_id, seq)
);
ALTER TABLE need_changes ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE need_changes ADD COLUMN IF NOT EXISTS tick bigint;
ALTER TABLE need_changes ADD COLUMN IF NOT EXISTS day integer;
ALTER TABLE need_changes ADD COLUMN IF NOT EXISTS minute_of_day integer;
ALTER TABLE need_changes ADD COLUMN IF NOT EXISTS need text;
ALTER TABLE need_changes ADD COLUMN IF NOT EXISTS delta double precision;
ALTER TABLE need_changes ADD COLUMN IF NOT EXISTS value double precision;
ALTER TABLE need_changes ADD COLUMN IF NOT EXISTS reason text;

CREATE TABLE IF NOT EXISTS hourly_state (
    run_id uuid NOT NULL,
    session_id uuid NOT NULL,
    seq bigint NOT NULL,
    tick bigint NOT NULL,
    day integer,
    minute_of_day integer,
    hour integer NOT NULL,
    money integer NOT NULL,
    needs jsonb NOT NULL,
    skills jsonb,
    doing text,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (run_id, seq)
);
ALTER TABLE hourly_state ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE hourly_state ADD COLUMN IF NOT EXISTS tick bigint;
ALTER TABLE hourly_state ADD COLUMN IF NOT EXISTS day integer;
ALTER TABLE hourly_state ADD COLUMN IF NOT EXISTS minute_of_day integer;
ALTER TABLE hourly_state ADD COLUMN IF NOT EXISTS hour integer;
ALTER TABLE hourly_state ADD COLUMN IF NOT EXISTS money integer;
ALTER TABLE hourly_state ADD COLUMN IF NOT EXISTS needs jsonb;
ALTER TABLE hourly_state ADD COLUMN IF NOT EXISTS skills jsonb;
ALTER TABLE hourly_state ADD COLUMN IF NOT EXISTS doing text;
CREATE INDEX IF NOT EXISTS hourly_state_run_id_tick ON hourly_state (run_id, tick);

CREATE TABLE IF NOT EXISTS memory_events (
    run_id uuid NOT NULL,
    session_id uuid NOT NULL,
    seq bigint NOT NULL,
    tick bigint NOT NULL,
    day integer,
    minute_of_day integer,
    id bigint NOT NULL,
    time text,
    kind text NOT NULL,
    text text NOT NULL,
    significance double precision,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (run_id, id)
);
ALTER TABLE memory_events ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE memory_events ADD COLUMN IF NOT EXISTS seq bigint;
ALTER TABLE memory_events ADD COLUMN IF NOT EXISTS tick bigint;
ALTER TABLE memory_events ADD COLUMN IF NOT EXISTS day integer;
ALTER TABLE memory_events ADD COLUMN IF NOT EXISTS minute_of_day integer;
ALTER TABLE memory_events ADD COLUMN IF NOT EXISTS time text;
ALTER TABLE memory_events ADD COLUMN IF NOT EXISTS kind text;
ALTER TABLE memory_events ADD COLUMN IF NOT EXISTS text text;
ALTER TABLE memory_events ADD COLUMN IF NOT EXISTS significance double precision;
CREATE INDEX IF NOT EXISTS memory_events_run_id_session_id_id ON memory_events (run_id, session_id, id);

CREATE TABLE IF NOT EXISTS memories (
    run_id uuid NOT NULL,
    session_id uuid NOT NULL,
    seq bigint NOT NULL,
    tick bigint NOT NULL,
    day integer,
    minute_of_day integer,
    id bigint NOT NULL,
    level integer NOT NULL,
    from_id bigint NOT NULL,
    to_id bigint NOT NULL,
    from_day integer,
    from_minute text,
    to_day integer,
    to_minute text,
    text text NOT NULL,
    source text NOT NULL,
    importance double precision,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (run_id, id)
);
ALTER TABLE memories ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS seq bigint;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS tick bigint;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS day integer;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS minute_of_day integer;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS level integer;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS from_id bigint;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS to_id bigint;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS from_day integer;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS from_minute text;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS to_day integer;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS to_minute text;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS text text;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS source text;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS importance double precision;
CREATE INDEX IF NOT EXISTS memories_run_id_level_id ON memories (run_id, level, id);

CREATE TABLE IF NOT EXISTS authored (
    id uuid NOT NULL,
    run_id uuid NOT NULL,
    session_id uuid NOT NULL,
    seq bigint NOT NULL,
    tick bigint NOT NULL,
    day integer,
    minute_of_day integer,
    op text NOT NULL,
    verb text,
    kept boolean NOT NULL,
    writer text,
    result jsonb NOT NULL,
    error text,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);
ALTER TABLE authored ADD COLUMN IF NOT EXISTS run_id uuid;
ALTER TABLE authored ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE authored ADD COLUMN IF NOT EXISTS seq bigint;
ALTER TABLE authored ADD COLUMN IF NOT EXISTS tick bigint;
ALTER TABLE authored ADD COLUMN IF NOT EXISTS day integer;
ALTER TABLE authored ADD COLUMN IF NOT EXISTS minute_of_day integer;
ALTER TABLE authored ADD COLUMN IF NOT EXISTS op text;
ALTER TABLE authored ADD COLUMN IF NOT EXISTS verb text;
ALTER TABLE authored ADD COLUMN IF NOT EXISTS kept boolean;
ALTER TABLE authored ADD COLUMN IF NOT EXISTS writer text;
ALTER TABLE authored ADD COLUMN IF NOT EXISTS result jsonb;
ALTER TABLE authored ADD COLUMN IF NOT EXISTS error text;
CREATE INDEX IF NOT EXISTS authored_run_id_seq ON authored (run_id, seq);

CREATE TABLE IF NOT EXISTS art_pieces (
    run_id uuid NOT NULL,
    session_id uuid NOT NULL,
    seq bigint NOT NULL,
    tick bigint NOT NULL,
    day integer,
    minute_of_day integer,
    piece integer NOT NULL,
    medium text NOT NULL,
    title text,
    tags text[],
    shows text,
    palette text[],
    letters text[],
    ops integer,
    png_blob text,
    commission text,
    artist text,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (run_id, seq)
);
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS tick bigint;
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS day integer;
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS minute_of_day integer;
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS piece integer;
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS medium text;
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS title text;
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS tags text[];
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS shows text;
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS palette text[];
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS letters text[];
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS ops integer;
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS png_blob text;
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS commission text;
ALTER TABLE art_pieces ADD COLUMN IF NOT EXISTS artist text;
CREATE INDEX IF NOT EXISTS art_pieces_run_id_piece ON art_pieces (run_id, piece);

CREATE TABLE IF NOT EXISTS checkpoints (
    id uuid NOT NULL,
    run_id uuid NOT NULL,
    session_id uuid NOT NULL,
    tick bigint NOT NULL,
    day integer NOT NULL,
    minute_of_day integer NOT NULL,
    reason text NOT NULL,
    content_hash text NOT NULL,
    git_sha text,
    world_state jsonb NOT NULL,
    agent_state jsonb NOT NULL,
    watermarks jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS run_id uuid;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS tick bigint;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS day integer;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS minute_of_day integer;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS reason text;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS content_hash text;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS git_sha text;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS world_state jsonb;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS agent_state jsonb;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS watermarks jsonb;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS created_at timestamptz;
CREATE INDEX IF NOT EXISTS checkpoints_run_id_tick ON checkpoints (run_id, tick);

CREATE TABLE IF NOT EXISTS run_lineage (
    run_id uuid NOT NULL,
    source_run_id uuid NOT NULL,
    scope text NOT NULL,
    mode text NOT NULL,
    note text,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (run_id, source_run_id, scope)
);
ALTER TABLE run_lineage ADD COLUMN IF NOT EXISTS mode text;
ALTER TABLE run_lineage ADD COLUMN IF NOT EXISTS note text;

CREATE TABLE IF NOT EXISTS run_records (
    run_id uuid NOT NULL,
    session_id uuid NOT NULL,
    seq bigint NOT NULL,
    tick bigint NOT NULL,
    day integer,
    minute_of_day integer,
    kind text NOT NULL,
    data jsonb NOT NULL,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (run_id, seq)
);
ALTER TABLE run_records ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE run_records ADD COLUMN IF NOT EXISTS tick bigint;
ALTER TABLE run_records ADD COLUMN IF NOT EXISTS day integer;
ALTER TABLE run_records ADD COLUMN IF NOT EXISTS minute_of_day integer;
ALTER TABLE run_records ADD COLUMN IF NOT EXISTS kind text;
ALTER TABLE run_records ADD COLUMN IF NOT EXISTS data jsonb;

CREATE TABLE IF NOT EXISTS letters (
    id uuid NOT NULL,
    run_id uuid NOT NULL,
    direction text NOT NULL,
    tick bigint,
    day integer,
    minute_of_day integer,
    text text NOT NULL,
    reply_to uuid,
    written_at timestamptz,
    delivered_at timestamptz,
    read_at timestamptz,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);
ALTER TABLE letters ADD COLUMN IF NOT EXISTS run_id uuid;
ALTER TABLE letters ADD COLUMN IF NOT EXISTS direction text;
ALTER TABLE letters ADD COLUMN IF NOT EXISTS tick bigint;
ALTER TABLE letters ADD COLUMN IF NOT EXISTS day integer;
ALTER TABLE letters ADD COLUMN IF NOT EXISTS minute_of_day integer;
ALTER TABLE letters ADD COLUMN IF NOT EXISTS text text;
ALTER TABLE letters ADD COLUMN IF NOT EXISTS reply_to uuid;
ALTER TABLE letters ADD COLUMN IF NOT EXISTS written_at timestamptz;
ALTER TABLE letters ADD COLUMN IF NOT EXISTS delivered_at timestamptz;
ALTER TABLE letters ADD COLUMN IF NOT EXISTS read_at timestamptz;

CREATE TABLE IF NOT EXISTS blobs (
    hash text PRIMARY KEY,
    encoding text NOT NULL,
    media_type text NOT NULL,
    bytes bytea NOT NULL,
    size integer NOT NULL
);
