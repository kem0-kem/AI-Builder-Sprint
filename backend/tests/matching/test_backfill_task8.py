from collections.abc import Sequence
from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest

from app.matching.backfill import (
    BackfillCommandError,
    LetterCursor,
    MatchEmbeddingBackfill,
    clamp_backfill_limit,
)


class FakeRepository:
    def __init__(self, ids: list[UUID], existing: set[UUID] | None = None) -> None:
        self.ids = ids
        self.existing = existing or set()
        self.boundaries: list[UUID] = []
        self.batches: list[list[UUID]] = []

    async def resolve_letter_cursor(self, letter_id: UUID) -> LetterCursor:
        self.boundaries.append(letter_id)
        if letter_id not in self.ids:
            raise BackfillCommandError("cursor does not identify a delivered letter")
        return LetterCursor(datetime(2026, 1, 1, tzinfo=UTC), letter_id)

    async def matched_letter_ids(
        self, *, boundary: LetterCursor | None, limit: int
    ) -> list[UUID]:
        ids = self.ids if boundary is None else self.ids[self.ids.index(boundary.id) + 1 :]
        return ids[:limit]

    async def embedded_letter_ids(
        self, letter_ids: Sequence[UUID], model_version: str
    ) -> set[UUID]:
        del model_version
        self.batches.append(list(letter_ids))
        return set(letter_ids) & self.existing


class FakeWorker:
    model_version = "active-model"

    def __init__(self, fail_at: UUID | None = None) -> None:
        self.fail_at = fail_at
        self.processed: list[UUID] = []

    async def process(self, letter_id: UUID) -> None:
        if letter_id == self.fail_at:
            raise RuntimeError("provider failed")
        self.processed.append(letter_id)


def test_backfill_limit_is_clamped() -> None:
    assert clamp_backfill_limit(0) == 1
    assert clamp_backfill_limit(-50) == 1
    assert clamp_backfill_limit(501) == 500
    assert clamp_backfill_limit(17) == 17


async def test_backfill_skips_existing_and_uses_at_most_32_ids_per_batch() -> None:
    ids = [uuid4() for _ in range(35)]
    repository = FakeRepository(ids, existing={ids[0], ids[-1]})
    worker = FakeWorker()
    committed = 0

    async def commit() -> None:
        nonlocal committed
        committed += 1

    page = await MatchEmbeddingBackfill(
        repository, worker, commit_batch=commit
    ).run(after=None, limit=35)

    assert all(len(batch) <= 32 for batch in repository.batches)
    assert worker.processed == ids[1:-1]
    assert committed == 2
    assert page.processed == 35
    assert page.next_cursor == ids[-1]
    assert page.exhausted is False


async def test_failed_second_batch_rolls_back_without_returning_advanced_cursor() -> None:
    ids = [uuid4() for _ in range(33)]
    repository = FakeRepository(ids)
    worker = FakeWorker(fail_at=ids[-1])
    committed = 0
    rolled_back = 0

    async def commit() -> None:
        nonlocal committed
        committed += 1

    async def rollback() -> None:
        nonlocal rolled_back
        rolled_back += 1

    with pytest.raises(RuntimeError, match="provider failed"):
        await MatchEmbeddingBackfill(
            repository,
            worker,
            commit_batch=commit,
            rollback_batch=rollback,
        ).run(after=None, limit=33)

    assert committed == 1
    assert rolled_back == 1
    assert worker.processed == ids[:-1]


async def test_cursor_is_resolved_before_keyset_selection() -> None:
    ids = [uuid4() for _ in range(3)]
    repository = FakeRepository(ids, existing=set(ids))
    page = await MatchEmbeddingBackfill(repository, FakeWorker()).run(after=ids[0], limit=5)

    assert repository.boundaries == [ids[0]]
    assert page.processed == 2
    assert page.next_cursor == ids[-1]
    assert page.exhausted is True
