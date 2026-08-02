"""Add optional unique usernames."""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0005_usernames"
down_revision: str | Sequence[str] | None = "0004_matching_vectors"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("users", sa.Column("username", sa.String(length=30), nullable=True))
    op.create_index("ix_users_username", "users", ["username"], unique=True)


def downgrade() -> None:
    op.drop_index("ix_users_username", table_name="users")
    op.drop_column("users", "username")
