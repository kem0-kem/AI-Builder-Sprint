"""Create the 마음잇기 schema."""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy import Table

# Register all model tables before create_all/drop_all.
from app.auth import models as auth_models  # noqa: F401
from app.chat import models as chat_models  # noqa: F401
from app.db.base import Base
from app.feeds import models as feed_models  # noqa: F401
from app.letters import models as letter_models  # noqa: F401
from app.meetings import models as meeting_models  # noqa: F401
from app.profiles import models as profile_models  # noqa: F401
from app.reports import models as report_models  # noqa: F401

revision: str = "0001_initial"
down_revision: str | Sequence[str] | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

HISTORICAL_TABLE_NAMES = (
    "users",
    "refresh_tokens",
    "chat_rooms",
    "chat_participants",
    "chat_messages",
    "feed_categories",
    "feeds",
    "comments",
    "feed_likes",
    "moderation_reports",
    "letters",
    "mailbox_entries",
    "user_blocks",
    "idempotency_records",
    "invite_candidates",
    "meetings",
    "meeting_participants",
    "interests",
    "user_interests",
    "analysis_snapshots",
    "reflection_reports",
)


def _historical_tables() -> list[Table]:
    return [Base.metadata.tables[name] for name in HISTORICAL_TABLE_NAMES]


def upgrade() -> None:
    Base.metadata.create_all(bind=op.get_bind(), tables=_historical_tables())
    op.create_table(
        "outbox_events",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("topic", sa.String(80), nullable=False),
        sa.Column("aggregate_id", sa.Uuid(), nullable=False),
        sa.Column("payload", sa.Text(), nullable=False),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_outbox_events_topic", "outbox_events", ["topic"])
    op.create_index("ix_outbox_events_aggregate_id", "outbox_events", ["aggregate_id"])


def downgrade() -> None:
    op.drop_table("outbox_events")
    Base.metadata.drop_all(bind=op.get_bind(), tables=_historical_tables())
