from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import DateTime, ForeignKey, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class FeedCategory(Base):
    __tablename__ = "feed_categories"

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    slug: Mapped[str] = mapped_column(String(40), unique=True)
    name: Mapped[str] = mapped_column(String(40))


class Feed(Base):
    __tablename__ = "feeds"

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    author_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), index=True)
    category_id: Mapped[UUID] = mapped_column(ForeignKey("feed_categories.id"), index=True)
    title: Mapped[str] = mapped_column(String(120))
    content: Mapped[str] = mapped_column(Text)
    moderation_status: Mapped[str] = mapped_column(String(20), default="VISIBLE")
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )


class Comment(Base):
    __tablename__ = "comments"

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    feed_id: Mapped[UUID] = mapped_column(ForeignKey("feeds.id"), index=True)
    author_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), index=True)
    parent_comment_id: Mapped[UUID | None] = mapped_column(ForeignKey("comments.id"), default=None)
    content: Mapped[str] = mapped_column(Text)
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )


class FeedLike(Base):
    __tablename__ = "feed_likes"

    user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), primary_key=True)
    feed_id: Mapped[UUID] = mapped_column(ForeignKey("feeds.id"), primary_key=True)


class ModerationReport(Base):
    __tablename__ = "moderation_reports"
    __table_args__ = (UniqueConstraint("reporter_id", "target_type", "target_id"),)

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    reporter_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), index=True)
    target_type: Mapped[str] = mapped_column(String(20))
    target_id: Mapped[UUID] = mapped_column(index=True)
    reason: Mapped[str] = mapped_column(String(500))
    status: Mapped[str] = mapped_column(String(20), default="PENDING")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )
