from io import StringIO
from pathlib import Path

from alembic import command
from alembic.config import Config


def render_migration_sql() -> str:
    # Reproduce a long-lived process where current models polluted Base.metadata
    # before the historical 0001 migration executes.
    from app.moderation import models as moderation_models  # noqa: F401

    backend_dir = Path(__file__).resolve().parents[2]
    output = StringIO()
    config = Config(str(backend_dir / "alembic.ini"), output_buffer=output)
    config.set_main_option("path_separator", "os")
    command.upgrade(config, "head", sql=True)
    return output.getvalue()


def render_downgrade_sql() -> str:
    from app.moderation import models as moderation_models  # noqa: F401

    backend_dir = Path(__file__).resolve().parents[2]
    output = StringIO()
    config = Config(str(backend_dir / "alembic.ini"), output_buffer=output)
    config.set_main_option("path_separator", "os")
    command.downgrade(config, "head:base", sql=True)
    return output.getvalue()


def test_immutable_trigger_blocks_direct_writes_but_allows_fk_cascades() -> None:
    sql = render_migration_sql()

    assert "BEFORE UPDATE OR DELETE ON moderation_decisions" in sql
    assert "TG_OP = 'DELETE'" in sql
    assert "pg_trigger_depth() > 1" in sql
    assert "NOT EXISTS" in sql
    assert "FROM content_submissions" in sql
    assert "WHERE id = OLD.submission_id" in sql
    assert "RETURN OLD" in sql
    assert "RAISE EXCEPTION 'moderation decision records are immutable'" in sql
    assert (
        "FOREIGN KEY(submission_id) REFERENCES content_submissions (id) ON DELETE CASCADE"
        in sql
    )
    assert "FOREIGN KEY(owner_id) REFERENCES users (id) ON DELETE CASCADE" in sql
    assert sql.count("CREATE TABLE content_submissions") == 1
    assert sql.count("CREATE TABLE moderation_decisions") == 1


def test_historical_downgrade_drops_moderation_tables_exactly_once_when_polluted() -> None:
    sql = render_downgrade_sql()

    assert sql.count("DROP TABLE moderation_decisions") == 1
    assert sql.count("DROP TABLE content_submissions") == 1
