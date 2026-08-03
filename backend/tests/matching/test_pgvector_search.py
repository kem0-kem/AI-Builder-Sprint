import asyncio
import os
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from testcontainers.community.postgres import PostgresContainer

from app.auth.models import User
from app.core.config import MATCHING_EMBEDDING_DIMENSIONS, get_settings
from app.letters.models import UserBlock
from app.matching.gateway import EmbeddingVector
from app.matching.models import MatchHistory, MatchStrategy, UserMatchVector
from app.matching.repository import MatchingRepository

pytestmark = pytest.mark.skipif(
    os.getenv("RUN_POSTGRES_INTEGRATION") != "1",
    reason="set RUN_POSTGRES_INTEGRATION=1 when Docker is available",
)

SENDER_ID = UUID(int=1)
ACTIVE_IDS = [UUID(int=100 + index) for index in range(23)]
INACTIVE_ID = UUID(int=200)
BLOCKED_ID = UUID(int=201)
PRIOR_MATCH_ID = UUID(int=202)
WRONG_MODEL_ID = UUID(int=203)
WRONG_VERSION_ID = UUID(int=204)
MODEL_NAME = "solar-embedding-2"
MODEL_VERSION = "solar-embedding-2-v1"


def alembic_config() -> Config:
    backend_dir = Path(__file__).resolve().parents[2]
    config = Config(str(backend_dir / "alembic.ini"))
    config.set_main_option("path_separator", "os")
    return config


def vector(second_dimension: float) -> list[float]:
    return [1.0, second_dimension, *([0.0] * (MATCHING_EMBEDDING_DIMENSIONS - 2))]


def user(user_id: UUID, *, active: bool = True) -> User:
    return User(
        id=user_id,
        email=f"user-{user_id.int}@example.com",
        password_hash="hash",
        nickname=f"user-{user_id.int}",
        is_active=active,
    )


def projection(
    user_id: UUID,
    second_dimension: float,
    *,
    model_name: str = MODEL_NAME,
    model_version: str = MODEL_VERSION,
) -> UserMatchVector:
    return UserMatchVector(
        user_id=user_id,
        model_version=model_version,
        embedding=vector(second_dimension),
        source_letter_ids=[str(uuid4())],
        source_count=1,
        model_name=model_name,
    )


async def _assert_pgvector_search_orders_eligible_active_model_candidates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    with PostgresContainer("pgvector/pgvector:pg16", driver="psycopg") as postgres:
        database_url = postgres.get_connection_url()
        monkeypatch.setenv("DATABASE_URL", database_url)
        get_settings.cache_clear()
        command.upgrade(alembic_config(), "0004_matching_vectors")

        engine = create_async_engine(database_url)
        session_factory = async_sessionmaker(engine, expire_on_commit=False)
        try:
            active_dimensions = [
                *(index / 100 for index in range(19)),
                0.10,
                0.19,
                0.20,
                0.21,
            ]
            async with session_factory() as session:
                session.add_all(
                    [
                        user(SENDER_ID),
                        *(user(user_id) for user_id in ACTIVE_IDS),
                        user(INACTIVE_ID, active=False),
                        user(BLOCKED_ID),
                        user(PRIOR_MATCH_ID),
                        user(WRONG_MODEL_ID),
                        user(WRONG_VERSION_ID),
                        *(
                            projection(user_id, second_dimension)
                            for user_id, second_dimension in zip(
                                ACTIVE_IDS, active_dimensions, strict=True
                            )
                        ),
                        projection(INACTIVE_ID, 0.001),
                        projection(BLOCKED_ID, 0.002),
                        projection(PRIOR_MATCH_ID, 0.003),
                        projection(
                            WRONG_MODEL_ID,
                            0.0001,
                            model_name="other-embedding-model",
                        ),
                        projection(
                            WRONG_VERSION_ID,
                            0.0002,
                            model_version="solar-embedding-2-v2",
                        ),
                        UserBlock(blocker_id=SENDER_ID, blocked_id=BLOCKED_ID),
                        MatchHistory.create(
                            SENDER_ID,
                            PRIOR_MATCH_ID,
                            MatchStrategy.PROFILE,
                        ),
                    ]
                )
                await session.commit()

            async with session_factory() as session:
                candidates = await MatchingRepository(session).search_semantic_candidates(
                    SENDER_ID,
                    EmbeddingVector(values=vector(0.0)),
                    model_name=MODEL_NAME,
                    model_version=MODEL_VERSION,
                )
        finally:
            await engine.dispose()
            get_settings.cache_clear()

    candidate_ids = [candidate.user_id for candidate in candidates]
    similarities = [candidate.similarity for candidate in candidates]

    assert len(candidates) == 20
    assert candidate_ids[0] == ACTIVE_IDS[0]
    assert similarities == sorted(similarities, reverse=True)
    assert candidate_ids.index(ACTIVE_IDS[10]) < candidate_ids.index(ACTIVE_IDS[19])
    assert not ({SENDER_ID, INACTIVE_ID, BLOCKED_ID, PRIOR_MATCH_ID} & set(candidate_ids))
    assert WRONG_MODEL_ID not in candidate_ids
    assert WRONG_VERSION_ID not in candidate_ids


def test_pgvector_search_orders_eligible_active_model_candidates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    loop = asyncio.SelectorEventLoop()
    try:
        loop.run_until_complete(
            _assert_pgvector_search_orders_eligible_active_model_candidates(monkeypatch)
        )
    finally:
        loop.close()
