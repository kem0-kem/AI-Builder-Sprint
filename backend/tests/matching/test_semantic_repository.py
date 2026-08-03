from unittest.mock import AsyncMock, Mock
from uuid import UUID

from sqlalchemy.dialects import postgresql
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.models import User
from app.matching.gateway import EmbeddingVector
from app.matching.repository import MatchingRepository, SemanticCandidate

SENDER_ID = UUID("00000000-0000-0000-0000-000000000001")
CANDIDATE_ID = UUID("00000000-0000-0000-0000-000000000002")


async def test_semantic_search_uses_cosine_model_filter_and_top_twenty() -> None:
    session = AsyncMock(spec=AsyncSession)
    result = Mock()
    result.all.return_value = []
    session.execute.return_value = result

    candidates = await MatchingRepository(session).search_semantic_candidates(
        SENDER_ID,
        EmbeddingVector(values=[1.0] * 1024),
        model_name="solar-embedding-2",
        model_version="solar-embedding-2-v1",
        limit=100,
    )
    statement = session.execute.await_args.args[0]
    sql = str(
        statement.compile(
            dialect=postgresql.dialect(),
            compile_kwargs={"literal_binds": True},
        )
    )

    assert candidates == []
    assert "user_match_vectors.model_name = 'solar-embedding-2'" in sql
    assert "user_match_vectors.model_version = 'solar-embedding-2-v1'" in sql
    assert "<=>" in sql
    assert "LIMIT 20" in sql
    assert "NOT (EXISTS" in sql
    assert "ORDER BY" in sql


async def test_semantic_search_maps_rows_without_exposing_vectors() -> None:
    session = AsyncMock(spec=AsyncSession)
    result = Mock()
    candidate = User(
        id=CANDIDATE_ID,
        email="candidate@example.com",
        password_hash="hash",
        nickname="candidate",
    )
    result.all.return_value = [(candidate, 0.91)]
    session.execute.return_value = result

    candidates = await MatchingRepository(session).search_semantic_candidates(
        SENDER_ID,
        EmbeddingVector(values=[0.5] * 1024),
        model_name="solar-embedding-2",
        model_version="solar-embedding-2-v1",
    )

    assert candidates == [SemanticCandidate(user_id=CANDIDATE_ID, similarity=0.91)]
    assert not hasattr(candidates[0], "embedding")


async def test_semantic_search_clamps_non_positive_limit_to_one() -> None:
    session = AsyncMock(spec=AsyncSession)
    result = Mock()
    result.all.return_value = []
    session.execute.return_value = result

    await MatchingRepository(session).search_semantic_candidates(
        SENDER_ID,
        EmbeddingVector(values=[1.0] * 1024),
        model_name="solar-embedding-2",
        model_version="solar-embedding-2-v1",
        limit=0,
    )
    statement = session.execute.await_args.args[0]
    sql = str(
        statement.compile(
            dialect=postgresql.dialect(),
            compile_kwargs={"literal_binds": True},
        )
    )

    assert "LIMIT 1" in sql
