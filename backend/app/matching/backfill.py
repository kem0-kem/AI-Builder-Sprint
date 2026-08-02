"""Bounded, resumable projection backfill for already delivered letters.

This module deliberately knows nothing about moderation submissions.  It reads
only ``letters`` with a recipient, which is the durable representation of a
letter that was allowed to be delivered.
"""

from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import Protocol
from uuid import UUID

from sqlalchemy import literal, select, tuple_
from sqlalchemy.ext.asyncio import AsyncSession

from app.letters.models import Letter
from app.matching.models import LetterEmbedding

MAX_BACKFILL_LIMIT = 500
PROVIDER_BATCH_SIZE = 32


class BackfillCommandError(ValueError):
    """A safe, operator-facing backfill error.

    Do not put cursor values or letter details in this exception: command-line
    output is frequently retained by deployment systems.
    """


@dataclass(frozen=True, slots=True)
class LetterCursor:
    created_at: datetime
    id: UUID


@dataclass(frozen=True, slots=True)
class BackfillPage:
    processed: int
    next_cursor: UUID | None
    exhausted: bool


class BackfillRepository(Protocol):
    async def resolve_letter_cursor(self, letter_id: UUID) -> LetterCursor: ...

    async def matched_letter_ids(
        self, *, boundary: LetterCursor | None, limit: int
    ) -> list[UUID]: ...

    async def embedded_letter_ids(
        self, letter_ids: Sequence[UUID], model_version: str
    ) -> set[UUID]: ...


class BackfillWorker(Protocol):
    model_version: str

    async def process(self, letter_id: UUID) -> None: ...


class SqlAlchemyBackfillRepository:
    """Read-side repository used by the command and PostgreSQL integration tests."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def resolve_letter_cursor(self, letter_id: UUID) -> LetterCursor:
        row = await self._session.execute(
            select(Letter.created_at, Letter.id).where(
                Letter.id == letter_id,
                Letter.recipient_id.is_not(None),
            )
        )
        boundary = row.one_or_none()
        if boundary is None:
            raise BackfillCommandError("cursor does not identify a delivered letter")
        return LetterCursor(created_at=boundary.created_at, id=boundary.id)

    async def matched_letter_ids(
        self, *, boundary: LetterCursor | None, limit: int
    ) -> list[UUID]:
        statement = (
            select(Letter.id)
            .where(Letter.recipient_id.is_not(None))
            .order_by(Letter.created_at.desc(), Letter.id.desc())
            .limit(limit)
        )
        if boundary is not None:
            statement = statement.where(
                tuple_(Letter.created_at, Letter.id)
                < tuple_(literal(boundary.created_at), literal(boundary.id))
            )
        return list(await self._session.scalars(statement))

    async def embedded_letter_ids(
        self, letter_ids: Sequence[UUID], model_version: str
    ) -> set[UUID]:
        if not letter_ids:
            return set()
        return set(
            await self._session.scalars(
                select(LetterEmbedding.letter_id).where(
                    LetterEmbedding.letter_id.in_(letter_ids),
                    LetterEmbedding.model_version == model_version,
                )
            )
        )


class MatchEmbeddingBackfill:
    """Feed historical delivered letters through the normal projection worker.

    The optional transaction callbacks let the CLI commit every successful
    provider-sized batch.  The worker itself never commits; this preserves the
    application's transaction ownership and makes a page safe to retry.
    """

    def __init__(
        self,
        repository: BackfillRepository,
        worker: BackfillWorker,
        *,
        commit_batch: Callable[[], Awaitable[None]] | None = None,
        rollback_batch: Callable[[], Awaitable[None]] | None = None,
    ) -> None:
        self._repository = repository
        self._worker = worker
        self._commit_batch = commit_batch
        self._rollback_batch = rollback_batch

    async def run(self, *, after: UUID | None, limit: int) -> BackfillPage:
        bounded_limit = clamp_backfill_limit(limit)
        boundary = (
            await self._repository.resolve_letter_cursor(after) if after is not None else None
        )
        letter_ids = await self._repository.matched_letter_ids(
            boundary=boundary,
            limit=bounded_limit,
        )
        processed = 0

        for batch in _chunks(letter_ids, PROVIDER_BATCH_SIZE):
            try:
                existing = await self._repository.embedded_letter_ids(
                    batch,
                    self._worker.model_version,
                )
                for letter_id in batch:
                    if letter_id not in existing:
                        # LetterEmbeddingWorker remains the sole place that hashes,
                        # normalizes, calls the provider, and upserts projections.
                        await self._worker.process(letter_id)
                if self._commit_batch is not None:
                    await self._commit_batch()
            except Exception:
                if self._rollback_batch is not None:
                    await self._rollback_batch()
                raise
            processed += len(batch)

        return BackfillPage(
            processed=processed,
            next_cursor=letter_ids[-1] if letter_ids else None,
            exhausted=len(letter_ids) < bounded_limit,
        )


def clamp_backfill_limit(limit: int) -> int:
    return max(1, min(MAX_BACKFILL_LIMIT, limit))


def _chunks(values: Sequence[UUID], size: int) -> list[Sequence[UUID]]:
    return [values[index : index + size] for index in range(0, len(values), size)]
