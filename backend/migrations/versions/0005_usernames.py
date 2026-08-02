"""Add optional unique usernames."""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0005_usernames"
down_revision: str | Sequence[str] | None = "0004_matching_vectors"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # ``0001_initial`` creates tables from current model metadata. On a fresh
    # database that metadata already contains username, while a deployed 0004
    # database does not. Keep this revision safe for both upgrade paths.
    if context.is_offline_mode():
        _add_username_schema()
        return
    inspector = sa.inspect(op.get_bind())
    columns = {column["name"] for column in inspector.get_columns("users")}
    if "username" not in columns:
        op.add_column("users", sa.Column("username", sa.String(length=30), nullable=True))
    constraints = {item["name"] for item in inspector.get_check_constraints("users")}
    if "ck_users_username_lowercase" not in constraints:
        op.create_check_constraint(
            "username_lowercase",
            "users",
            "username IS NULL OR username = lower(username)",
        )
    indexes = {item["name"] for item in inspector.get_indexes("users")}
    if "ix_users_username" not in indexes:
        op.create_index("ix_users_username", "users", ["username"], unique=True)


def _add_username_schema() -> None:
    op.add_column("users", sa.Column("username", sa.String(length=30), nullable=True))
    op.create_check_constraint(
        "username_lowercase",
        "users",
        "username IS NULL OR username = lower(username)",
    )
    op.create_index("ix_users_username", "users", ["username"], unique=True)


def downgrade() -> None:
    op.drop_index("ix_users_username", table_name="users")
    op.drop_constraint(
        "username_lowercase",
        "users",
        type_="check",
    )
    op.drop_column("users", "username")
