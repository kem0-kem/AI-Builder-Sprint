import json
import math
from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import DateTime, Index, Select, String, Text, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class OutboxEvent(Base):
    """The one application outbox model (shared by chat and moderation)."""

    __tablename__ = "outbox_events"
    __table_args__ = (
        Index(
            "ix_outbox_events_topic_available",
            "topic",
            "published_at",
            "available_at",
        ),
    )

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    topic: Mapped[str] = mapped_column(String(80), index=True)
    aggregate_id: Mapped[UUID] = mapped_column(index=True)
    payload: Mapped[str] = mapped_column(Text)
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)
    available_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )


class OutboxRepository:
    """Adds and claims events in the caller's database transaction."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def add(
        self,
        topic: str,
        aggregate_id: UUID,
        payload: dict[str, object],
        *,
        available_at: datetime | None = None,
    ) -> OutboxEvent:
        if available_at is not None and not _aware(available_at):
            raise InvalidOutboxQuery()
        try:
            _validate_payload(payload, depth=0, state=_PayloadState())
            serialized = json.dumps(
                payload,
                allow_nan=False,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            )
            if len(serialized.encode("utf-8")) > 65_536:
                raise ValueError
        except (RecursionError, TypeError, UnicodeError, ValueError):
            pass
        else:
            event = OutboxEvent(
                topic=topic,
                aggregate_id=aggregate_id,
                payload=serialized,
                available_at=available_at,
            )
            self._session.add(event)
            await self._session.flush()
            return event
        raise OutboxPayloadInvalid() from None

    async def claim(
        self,
        *,
        topic: str,
        now: datetime | None = None,
        limit: int = 100,
    ) -> list[OutboxEvent]:
        """Lock unpublished rows until the surrounding transaction completes."""

        current_time = now or datetime.now(UTC)
        if type(limit) is not int or not 1 <= limit <= 1_000 or not _aware(current_time):
            raise InvalidOutboxQuery()

        statement = build_claim_statement(topic, current_time, limit)
        return list(await self._session.scalars(statement))

    async def mark_published(self, event_id: UUID, *, now: datetime | None = None) -> bool:
        result = await self._session.execute(
            update(OutboxEvent)
            .where(OutboxEvent.id == event_id, OutboxEvent.published_at.is_(None))
            .values(published_at=now or datetime.now(UTC))
        )
        return bool(result.rowcount == 1)  # type: ignore[attr-defined]


class OutboxPayloadInvalid(ValueError):
    def __init__(self) -> None:
        super().__init__("invalid outbox payload")


class InvalidOutboxQuery(ValueError):
    def __init__(self) -> None:
        super().__init__("invalid outbox query")


class _PayloadState:
    def __init__(self) -> None:
        self.nodes = 0
        self.active: set[int] = set()


def _validate_payload(value: object, *, depth: int, state: _PayloadState) -> None:
    state.nodes += 1
    if state.nodes > 1_024 or depth > 20:
        raise ValueError
    if value is None or type(value) in (bool, int, str):
        if isinstance(value, str):
            value.encode("utf-8")
        return
    if type(value) is float:
        if not isinstance(value, float) or not math.isfinite(value):
            raise ValueError
        return
    if type(value) not in (list, dict):
        raise TypeError
    identity = id(value)
    if identity in state.active:
        raise ValueError
    state.active.add(identity)
    try:
        if isinstance(value, list):
            for item in value:
                _validate_payload(item, depth=depth + 1, state=state)
        else:
            assert isinstance(value, dict)
            if any(type(key) is not str for key in value):
                raise TypeError
            for item in value.values():
                _validate_payload(item, depth=depth + 1, state=state)
    finally:
        state.active.remove(identity)


def _aware(value: datetime) -> bool:
    return value.tzinfo is not None and value.utcoffset() is not None


def build_claim_statement(topic: str, now: datetime, limit: int) -> Select[tuple[OutboxEvent]]:
    return (
        select(OutboxEvent)
        .where(
            OutboxEvent.topic == topic,
            OutboxEvent.published_at.is_(None),
            or_(
                OutboxEvent.available_at.is_(None),
                OutboxEvent.available_at <= now,
            ),
        )
        .order_by(OutboxEvent.created_at, OutboxEvent.id)
        .limit(limit)
        .with_for_update(skip_locked=True)
    )
