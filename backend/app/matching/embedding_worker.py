import hashlib
import math
from collections.abc import Sequence
from datetime import UTC, datetime
from typing import Protocol, cast
from uuid import UUID

from sqlalchemy import Select, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.models import User
from app.letters.models import Letter
from app.matching.gateway import EmbeddingVector
from app.matching.models import LetterEmbedding, UserMatchVector


class PassageEmbeddingGateway(Protocol):
    async def embed_passages(self, texts: list[str]) -> list[EmbeddingVector]: ...


class EmbeddingProjectionIncomplete(RuntimeError):
    """Raised when a representative vector cannot be rebuilt atomically."""


class EmbeddingProjectionStore(Protocol):
    async def get_matched_letter(self, letter_id: UUID) -> Letter | None: ...

    async def latest_authored_matched_letters(
        self, user_id: UUID, *, limit: int
    ) -> list[Letter]: ...

    async def lock_owner(self, user_id: UUID) -> None: ...

    async def embedded_letter_ids(
        self, letter_ids: list[UUID], model_version: str
    ) -> set[UUID]: ...

    def store_embeddings(
        self,
        letters: list[Letter],
        vectors: list[EmbeddingVector],
        *,
        model_name: str,
        model_version: str,
    ) -> None: ...

    async def vectors_for_letters(
        self, letter_ids: list[UUID], model_version: str
    ) -> list[EmbeddingVector]: ...

    async def upsert_user_vector(
        self,
        user_id: UUID,
        vector: EmbeddingVector,
        source_letter_ids: list[UUID],
        *,
        model_name: str,
        model_version: str,
    ) -> None: ...

    async def flush(self) -> None: ...


class EmbeddingProjectionRepository:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def get_matched_letter(self, letter_id: UUID) -> Letter | None:
        return cast(
            Letter | None,
            await self._session.scalar(
                select(Letter).where(
                    Letter.id == letter_id,
                    Letter.recipient_id.is_not(None),
                )
            )
        )

    async def latest_authored_matched_letters(
        self, user_id: UUID, *, limit: int
    ) -> list[Letter]:
        if not 1 <= limit <= 5:
            raise ValueError("embedding source limit must be between 1 and 5")
        newest_first = list(
            await self._session.scalars(
                select(Letter)
                .where(
                    Letter.sender_id == user_id,
                    Letter.recipient_id.is_not(None),
                )
                .order_by(Letter.created_at.desc(), Letter.id.desc())
                .limit(limit)
            )
        )
        newest_first.reverse()
        return newest_first

    async def lock_owner(self, user_id: UUID) -> None:
        locked_id = await self._session.scalar(build_embedding_owner_lock(user_id))
        if locked_id is None:
            raise EmbeddingProjectionIncomplete("embedding owner is unavailable")

    async def embedded_letter_ids(
        self, letter_ids: list[UUID], model_version: str
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

    def store_embeddings(
        self,
        letters: list[Letter],
        vectors: list[EmbeddingVector],
        *,
        model_name: str,
        model_version: str,
    ) -> None:
        if len(letters) != len(vectors):
            raise EmbeddingProjectionIncomplete("embedding batch size mismatch")
        for letter, vector in zip(letters, vectors, strict=True):
            self._session.add(
                LetterEmbedding(
                    letter_id=letter.id,
                    model_version=model_version,
                    owner_id=letter.sender_id,
                    embedding=vector.values,
                    content_hash=hashlib.sha256(letter.content.encode("utf-8")).hexdigest(),
                    model_name=model_name,
                    dimensions=len(vector.values),
                )
            )

    async def vectors_for_letters(
        self, letter_ids: list[UUID], model_version: str
    ) -> list[EmbeddingVector]:
        rows = list(
            await self._session.scalars(
                select(LetterEmbedding).where(
                    LetterEmbedding.letter_id.in_(letter_ids),
                    LetterEmbedding.model_version == model_version,
                )
            )
        )
        by_letter = {
            row.letter_id: EmbeddingVector(values=list(row.embedding)) for row in rows
        }
        try:
            return [by_letter[letter_id] for letter_id in letter_ids]
        except KeyError:
            raise EmbeddingProjectionIncomplete("embedding projection is incomplete") from None

    async def upsert_user_vector(
        self,
        user_id: UUID,
        vector: EmbeddingVector,
        source_letter_ids: list[UUID],
        *,
        model_name: str,
        model_version: str,
    ) -> None:
        projection = await self._session.get(UserMatchVector, (user_id, model_version))
        values = {
            "embedding": vector.values,
            "source_letter_ids": [str(letter_id) for letter_id in source_letter_ids],
            "source_count": len(source_letter_ids),
            "model_name": model_name,
            "updated_at": datetime.now(UTC),
        }
        if projection is None:
            self._session.add(
                UserMatchVector(
                    user_id=user_id,
                    model_version=model_version,
                    **values,
                )
            )
            return
        for field, value in values.items():
            setattr(projection, field, value)

    async def flush(self) -> None:
        await self._session.flush()


class LetterEmbeddingWorker:
    def __init__(
        self,
        repository: EmbeddingProjectionStore,
        gateway: PassageEmbeddingGateway,
        *,
        model_name: str,
        model_version: str,
    ) -> None:
        self.repository = repository
        self.gateway = gateway
        self.model_name = model_name
        self.model_version = model_version

    async def process(self, letter_id: UUID) -> None:
        target = await self.repository.get_matched_letter(letter_id)
        if target is None:
            return
        await self.repository.lock_owner(target.sender_id)
        recent = await self.repository.latest_authored_matched_letters(
            target.sender_id,
            limit=5,
        )
        letter_ids = [letter.id for letter in recent]
        embedded = await self.repository.embedded_letter_ids(
            letter_ids,
            self.model_version,
        )
        missing = [letter for letter in recent if letter.id not in embedded]
        if missing:
            vectors = await self.gateway.embed_passages(
                [letter.content for letter in missing]
            )
            self.repository.store_embeddings(
                missing,
                vectors,
                model_name=self.model_name,
                model_version=self.model_version,
            )
        all_vectors = await self.repository.vectors_for_letters(
            letter_ids,
            self.model_version,
        )
        representative = mean_normalized_vector(all_vectors)
        await self.repository.upsert_user_vector(
            target.sender_id,
            representative,
            letter_ids,
            model_name=self.model_name,
            model_version=self.model_version,
        )
        await self.repository.flush()


def mean_normalized_vector(vectors: Sequence[EmbeddingVector]) -> EmbeddingVector:
    if not vectors:
        raise EmbeddingProjectionIncomplete("cannot average an empty embedding set")
    dimensions = len(vectors[0].values)
    if any(len(vector.values) != dimensions for vector in vectors):
        raise EmbeddingProjectionIncomplete("embedding dimensions are inconsistent")
    mean = [
        math.fsum(vector.values[index] for vector in vectors) / len(vectors)
        for index in range(dimensions)
    ]
    norm = math.sqrt(math.fsum(value * value for value in mean))
    if not math.isfinite(norm) or norm == 0:
        raise EmbeddingProjectionIncomplete("representative embedding is not normalizable")
    return EmbeddingVector(values=[value / norm for value in mean])


def build_embedding_owner_lock(user_id: UUID) -> Select[tuple[UUID]]:
    return (
        select(User.id)
        .where(User.id == user_id)
        .with_for_update(of=User)
    )
