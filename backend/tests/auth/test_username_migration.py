from io import StringIO
from pathlib import Path

from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory

from app.auth.models import User


def alembic_config() -> Config:
    backend_dir = Path(__file__).resolve().parents[2]
    config = Config(str(backend_dir / "alembic.ini"))
    config.set_main_option("path_separator", "os")
    return config


def render_username_upgrade_sql() -> str:
    output = StringIO()
    config = alembic_config()
    config.output_buffer = output
    command.upgrade(config, "0004_matching_vectors:0005_usernames", sql=True)
    return output.getvalue()


def render_username_downgrade_sql() -> str:
    output = StringIO()
    config = alembic_config()
    config.output_buffer = output
    command.downgrade(config, "0005_usernames:0004_matching_vectors", sql=True)
    return output.getvalue()


def test_username_model_is_optional_and_uniquely_indexed() -> None:
    username = User.__table__.c.username
    index = next(item for item in User.__table__.indexes if item.name == "ix_users_username")

    assert username.nullable is True
    assert username.type.length == 30
    assert index.unique is True


def test_username_model_enforces_lowercase_when_present() -> None:
    constraint = next(
        item
        for item in User.__table__.constraints
        if item.name == "ck_users_username_lowercase"
    )

    assert str(constraint.sqltext) == "username IS NULL OR username = lower(username)"


def test_username_migration_adds_and_removes_lowercase_constraint() -> None:
    upgrade_sql = render_username_upgrade_sql()
    downgrade_sql = render_username_downgrade_sql()

    assert (
        "CONSTRAINT ck_users_username_lowercase "
        "CHECK (username IS NULL OR username = lower(username))"
    ) in upgrade_sql
    assert "CREATE UNIQUE INDEX ix_users_username ON users (username)" in upgrade_sql
    assert "DROP CONSTRAINT ck_users_username_lowercase" in downgrade_sql
    assert downgrade_sql.index(
        "DROP CONSTRAINT ck_users_username_lowercase"
    ) < downgrade_sql.index("DROP COLUMN username")


def test_username_migration_is_the_only_linear_head() -> None:
    scripts = ScriptDirectory.from_config(alembic_config())
    revision = scripts.get_revision("0005_usernames")

    assert scripts.get_heads() == ["0005_usernames"]
    assert revision is not None
    assert revision.down_revision == "0004_matching_vectors"
