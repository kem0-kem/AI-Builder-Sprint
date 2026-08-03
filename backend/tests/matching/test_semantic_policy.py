from unittest.mock import AsyncMock
from uuid import UUID

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.matching.gateway import (
    EmbeddingDimensionMismatch,
    EmbeddingProviderUnavailable,
    EmbeddingVector,
)
from app.matching.models import MatchStrategy
from app.matching.profile_policy import MatchCandidate
from app.matching.repository import SemanticCandidate
from app.matching.semantic_policy import SemanticMatchingPolicy

SENDER_ID = UUID(int=1)
CANDIDATE_ID = UUID(int=2)
FALLBACK_ID = UUID(int=3)


def policy(
    gateway: AsyncMock, repository: AsyncMock, profile: AsyncMock, minimum: float = 0.7
) -> SemanticMatchingPolicy:
    return SemanticMatchingPolicy(
        gateway,
        repository,
        profile,
        active_model_name="solar-embedding-2",
        active_model_version="solar-embedding-2",
        minimum_similarity=minimum,
    )


async def test_semantic_policy_accepts_threshold_equality() -> None:
    gateway = AsyncMock()
    gateway.embed_query.return_value = EmbeddingVector(values=[1.0] * 1024)
    repository = AsyncMock()
    repository.search_semantic_candidates.return_value = [
        SemanticCandidate(CANDIDATE_ID, 0.7)
    ]
    profile = AsyncMock()

    result = await policy(gateway, repository, profile).select(
        AsyncMock(spec=AsyncSession), SENDER_ID, content="private letter"
    )

    assert result == MatchCandidate(
        user_id=CANDIDATE_ID,
        strategy=MatchStrategy.SEMANTIC,
        score=0.7,
        model_name="solar-embedding-2",
        model_version="solar-embedding-2",
    )
    profile.select.assert_not_awaited()


@pytest.mark.parametrize(
    "failure",
    [
        EmbeddingProviderUnavailable("unavailable"),
        EmbeddingDimensionMismatch(expected=1024, actual=3),
    ],
)
async def test_known_embedding_failures_use_redacted_profile_fallback(
    failure: Exception,
) -> None:
    session = AsyncMock(spec=AsyncSession)
    gateway = AsyncMock()
    gateway.embed_query.side_effect = failure
    repository = AsyncMock()
    profile = AsyncMock()
    profile.select.return_value = MatchCandidate(
        FALLBACK_ID,
        MatchStrategy.PROFILE_FALLBACK,
        0.2,
        "PROVIDER_UNAVAILABLE",
    )

    result = await policy(gateway, repository, profile).select(
        session, SENDER_ID, content="never logged"
    )

    assert result is not None and result.fallback_reason == "PROVIDER_UNAVAILABLE"
    profile.select.assert_awaited_once_with(
        session,
        SENDER_ID,
        strategy=MatchStrategy.PROFILE_FALLBACK,
        fallback_reason="PROVIDER_UNAVAILABLE",
        excluded_ids=(),
    )


async def test_below_threshold_and_lost_race_exclusion_fall_back() -> None:
    session = AsyncMock(spec=AsyncSession)
    gateway = AsyncMock()
    gateway.embed_query.return_value = EmbeddingVector(values=[1.0] * 1024)
    repository = AsyncMock()
    repository.search_semantic_candidates.return_value = [
        SemanticCandidate(CANDIDATE_ID, 0.99)
    ]
    profile = AsyncMock()
    profile.select.return_value = MatchCandidate(
        FALLBACK_ID,
        MatchStrategy.PROFILE_FALLBACK,
        0.2,
        "INSUFFICIENT_EMBEDDINGS",
    )

    result = await policy(gateway, repository, profile).select(
        session, SENDER_ID, content="letter", excluded_ids={CANDIDATE_ID}
    )

    assert result is not None and result.user_id == FALLBACK_ID
    profile.select.assert_awaited_once_with(
        session,
        SENDER_ID,
        strategy=MatchStrategy.PROFILE_FALLBACK,
        fallback_reason="INSUFFICIENT_EMBEDDINGS",
        excluded_ids={CANDIDATE_ID},
    )
