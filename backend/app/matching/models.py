from datetime import UTC, datetime
from enum import StrEnum
from uuid import UUID, uuid4

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    JSON,
    CheckConstraint,
    DateTime,
    Enum,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.core.config import MATCHING_EMBEDDING_DIMENSIONS
from app.db.base import Base


def utcnow() -> datetime:
    return datetime.now(UTC)


class MatchStrategy(StrEnum):
    PROFILE = "PROFILE"
    SEMANTIC = "SEMANTIC"
    PROFILE_FALLBACK = "PROFILE_FALLBACK"


class MatchHistory(Base):
    __tablename__ = "match_history"
    __table_args__ = (
        UniqueConstraint(
            "user_a_id",
            "user_b_id",
            name="uq_match_history_canonical_pair",
        ),
        CheckConstraint("user_a_id < user_b_id", name="canonical_pair"),
    )

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    user_a_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    user_b_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    strategy: Mapped[MatchStrategy] = mapped_column(
        Enum(MatchStrategy, native_enum=False, length=20)
    )
    similarity_score: Mapped[float | None] = mapped_column(Float, default=None)
    model_name: Mapped[str | None] = mapped_column(String(100), default=None)
    model_version: Mapped[str | None] = mapped_column(String(80), default=None)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    @classmethod
    def create(
        cls,
        first: UUID,
        second: UUID,
        strategy: MatchStrategy,
        **values: object,
    ) -> "MatchHistory":
        if first == second:
            raise ValueError("a user cannot be matched with themselves")
        user_a_id, user_b_id = sorted((first, second), key=str)
        return cls(
            user_a_id=user_a_id,
            user_b_id=user_b_id,
            strategy=strategy,
            **values,
        )


class LetterEmbedding(Base):
    __tablename__ = "letter_embeddings"
    __table_args__ = (
        CheckConstraint(
            f"dimensions = {MATCHING_EMBEDDING_DIMENSIONS}",
            name="dimensions",
        ),
        Index(
            "ix_letter_embeddings_embedding_hnsw",
            "embedding",
            postgresql_using="hnsw",
            postgresql_ops={"embedding": "vector_cosine_ops"},
        ),
    )

    letter_id: Mapped[UUID] = mapped_column(
        ForeignKey("letters.id", ondelete="CASCADE"), primary_key=True
    )
    model_version: Mapped[str] = mapped_column(String(80), primary_key=True)
    owner_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    embedding: Mapped[list[float]] = mapped_column(Vector(MATCHING_EMBEDDING_DIMENSIONS))
    content_hash: Mapped[str] = mapped_column(String(64))
    model_name: Mapped[str] = mapped_column(String(100))
    dimensions: Mapped[int] = mapped_column(
        Integer,
        default=MATCHING_EMBEDDING_DIMENSIONS,
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class UserMatchVector(Base):
    __tablename__ = "user_match_vectors"
    __table_args__ = (
        CheckConstraint(
            "source_count >= 0 AND source_count <= 5",
            name="source_count",
        ),
        Index(
            "ix_user_match_vectors_embedding_hnsw",
            "embedding",
            postgresql_using="hnsw",
            postgresql_ops={"embedding": "vector_cosine_ops"},
        ),
    )

    user_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    model_version: Mapped[str] = mapped_column(String(80), primary_key=True)
    embedding: Mapped[list[float]] = mapped_column(Vector(MATCHING_EMBEDDING_DIMENSIONS))
    source_letter_ids: Mapped[list[str]] = mapped_column(JSON)
    source_count: Mapped[int] = mapped_column(Integer)
    model_name: Mapped[str] = mapped_column(String(100))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
