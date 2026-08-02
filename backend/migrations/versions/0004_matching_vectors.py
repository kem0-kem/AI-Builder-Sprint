"""Add pgvector persistence for profile and semantic matching."""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from pgvector.sqlalchemy import Vector

revision: str = "0004_matching_vectors"
down_revision: str | Sequence[str] | None = "0003_moderation_review_lifecycle"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

EMBEDDING_DIMENSIONS = 1024


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.create_table(
        "match_history",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_a_id", sa.Uuid(), nullable=False),
        sa.Column("user_b_id", sa.Uuid(), nullable=False),
        sa.Column("strategy", sa.String(length=20), nullable=False),
        sa.Column("similarity_score", sa.Float(), nullable=True),
        sa.Column("model_name", sa.String(length=100), nullable=True),
        sa.Column("model_version", sa.String(length=80), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "user_a_id < user_b_id",
            name="canonical_pair",
        ),
        sa.ForeignKeyConstraint(["user_a_id"], ["users.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["user_b_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "user_a_id",
            "user_b_id",
            name="uq_match_history_canonical_pair",
        ),
    )
    op.create_index("ix_match_history_user_a_id", "match_history", ["user_a_id"])
    op.create_index("ix_match_history_user_b_id", "match_history", ["user_b_id"])
    op.create_table(
        "letter_embeddings",
        sa.Column("letter_id", sa.Uuid(), nullable=False),
        sa.Column("model_version", sa.String(length=80), nullable=False),
        sa.Column("owner_id", sa.Uuid(), nullable=False),
        sa.Column("embedding", Vector(EMBEDDING_DIMENSIONS), nullable=False),
        sa.Column("content_hash", sa.String(length=64), nullable=False),
        sa.Column("model_name", sa.String(length=100), nullable=False),
        sa.Column("dimensions", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            f"dimensions = {EMBEDDING_DIMENSIONS}",
            name="dimensions",
        ),
        sa.ForeignKeyConstraint(["letter_id"], ["letters.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["owner_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("letter_id", "model_version"),
    )
    op.create_index("ix_letter_embeddings_owner_id", "letter_embeddings", ["owner_id"])
    op.create_index(
        "ix_letter_embeddings_embedding_hnsw",
        "letter_embeddings",
        ["embedding"],
        postgresql_using="hnsw",
        postgresql_ops={"embedding": "vector_cosine_ops"},
    )
    op.create_table(
        "user_match_vectors",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("model_version", sa.String(length=80), nullable=False),
        sa.Column("embedding", Vector(EMBEDDING_DIMENSIONS), nullable=False),
        sa.Column("source_letter_ids", sa.JSON(), nullable=False),
        sa.Column("source_count", sa.Integer(), nullable=False),
        sa.Column("model_name", sa.String(length=100), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "source_count >= 0 AND source_count <= 5",
            name="source_count",
        ),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("user_id", "model_version"),
    )
    op.create_index(
        "ix_user_match_vectors_embedding_hnsw",
        "user_match_vectors",
        ["embedding"],
        postgresql_using="hnsw",
        postgresql_ops={"embedding": "vector_cosine_ops"},
    )


def downgrade() -> None:
    op.drop_index(
        "ix_user_match_vectors_embedding_hnsw",
        table_name="user_match_vectors",
        postgresql_using="hnsw",
    )
    op.drop_table("user_match_vectors")
    op.drop_index(
        "ix_letter_embeddings_embedding_hnsw",
        table_name="letter_embeddings",
        postgresql_using="hnsw",
    )
    op.drop_index("ix_letter_embeddings_owner_id", table_name="letter_embeddings")
    op.drop_table("letter_embeddings")
    op.drop_index("ix_match_history_user_b_id", table_name="match_history")
    op.drop_index("ix_match_history_user_a_id", table_name="match_history")
    op.drop_table("match_history")
