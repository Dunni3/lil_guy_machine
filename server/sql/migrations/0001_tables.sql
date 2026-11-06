-- 0001: the run database's tables, as of the first build. Applied once, in order, by the server
-- (app/migrations.py). Columns follow the row classes in runlog/.../Rows.kt, the one definition;
-- `make server-test` fails when a migration and server/schema/rows.schema.json disagree.
-- To change a table: change Rows.kt, `make server-schema`, then add the next numbered migration.

CREATE TABLE content_versions (
    hash text NOT NULL,
    files jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (hash)
);

CREATE TABLE batches (
    id uuid NOT NULL,
    name text,
    spec jsonb,
    created_at timestamp with time zone NOT NULL,
    notes text,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);

CREATE TABLE runs (
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
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    end_reason text,
    notes text,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);

CREATE TABLE sessions (
    id uuid NOT NULL,
    run_id uuid NOT NULL,
    from_checkpoint uuid,
    git_sha text,
    git_dirty boolean,
    content_hash text,
    platform text NOT NULL,
    host text,
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);
CREATE INDEX sessions_run_id ON sessions (run_id);

CREATE TABLE model_calls (
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
CREATE INDEX model_calls_run_id_seq ON model_calls (run_id, seq);

CREATE TABLE decisions (
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
CREATE INDEX decisions_run_id_seq ON decisions (run_id, seq);

CREATE TABLE ledger_entries (
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

CREATE TABLE need_changes (
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

CREATE TABLE hourly_state (
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
CREATE INDEX hourly_state_run_id_tick ON hourly_state (run_id, tick);

CREATE TABLE memory_events (
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
CREATE INDEX memory_events_run_id_session_id_id ON memory_events (run_id, session_id, id);

CREATE TABLE memories (
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
CREATE INDEX memories_run_id_level_id ON memories (run_id, level, id);

CREATE TABLE authored (
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
CREATE INDEX authored_run_id_seq ON authored (run_id, seq);

CREATE TABLE art_pieces (
    run_id uuid NOT NULL,
    session_id uuid NOT NULL,
    seq bigint NOT NULL,
    tick bigint NOT NULL,
    day integer,
    minute_of_day integer,
    piece integer NOT NULL,
    medium text NOT NULL,
    title text,
    tags text[] NOT NULL,
    shows text,
    palette text[] NOT NULL,
    letters text[] NOT NULL,
    ops integer NOT NULL,
    png_blob text,
    commission text,
    artist text,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (run_id, seq)
);
CREATE INDEX art_pieces_run_id_piece ON art_pieces (run_id, piece);

CREATE TABLE checkpoints (
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
    created_at timestamp with time zone NOT NULL,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);
CREATE INDEX checkpoints_run_id_tick ON checkpoints (run_id, tick);

CREATE TABLE run_lineage (
    run_id uuid NOT NULL,
    source_run_id uuid NOT NULL,
    scope text NOT NULL,
    mode text NOT NULL,
    note text,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (run_id, source_run_id, scope)
);

CREATE TABLE run_records (
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

CREATE TABLE letters (
    id uuid NOT NULL,
    run_id uuid NOT NULL,
    direction text NOT NULL,
    tick bigint,
    day integer,
    minute_of_day integer,
    text text NOT NULL,
    reply_to uuid,
    written_at timestamp with time zone,
    delivered_at timestamp with time zone,
    read_at timestamp with time zone,
    _rev bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);

CREATE TABLE blobs (
    hash text PRIMARY KEY,
    encoding text NOT NULL,
    media_type text NOT NULL,
    bytes bytea NOT NULL,
    size integer NOT NULL
);
