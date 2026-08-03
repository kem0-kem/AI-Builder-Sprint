"""Add encrypted moderation submissions and immutable decisions."""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0002_moderation"
down_revision: str | Sequence[str] | None = "0001_initial"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "content_submissions",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("owner_id", sa.Uuid(), nullable=False),
        sa.Column("content_type", sa.String(length=30), nullable=False),
        sa.Column("operation", sa.String(length=80), nullable=False),
        sa.Column("target_id", sa.Uuid(), nullable=True),
        sa.Column("ciphertext", sa.Text(), nullable=True),
        sa.Column("nonce", sa.String(length=64), nullable=True),
        sa.Column("content_hash", sa.String(length=64), nullable=False),
        sa.Column("idempotency_key", sa.String(length=255), nullable=False),
        sa.Column("status", sa.String(length=30), nullable=False),
        sa.Column("attempt_count", sa.Integer(), nullable=False),
        sa.Column("next_attempt_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("resolved_resource_id", sa.Uuid(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["owner_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "owner_id",
            "idempotency_key",
            name="uq_content_submissions_owner_id_idempotency_key",
        ),
    )
    op.create_index(
        "ix_content_submissions_owner_id", "content_submissions", ["owner_id"]
    )
    op.create_index(
        "ix_content_submissions_status_next_attempt_at",
        "content_submissions",
        ["status", "next_attempt_at"],
    )
    op.create_table(
        "moderation_decisions",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("submission_id", sa.Uuid(), nullable=False),
        sa.Column("decision", sa.String(length=10), nullable=False),
        sa.Column("categories", sa.JSON(), nullable=False),
        sa.Column("severity", sa.String(length=10), nullable=False),
        sa.Column("confidence", sa.Float(), nullable=False),
        sa.Column("reason", sa.String(length=300), nullable=False),
        sa.Column("provider_request_id", sa.String(length=255), nullable=True),
        sa.Column("provider", sa.String(length=50), nullable=False),
        sa.Column("model", sa.String(length=100), nullable=False),
        sa.Column("prompt_version", sa.String(length=50), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(
            ["submission_id"], ["content_submissions.id"], ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_moderation_decisions_submission_id", "moderation_decisions", ["submission_id"]
    )
    op.execute(
        """
        CREATE FUNCTION reject_moderation_decision_mutation()
        RETURNS trigger AS $$
        BEGIN
            -- An ON DELETE CASCADE runs after its referenced submission row has
            -- disappeared. A direct decision delete still has a live parent.
            IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 AND NOT EXISTS (
                SELECT 1
                FROM content_submissions
                WHERE id = OLD.submission_id
            ) THEN
                RETURN OLD;
            END IF;
            RAISE EXCEPTION 'moderation decision records are immutable';
        END;
        $$ LANGUAGE plpgsql
        """
    )
    op.execute(
        """
        CREATE TRIGGER moderation_decisions_immutable
        BEFORE UPDATE OR DELETE ON moderation_decisions
        FOR EACH ROW EXECUTE FUNCTION reject_moderation_decision_mutation()
        """
    )


def downgrade() -> None:
    op.execute("DROP TRIGGER moderation_decisions_immutable ON moderation_decisions")
    op.execute("DROP FUNCTION reject_moderation_decision_mutation()")
    op.drop_index("ix_moderation_decisions_submission_id", table_name="moderation_decisions")
    op.drop_table("moderation_decisions")
    op.drop_index(
        "ix_content_submissions_status_next_attempt_at", table_name="content_submissions"
    )
    op.drop_index("ix_content_submissions_owner_id", table_name="content_submissions")
    op.drop_table("content_submissions")
