from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import JSON, DateTime, ForeignKey, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class Letter(Base):
    __tablename__ = "letters"

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    sender_id: Mapped[UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    recipient_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("users.id"), index=True, default=None
    )
    content: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )


class MailboxEntry(Base):
    __tablename__ = "mailbox_entries"

    user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), primary_key=True)
    letter_id: Mapped[UUID] = mapped_column(ForeignKey("letters.id"), primary_key=True)
    direction: Mapped[str] = mapped_column(String(10))


class UserBlock(Base):
    __tablename__ = "user_blocks"

    blocker_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), primary_key=True)
    blocked_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), primary_key=True)


class IdempotencyRecord(Base):
    __tablename__ = "idempotency_records"
    __table_args__ = (UniqueConstraint("user_id", "key"),)

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), index=True)
    key: Mapped[str] = mapped_column(String(80))
    response: Mapped[dict[str, object]] = mapped_column(JSON)
