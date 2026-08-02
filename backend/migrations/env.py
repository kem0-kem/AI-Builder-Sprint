from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool

# Import models so every table is registered on Base.metadata.
from app.auth import models as auth_models  # noqa: F401
from app.chat import models as chat_models  # noqa: F401
from app.core.config import get_settings
from app.db.base import Base
from app.feeds import models as feed_models  # noqa: F401
from app.letters import models as letter_models  # noqa: F401
from app.matching import models as matching_models  # noqa: F401
from app.meetings import models as meeting_models  # noqa: F401
from app.moderation import models as moderation_models  # noqa: F401
from app.profiles import models as profile_models  # noqa: F401
from app.reports import models as report_models  # noqa: F401

config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name)
config.set_main_option("sqlalchemy.url", get_settings().database_url.replace("+asyncpg", ""))
target_metadata = Base.metadata


def run_migrations_offline() -> None:
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata, compare_type=True)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
