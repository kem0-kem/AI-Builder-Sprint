import asyncio
from collections.abc import AsyncIterator

from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import get_settings

settings = get_settings()
engine = create_async_engine(settings.database_url, pool_pre_ping=True)
SessionFactory = async_sessionmaker(engine, expire_on_commit=False)


async def get_session() -> AsyncIterator[AsyncSession]:
    async with SessionFactory() as session:
        yield session


async def database_is_ready() -> bool:
    """Return whether the configured database can execute a minimal query."""
    try:
        async with asyncio.timeout(2.0):
            async with engine.connect() as connection:
                await connection.execute(text("SELECT 1"))
    except (OSError, SQLAlchemyError, TimeoutError):
        return False
    return True
