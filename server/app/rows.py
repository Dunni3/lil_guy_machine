# Generated from schema/rows.schema.json (runlog/.../Rows.kt) by gen_models.sh. Don't edit; run `make server-schema`.

from __future__ import annotations

from datetime import datetime
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, RootModel, conint


class ContentVersionRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['content_versions']
    hash: str
    files: Any
    created_at: datetime
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class BatchRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['batches']
    id: UUID
    name: str | None = None
    spec: Any
    created_at: datetime
    notes: str | None = None
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class RunRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['runs']
    id: UUID
    parent_id: UUID | None = None
    forked_from: UUID | None = None
    batch_id: UUID | None = None
    label: str | None = None
    scenario: str
    platform: str
    host: str | None = None
    git_sha: str | None = None
    git_dirty: bool | None = None
    content_hash: str | None = None
    seed: int
    ticks_per_minute: int
    personality: Any
    granted: list[str] | None = None
    models: Any
    settings: Any
    started_at: datetime
    ended_at: datetime | None = None
    end_reason: str | None = None
    notes: str | None = None
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class SessionRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['sessions']
    id: UUID
    run_id: UUID
    from_checkpoint: UUID | None = None
    git_sha: str | None = None
    git_dirty: bool | None = None
    content_hash: str | None = None
    platform: str
    host: str | None = None
    started_at: datetime
    ended_at: datetime | None = None
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class ModelCallRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['model_calls']
    id: UUID
    run_id: UUID
    session_id: UUID
    seq: int
    tick: int
    day: int | None = None
    minute_of_day: int | None = None
    purpose: str
    endpoint: str
    model: str
    calls: int
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    reasoning_tokens: int | None = None
    total_tokens: int | None = None
    latency_ms: int | None = None
    price_in: float | None = None
    price_out: float | None = None
    cost_usd: float | None = None
    failed: bool
    system_blob: str | None = None
    request_blob: str | None = None
    response_blob: str | None = None
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class DecisionRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['decisions']
    id: UUID
    run_id: UUID
    session_id: UUID
    seq: int
    tick: int
    day: int | None = None
    minute_of_day: int | None = None
    model_call_id: UUID | None = None
    intent: Any
    intent_label: str
    thought: str | None = None
    choices: Any
    fallback: bool
    error: str | None = None
    outcome_status: str | None = None
    outcome_text: str | None = None
    observation_blob: str | None = None
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class LedgerRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['ledger_entries']
    run_id: UUID
    session_id: UUID
    seq: int
    tick: int
    day: int | None = None
    minute_of_day: int | None = None
    amount: int
    balance: int
    reason: str
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class NeedRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['need_changes']
    run_id: UUID
    session_id: UUID
    seq: int
    tick: int
    day: int | None = None
    minute_of_day: int | None = None
    need: str
    delta: float
    value: float
    reason: str
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class HourlyRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['hourly_state']
    run_id: UUID
    session_id: UUID
    seq: int
    tick: int
    day: int | None = None
    minute_of_day: int | None = None
    hour: int
    money: int
    needs: Any
    skills: Any
    doing: str | None = None
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class MemoryEventRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['memory_events']
    run_id: UUID
    session_id: UUID
    seq: int
    tick: int
    day: int | None = None
    minute_of_day: int | None = None
    id: int
    time: str | None = None
    kind: str
    text: str
    significance: float | None = None
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class MemoryRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['memories']
    run_id: UUID
    session_id: UUID
    seq: int
    tick: int
    day: int | None = None
    minute_of_day: int | None = None
    id: int
    level: int
    from_id: int
    to_id: int
    from_day: int | None = None
    from_minute: str | None = None
    to_day: int | None = None
    to_minute: str | None = None
    text: str
    source: str
    importance: float | None = None
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class AuthoredRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['authored']
    id: UUID
    run_id: UUID
    session_id: UUID
    seq: int
    tick: int
    day: int | None = None
    minute_of_day: int | None = None
    op: str
    verb: str | None = None
    kept: bool
    writer: str | None = None
    result: Any
    error: str | None = None
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class ArtPieceRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['art_pieces']
    run_id: UUID
    session_id: UUID
    seq: int
    tick: int
    day: int | None = None
    minute_of_day: int | None = None
    piece: int
    medium: str
    title: str | None = None
    tags: list[str]
    shows: str | None = None
    palette: list[str]
    letters: list[str]
    ops: int
    png_blob: str | None = None
    commission: str | None = None
    artist: str | None = None
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class CheckpointRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['checkpoints']
    id: UUID
    run_id: UUID
    session_id: UUID
    tick: int
    day: int
    minute_of_day: int
    reason: str
    content_hash: str
    git_sha: str | None = None
    world_state: Any
    agent_state: Any
    watermarks: Any
    created_at: datetime
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class LineageRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['run_lineage']
    run_id: UUID
    source_run_id: UUID
    scope: str
    mode: str
    note: str | None = None
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class RecordRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['run_records']
    run_id: UUID
    session_id: UUID
    seq: int
    tick: int
    day: int | None = None
    minute_of_day: int | None = None
    kind: str
    data: Any
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class LetterRow(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    table: Literal['letters']
    id: UUID
    run_id: UUID
    direction: str
    tick: int | None = None
    day: int | None = None
    minute_of_day: int | None = None
    text: str
    reply_to: UUID | None = None
    written_at: datetime | None = None
    delivered_at: datetime | None = None
    read_at: datetime | None = None
    field_rev: conint(ge=1) = Field(..., alias='_rev')


class Row(
    RootModel[
        ContentVersionRow
        | BatchRow
        | RunRow
        | SessionRow
        | ModelCallRow
        | DecisionRow
        | LedgerRow
        | NeedRow
        | HourlyRow
        | MemoryEventRow
        | MemoryRow
        | AuthoredRow
        | ArtPieceRow
        | CheckpointRow
        | LineageRow
        | RecordRow
        | LetterRow
    ]
):
    root: (
        ContentVersionRow
        | BatchRow
        | RunRow
        | SessionRow
        | ModelCallRow
        | DecisionRow
        | LedgerRow
        | NeedRow
        | HourlyRow
        | MemoryEventRow
        | MemoryRow
        | AuthoredRow
        | ArtPieceRow
        | CheckpointRow
        | LineageRow
        | RecordRow
        | LetterRow
    ) = Field(
        ...,
        description="One row of the lil guy machine run database, as sync sends it. Generated from runlog/.../Rows.kt; don't edit.",
        title='Row',
    )
