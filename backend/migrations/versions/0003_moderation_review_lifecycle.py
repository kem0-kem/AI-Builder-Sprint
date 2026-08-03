"""Add moderation review result expiry."""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0003_moderation_review_lifecycle"
down_revision: str | Sequence[str] | None = "0002_moderation"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "outbox_events",
        sa.Column("available_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index(
        "ix_outbox_events_topic_available",
        "outbox_events",
        ["topic", "published_at", "available_at"],
    )
    op.add_column(
        "content_submissions",
        sa.Column("result_expires_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index(
        "ix_content_submissions_result_expiry",
        "content_submissions",
        ["status", "content_type", "result_expires_at"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_content_submissions_result_expiry", table_name="content_submissions"
    )
    op.drop_column("content_submissions", "result_expires_at")
    op.drop_index("ix_outbox_events_topic_available", table_name="outbox_events")
    op.drop_column("outbox_events", "available_at")
