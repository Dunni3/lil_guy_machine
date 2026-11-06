-- Views for the eval harness and for asking questions. Recreated on every start.
--
-- A run that was resumed has one session per process lifetime; a resume from a checkpoint replays
-- the ticks after it, so the abandoned branch's rows are still here (sessions.from_checkpoint says
-- where each branch starts). These views count every session's rows; filter on session_id for one branch.

-- One row per life: what it was, how long it lived, what it cost.
CREATE OR REPLACE VIEW v_runs AS
SELECT
    r.id, r.label, r.scenario, r.platform, r.host, r.git_sha, r.git_dirty, r.content_hash,
    r.started_at, r.ended_at, r.end_reason, r.models, r.personality, r.granted,
    coalesce((r.settings ->> 'imported')::boolean, false) AS imported,
    r.parent_id, r.batch_id,
    (SELECT count(*) FROM sessions s WHERE s.run_id = r.id) AS sessions,
    (SELECT max(d.day) FROM decisions d WHERE d.run_id = r.id) AS days_lived,
    (SELECT count(*) FROM decisions d WHERE d.run_id = r.id) AS decisions,
    (SELECT count(*) FROM decisions d WHERE d.run_id = r.id AND d.fallback) AS fallbacks,
    (SELECT sum(m.cost_usd) FROM model_calls m WHERE m.run_id = r.id) AS cost_usd,
    (SELECT sum(coalesce(m.prompt_tokens, 0) + coalesce(m.completion_tokens, 0)) FROM model_calls m WHERE m.run_id = r.id) AS tokens,
    (SELECT count(*) FROM checkpoints c WHERE c.run_id = r.id) AS checkpoints,
    (SELECT count(*) FROM run_lineage l WHERE l.run_id = r.id) AS lineage
FROM runs r;

-- Dollars and tokens per run, game day, purpose and model.
CREATE OR REPLACE VIEW v_cost_by_day AS
SELECT
    run_id, day, purpose, model,
    count(*) AS decides,
    sum(calls) AS calls,
    sum(prompt_tokens) AS prompt_tokens,
    sum(completion_tokens) AS completion_tokens,
    sum(cost_usd) AS cost_usd,
    sum(latency_ms) AS latency_ms,
    count(*) FILTER (WHERE failed) AS failed
FROM model_calls
GROUP BY run_id, day, purpose, model;

-- What he chose, per run and day, with each choice's share and the day's entropy (in nats) over them.
CREATE OR REPLACE VIEW v_verb_mix AS
WITH counts AS (
    SELECT run_id, day, intent_label, count(*) AS n
    FROM decisions
    GROUP BY run_id, day, intent_label
), shares AS (
    SELECT *, n::double precision / sum(n) OVER (PARTITION BY run_id, day) AS share
    FROM counts
)
SELECT run_id, day, intent_label, n, share,
       -sum(share * ln(share)) OVER (PARTITION BY run_id, day) AS entropy
FROM shares;

-- Hours a day each need sat low (below 20; frustration above 60), from the hourly samples.
CREATE OR REPLACE VIEW v_needs_hourly AS
SELECT
    run_id, day,
    count(*) AS hours,
    count(*) FILTER (WHERE (needs ->> 'energy')::double precision < 20) AS low_energy,
    count(*) FILTER (WHERE (needs ->> 'fullness')::double precision < 20) AS low_fullness,
    count(*) FILTER (WHERE (needs ->> 'mood')::double precision < 20) AS low_mood,
    count(*) FILTER (WHERE (needs ->> 'stimulation')::double precision < 20) AS bored,
    count(*) FILTER (WHERE (needs ->> 'frustration')::double precision > 60) AS frustrated,
    min(money) AS money_min,
    max(money) AS money_max
FROM hourly_state
GROUP BY run_id, day;

-- Stretches of three or more decisions in a row that went nowhere: waits, failures, fallbacks.
CREATE OR REPLACE VIEW v_stuck AS
WITH marked AS (
    SELECT run_id, session_id, seq, tick, day, intent_label, outcome_status, fallback,
           (intent_label = 'wait' OR outcome_status = 'failed' OR fallback) AS stuck,
           row_number() OVER (PARTITION BY run_id, session_id ORDER BY seq) AS i
    FROM decisions
), runs_of AS (
    SELECT *, i - row_number() OVER (PARTITION BY run_id, session_id, stuck ORDER BY seq) AS grp
    FROM marked
)
SELECT
    run_id, session_id,
    min(day) AS day, min(tick) AS from_tick, max(tick) AS to_tick,
    count(*) AS decisions,
    count(*) FILTER (WHERE intent_label = 'wait') AS waits,
    count(*) FILTER (WHERE outcome_status = 'failed') AS failed,
    count(*) FILTER (WHERE fallback) AS fallbacks
FROM runs_of
WHERE stuck
GROUP BY run_id, session_id, grp
HAVING count(*) >= 3;
