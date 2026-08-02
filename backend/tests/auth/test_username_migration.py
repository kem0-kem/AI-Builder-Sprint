from pathlib import Path

from alembic.config import Config
from alembic.script import ScriptDirectory

from app.auth.models import User


def alembic_config() -> Config:
    backend_dir = Path(__file__).resolve().parents[2]
    config = Config(str(backend_dir / "alembic.ini"))
    config.set_main_option("path_separator", "os")
    return config


def test_username_model_is_optional_and_uniquely_indexed() -> None:
    username = User.__table__.c.username
    index = next(item for item in User.__table__.indexes if item.name == "ix_users_username")

    assert username.nullable is True
    assert username.type.length == 30
    assert index.unique is True


def test_username_migration_is_the_only_linear_head() -> None:
    scripts = ScriptDirectory.from_config(alembic_config())
    revision = scripts.get_revision("0005_usernames")

    assert scripts.get_heads() == ["0005_usernames"]
    assert revision is not None
    assert revision.down_revision == "0004_matching_vectors"
