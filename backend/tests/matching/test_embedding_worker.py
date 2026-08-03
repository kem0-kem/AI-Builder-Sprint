import json
import math
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select
from sqlalchemy.dialects import postgresql
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.auth.models import User
from app.core.config import MATCHING_EMBEDDING_DIMENSIONS
from app.events.outbox import OutboxEvent
from app.letters.models import Letter
from app.letters.service import LetterCommandHandler
from app.matching.embedding_worker import (
    EmbeddingProjectionRepository,
    LetterEmbeddingWorker,
    build_embedding_owner_lock,
    mean_normalized_vector,
)
from app.matching.gateway import EmbeddingVector
from app.matching.models import UserMatchVector


def make_user(user_id: UUID, email: str) -> User:
    return User(
        id=user_id,
        email=email,
        password_hash="hashed",
        nickname=email,
    )


async def test_matched_delivery_emits_embedding_request_but_personal_does_not(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    sender_id = uuid4()
    recipient_id = uuid4()
    async with session_factory() as session:
        session.add_all(
            [
                make_user(sender_id, "sender-outbox@example.com"),
                make_user(recipient_id, "recipient-outbox@example.com"),
            ]
        )
        await session.commit()

    async with session_factory() as session:
        matched = await LetterCommandHandler(session).execute(
            sender_id,
            {"content": "matched letter", "match": True},
            "embedding-matched-key",
        )
        repeated = await LetterCommandHandler(session).execute(
            sender_id,
            {"content": "matched letter", "match": True},
            "embedding-matched-key",
        )
        await LetterCommandHandler(session).execute(
            sender_id,
            {"content": "personal letter", "match": False},
            "embedding-personal-key",
        )

    async with session_factory() as session:
        events = list(
            await session.scalars(
                select(OutboxEvent).where(
                    OutboxEvent.topic == "letter.embedding.requested"
                )
            )
        )

    assert len(events) == 1
    assert repeated.resource_id == matched.resource_id
    assert events[0].aggregate_id == matched.resource_id
    assert json.loads(events[0].payload) == {
        "letterId": str(matched.resource_id),
        "senderId": str(sender_id),
    }


async def test_embedding_request_rolls_back_with_failed_delivery_commit(
    session_factory: async_sessionmaker[AsyncSession],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sender_id = uuid4()
    recipient_id = uuid4()
    async with session_factory() as session:
        session.add_all(
            [
                make_user(sender_id, "sender-rollback@example.com"),
                make_user(recipient_id, "recipient-rollback@example.com"),
            ]
        )
        await session.commit()

    async with session_factory() as session:
        monkeypatch.setattr(
            session,
            "commit",
            AsyncMock(side_effect=RuntimeError("injected commit failure")),
        )
        with pytest.raises(RuntimeError, match="injected commit failure"):
            await LetterCommandHandler(session).execute(
                sender_id,
                {"content": "rolled back letter", "match": True},
                "embedding-rollback-key",
            )
        await session.rollback()

    async with session_factory() as session:
        events = list(
            await session.scalars(
                select(OutboxEvent).where(
                    OutboxEvent.topic == "letter.embedding.requested"
                )
            )
        )
    assert events == []


async def test_repository_selects_latest_five_authored_matched_letters_only(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    sender_id = uuid4()
    recipient_id = uuid4()
    start = datetime(2026, 1, 1, tzinfo=UTC)
    authored = [
        Letter(
            id=uuid4(),
            sender_id=sender_id,
            recipient_id=recipient_id,
            content=f"authored-{index}",
            created_at=start + timedelta(minutes=index),
        )
        for index in range(6)
    ]
    async with session_factory() as session:
        session.add_all(
            [
                make_user(sender_id, "sender-selection@example.com"),
                make_user(recipient_id, "recipient-selection@example.com"),
                *authored,
                Letter(
                    sender_id=sender_id,
                    recipient_id=None,
                    content="personal",
                    created_at=start + timedelta(minutes=10),
                ),
                Letter(
                    sender_id=recipient_id,
                    recipient_id=sender_id,
                    content="received",
                    created_at=start + timedelta(minutes=11),
                ),
            ]
        )
        await session.commit()

        recent = await EmbeddingProjectionRepository(
            session
        ).latest_authored_matched_letters(sender_id, limit=5)

    assert [letter.id for letter in recent] == [letter.id for letter in authored[-5:]]


async def test_repository_persists_embedding_and_upserts_user_projection(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    sender_id = uuid4()
    recipient_id = uuid4()
    letter = Letter(
        id=uuid4(),
        sender_id=sender_id,
        recipient_id=recipient_id,
        content="persisted embedding",
    )
    vector = EmbeddingVector(
        values=[1.0, *([0.0] * (MATCHING_EMBEDDING_DIMENSIONS - 1))]
    )
    async with session_factory() as session:
        session.add_all(
            [
                make_user(sender_id, "sender-persist@example.com"),
                make_user(recipient_id, "recipient-persist@example.com"),
                letter,
            ]
        )
        await session.flush()
        repository = EmbeddingProjectionRepository(session)
        repository.store_embeddings(
            [letter],
            [vector],
            model_name="solar-embedding-2",
            model_version="solar-embedding-2-v1",
        )
        await repository.flush()

        stored = await repository.vectors_for_letters(
            [letter.id], "solar-embedding-2-v1"
        )
        await repository.upsert_user_vector(
            sender_id,
            mean_normalized_vector(stored),
            [letter.id],
            model_name="solar-embedding-2",
            model_version="solar-embedding-2-v1",
        )
        await repository.flush()
        projection = await session.get(
            UserMatchVector,
            (sender_id, "solar-embedding-2-v1"),
        )

    assert stored == [vector]
    assert projection is not None
    assert projection.source_letter_ids == [str(letter.id)]
    assert projection.source_count == 1


class FakeGateway:
    def __init__(self) -> None:
        self.calls: list[list[str]] = []

    async def embed_passages(self, texts: list[str]) -> list[EmbeddingVector]:
        self.calls.append(texts)
        return [
            EmbeddingVector(values=[float(index + 1), 1.0, 0.0])
            for index, _ in enumerate(texts)
        ]


class FakeProjectionRepository:
    def __init__(self, letters: list[Letter]) -> None:
        self.letters = letters
        self.embeddings: dict[tuple[UUID, str], EmbeddingVector] = {}
        self.source_letter_ids: list[UUID] = []
        self.representative: EmbeddingVector | None = None
        self.flush_count = 0

    async def get_matched_letter(self, letter_id: UUID) -> Letter | None:
        return next(
            (
                letter
                for letter in self.letters
                if letter.id == letter_id and letter.recipient_id is not None
            ),
            None,
        )

    async def latest_authored_matched_letters(
        self, user_id: UUID, *, limit: int
    ) -> list[Letter]:
        eligible = sorted(
            (
                letter
                for letter in self.letters
                if letter.sender_id == user_id and letter.recipient_id is not None
            ),
            key=lambda letter: (letter.created_at, str(letter.id)),
        )
        return eligible[-limit:]

    async def lock_owner(self, user_id: UUID) -> None:
        del user_id

    async def embedded_letter_ids(
        self, letter_ids: list[UUID], model_version: str
    ) -> set[UUID]:
        return {
            letter_id
            for letter_id in letter_ids
            if (letter_id, model_version) in self.embeddings
        }

    def store_embeddings(
        self,
        letters: list[Letter],
        vectors: list[EmbeddingVector],
        *,
        model_name: str,
        model_version: str,
    ) -> None:
        del model_name
        for letter, vector in zip(letters, vectors, strict=True):
            self.embeddings[(letter.id, model_version)] = vector

    async def vectors_for_letters(
        self, letter_ids: list[UUID], model_version: str
    ) -> list[EmbeddingVector]:
        return [self.embeddings[(letter_id, model_version)] for letter_id in letter_ids]

    async def upsert_user_vector(
        self,
        user_id: UUID,
        vector: EmbeddingVector,
        source_letter_ids: list[UUID],
        *,
        model_name: str,
        model_version: str,
    ) -> None:
        del user_id, model_name, model_version
        self.representative = vector
        self.source_letter_ids = source_letter_ids

    async def flush(self) -> None:
        self.flush_count += 1


async def test_worker_is_idempotent_and_rebuilds_normalized_recent_five_vector() -> None:
    sender_id = uuid4()
    recipient_id = uuid4()
    start = datetime(2026, 1, 1, tzinfo=UTC)
    authored = [
        Letter(
            id=uuid4(),
            sender_id=sender_id,
            recipient_id=recipient_id,
            content=f"letter-{index}",
            created_at=start + timedelta(minutes=index),
        )
        for index in range(6)
    ]
    repository = FakeProjectionRepository(
        [
            *authored,
            Letter(
                id=uuid4(),
                sender_id=sender_id,
                recipient_id=None,
                content="personal",
                created_at=start + timedelta(minutes=10),
            ),
            Letter(
                id=uuid4(),
                sender_id=recipient_id,
                recipient_id=sender_id,
                content="received",
                created_at=start + timedelta(minutes=11),
            ),
        ]
    )
    gateway = FakeGateway()
    worker = LetterEmbeddingWorker(
        repository,
        gateway,
        model_name="solar-embedding-2",
        model_version="solar-embedding-2-v1",
    )

    await worker.process(authored[-1].id)
    await worker.process(authored[-1].id)

    expected = mean_normalized_vector(
        [EmbeddingVector(values=[float(index + 1), 1.0, 0.0]) for index in range(5)]
    )
    assert gateway.calls == [[letter.content for letter in authored[-5:]]]
    assert repository.source_letter_ids == [letter.id for letter in authored[-5:]]
    assert repository.representative == expected
    assert math.isclose(sum(value * value for value in expected.values), 1.0)
    assert repository.flush_count == 2


def test_worker_serializes_projection_rebuilds_by_owner() -> None:
    sql = str(
        build_embedding_owner_lock(uuid4()).compile(
            dialect=postgresql.dialect(),
            compile_kwargs={"literal_binds": True},
        )
    )

    assert "FOR UPDATE OF users" in sql
