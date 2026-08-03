import math
import traceback
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import func, select
from sqlalchemy.dialects import postgresql

from app.chat.models import OutboxEvent as ChatOutboxEvent
from app.events.outbox import (
    InvalidOutboxQuery,
    OutboxEvent,
    OutboxPayloadInvalid,
    OutboxRepository,
    build_claim_statement,
)


def test_chat_and_events_use_the_single_outbox_model() -> None:
    assert OutboxEvent is ChatOutboxEvent
    assert OutboxEvent.__table__.name == "outbox_events"


async def test_outbox_add_rolls_back_with_domain_transaction(session_factory) -> None:
    async with session_factory() as session:
        await OutboxRepository(session).add("test.topic", uuid4(), {"safe": True})
        await session.rollback()
    async with session_factory() as session:
        assert await session.scalar(select(func.count()).select_from(OutboxEvent)) == 0


async def test_claim_is_unpublished_ordered_and_mark_is_compare_and_set(
    session_factory,
) -> None:
    async with session_factory() as session:
        outbox = OutboxRepository(session)
        first = await outbox.add("test.topic", uuid4(), {"position": 1})
        second = await outbox.add("test.topic", uuid4(), {"position": 2})
        ignored = await outbox.add("other.topic", uuid4(), {})
        await session.flush()

        claimed = await outbox.claim(topic="test.topic", limit=10)
        assert {event.id for event in claimed} == {first.id, second.id}
        assert ignored.id not in {event.id for event in claimed}
        assert await outbox.mark_published(first.id)
        assert not await outbox.mark_published(first.id)
        assert {event.id for event in await outbox.claim(topic="test.topic")} == {second.id}


async def test_claim_excludes_future_events_but_null_chat_events_are_immediate(
    session_factory,
) -> None:
    now = datetime(2026, 8, 2, tzinfo=UTC)
    async with session_factory() as session:
        outbox = OutboxRepository(session)
        immediate = await outbox.add("test.topic", uuid4(), {})
        due = await outbox.add(
            "test.topic", uuid4(), {}, available_at=now - timedelta(seconds=1)
        )
        future = await outbox.add(
            "test.topic", uuid4(), {}, available_at=now + timedelta(seconds=1)
        )
        claimed = await outbox.claim(topic="test.topic", now=now)
        assert {event.id for event in claimed} == {immediate.id, due.id}
        assert future.id not in {event.id for event in claimed}


async def test_outbox_rejects_non_standard_payload_without_marker_leak(
    session_factory,
) -> None:
    marker = "RAW-OUTBOX-PAYLOAD-MARKER"
    cycle: list[object] = []
    cycle.append(cycle)
    payloads = [
        {1: marker},
        {"value": math.nan, "marker": marker},
        {"value": object(), "marker": marker},
        {"value": cycle, "marker": marker},
    ]
    async with session_factory() as session:
        for payload in payloads:
            try:
                await OutboxRepository(session).add(
                    "test.topic", uuid4(), payload  # type: ignore[arg-type]
                )
            except OutboxPayloadInvalid as exc:
                rendered = str(exc) + repr(exc) + "".join(traceback.format_exception(exc))
                assert marker not in rendered
                assert exc.__cause__ is None and exc.__context__ is None
            else:
                raise AssertionError("invalid outbox payload was accepted")


async def test_outbox_rejects_naive_times_and_invalid_limits(session_factory) -> None:
    async with session_factory() as session:
        outbox = OutboxRepository(session)
        with pytest.raises(InvalidOutboxQuery):
            await outbox.add("test.topic", uuid4(), {}, available_at=datetime.now())
        for limit in (True, 0, -1, 1001):
            with pytest.raises(InvalidOutboxQuery):
                await outbox.claim(topic="test.topic", limit=limit)  # type: ignore[arg-type]
        with pytest.raises(InvalidOutboxQuery):
            await outbox.claim(topic="test.topic", now=datetime.now())


def test_postgresql_claim_uses_skip_locked() -> None:
    sql = str(
        build_claim_statement("test.topic", datetime.now(UTC), 10).compile(
            dialect=postgresql.dialect()
        )
    )
    assert "FOR UPDATE SKIP LOCKED" in sql
