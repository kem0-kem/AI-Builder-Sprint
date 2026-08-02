"""Semantic candidate selection with a bounded profile fallback."""

from __future__ import annotations

from collections.abc import Collection
from typing import Protocol
from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession

from app.matching.gateway import (
    EmbeddingDimensionMismatch,
    EmbeddingGateway,
    EmbeddingProviderUnavailable,
)
from app.matching.models import MatchStrategy
from app.matching.profile_policy import MatchCandidate, ProfileMatchingPolicy
from app.matching.repository import MatchingRepository

INSUFFICIENT_EMBEDDINGS = "INSUFFICIENT_EMBEDDINGS"
PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"


class SemanticSelectionObserver(Protocol):
    """Receives only fixed, non-identifying semantic-selection events."""

    def provider_failure(self) -> None: ...

    def profile_fallback(self, reason: str) -> None: ...

    def threshold_pass(self) -> None: ...

    def threshold_below_or_missing(self) -> None: ...


class NoopSemanticSelectionObserver:
    def provider_failure(self) -> None:
        return None

    def profile_fallback(self, reason: str) -> None:
        return None

    def threshold_pass(self) -> None:
        return None

    def threshold_below_or_missing(self) -> None:
        return None


class SemanticMatchingPolicy:
    def __init__(
        self,
        gateway: EmbeddingGateway,
        repository: MatchingRepository,
        profile_policy: ProfileMatchingPolicy,
        *,
        active_model_name: str,
        active_model_version: str,
        minimum_similarity: float,
        observer: SemanticSelectionObserver | None = None,
    ) -> None:
        if not active_model_name.strip() or not active_model_version.strip():
            raise ValueError("active embedding model name and version are required")
        if not 0 <= minimum_similarity <= 1:
            raise ValueError("minimum similarity must be between 0 and 1")
        self._gateway = gateway
        self._repository = repository
        self._profile = profile_policy
        self._active_model_name = active_model_name
        self._active_model_version = active_model_version
        self._minimum_similarity = minimum_similarity
        self._observer = observer or NoopSemanticSelectionObserver()

    async def select(
        self,
        session: AsyncSession,
        sender_id: UUID,
        *,
        content: str,
        excluded_ids: Collection[UUID] = (),
    ) -> MatchCandidate | None:
        try:
            query_vector = await self._gateway.embed_query(content)
        except (EmbeddingProviderUnavailable, EmbeddingDimensionMismatch):
            self._observer.provider_failure()
            return await self._fallback(
                session, sender_id, PROVIDER_UNAVAILABLE, excluded_ids
            )

        candidates = await self._repository.search_semantic_candidates(
            sender_id,
            query_vector,
            model_name=self._active_model_name,
            model_version=self._active_model_version,
            limit=20,
        )
        excluded = set(excluded_ids)
        best = next(
            (candidate for candidate in candidates if candidate.user_id not in excluded), None
        )
        if best is not None and best.similarity >= self._minimum_similarity:
            self._observer.threshold_pass()
            return MatchCandidate(
                user_id=best.user_id,
                strategy=MatchStrategy.SEMANTIC,
                score=best.similarity,
                model_name=self._active_model_name,
                model_version=self._active_model_version,
            )
        self._observer.threshold_below_or_missing()
        return await self._fallback(session, sender_id, INSUFFICIENT_EMBEDDINGS, excluded)

    async def _fallback(
        self,
        session: AsyncSession,
        sender_id: UUID,
        reason: str,
        excluded_ids: Collection[UUID],
    ) -> MatchCandidate | None:
        self._observer.profile_fallback(reason)
        return await self._profile.select(
            session,
            sender_id,
            strategy=MatchStrategy.PROFILE_FALLBACK,
            fallback_reason=reason,
            excluded_ids=excluded_ids,
        )
