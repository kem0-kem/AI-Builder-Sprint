from datetime import UTC, datetime
from enum import StrEnum
from uuid import UUID, uuid4

from sqlalchemy import (
    JSON,
    DateTime,
    Enum,
    Float,
    ForeignKey,
    Index,
    String,
    Text,
    UniqueConstraint,
    event,
)
from sqlalchemy.orm import Mapped, Mapper, mapped_column

from app.db.base import Base
from app.moderation.schemas import ContentType, ModerationDecision, Severity


def utcnow() -> datetime:
    return datetime.now(UTC)


class SubmissionStatus(StrEnum):
    PENDING_REVIEW = "PENDING_REVIEW"
    ALLOWED = "ALLOWED"
    BLOCKED = "BLOCKED"
    MANUAL_REVIEW = "MANUAL_REVIEW"


class ContentSubmission(Base):
    __tablename__ = "content_submissions"
    __table_args__ = (
        UniqueConstraint(
            "owner_id",
            "idempotency_key",
            name="uq_content_submissions_owner_id_idempotency_key",
        ),
        Index(
            "ix_content_submissions_status_next_attempt_at",
            "status",
            "next_attempt_at",
        ),
    )

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    owner_id: Mapped[UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    content_type: Mapped[ContentType] = mapped_column(
        Enum(ContentType, native_enum=False, length=30)
    )
    operation: Mapped[str] = mapped_column(String(80))
    target_id: Mapped[UUID | None] = mapped_column(default=None)
    ciphertext: Mapped[str | None] = mapped_column(Text, default=None)
    nonce: Mapped[str | None] = mapped_column(String(64), default=None)
    content_hash: Mapped[str] = mapped_column(String(64))
    idempotency_key: Mapped[str] = mapped_column(String(255))
    status: Mapped[SubmissionStatus] = mapped_column(
        Enum(SubmissionStatus, native_enum=False, length=30),
        default=SubmissionStatus.PENDING_REVIEW,
    )
    attempt_count: Mapped[int] = mapped_column(default=0)
    next_attempt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)
    resolved_resource_id: Mapped[UUID | None] = mapped_column(default=None)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)


class ModerationDecisionRecord(Base):
    __tablename__ = "moderation_decisions"

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    submission_id: Mapped[UUID] = mapped_column(
        ForeignKey("content_submissions.id", ondelete="CASCADE"), index=True
    )
    decision: Mapped[ModerationDecision] = mapped_column(
        Enum(ModerationDecision, native_enum=False, length=10)
    )
    categories: Mapped[list[str]] = mapped_column(JSON)
    severity: Mapped[Severity] = mapped_column(Enum(Severity, native_enum=False, length=10))
    confidence: Mapped[float] = mapped_column(Float)
    reason: Mapped[str] = mapped_column(String(300))
    provider_request_id: Mapped[str | None] = mapped_column(String(255), default=None)
    provider: Mapped[str] = mapped_column(String(50))
    model: Mapped[str] = mapped_column(String(100))
    prompt_version: Mapped[str] = mapped_column(String(50))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


@event.listens_for(ModerationDecisionRecord, "before_update")
@event.listens_for(ModerationDecisionRecord, "before_delete")
def _prevent_decision_mutation(
    _mapper: Mapper[ModerationDecisionRecord],
    _connection: object,
    _target: ModerationDecisionRecord,
) -> None:
    raise ValueError("moderation decision records are immutable")
