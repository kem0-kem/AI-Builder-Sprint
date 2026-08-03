import logging
from typing import Annotated

import httpx
from fastapi import Depends, Request
from pydantic import BaseModel, ConfigDict, Field

from app.auth.dependencies import Session
from app.core.config import Settings, get_settings, matching_configuration_complete
from app.core.errors import ApiError
from app.matching.gateway import (
    EmbeddingDimensionMismatch,
    EmbeddingGateway,
    EmbeddingProviderUnavailable,
)
from app.matching.metrics import FallbackReason, MatchingMetrics, SemanticThresholdOutcome
from app.matching.profile_policy import ProfileMatchingPolicy
from app.matching.repository import MatchingRepository
from app.matching.semantic_policy import SemanticMatchingPolicy
from app.matching.service import MatchingService
from app.matching.upstage_gateway import UpstageEmbeddingGateway

EMBEDDING_READINESS_PROBE = "SlowTalk 임베딩 준비 상태 확인"


class MetricsSemanticObserver:
    """Adapts policy events to counters with a fixed label set."""

    def __init__(self, metrics: MatchingMetrics) -> None:
        self._metrics = metrics

    def provider_failure(self) -> None:
        return None

    def profile_fallback(self, reason: str) -> None:
        self._metrics.record_fallback(FallbackReason(reason))

    def threshold_pass(self) -> None:
        self._metrics.record_semantic_threshold(SemanticThresholdOutcome.PASS)

    def threshold_below_or_missing(self) -> None:
        self._metrics.record_semantic_threshold(SemanticThresholdOutcome.NO_VECTOR)


class EmbeddingReadiness(BaseModel):
    model_config = ConfigDict(populate_by_name=True, serialize_by_alias=True)

    mode: str
    model: str | None
    expected_dimensions: int = Field(alias="expectedDimensions")
    ready: bool


async def probe_embedding_readiness(
    settings: Settings,
    gateway: EmbeddingGateway,
) -> EmbeddingReadiness:
    if settings.matching_mode == "disabled":
        return _readiness(settings, ready=True)
    if not matching_configuration_complete(settings):
        return _readiness(settings, ready=False)

    try:
        vector = await gateway.embed_query(EMBEDDING_READINESS_PROBE)
        if len(vector.values) != settings.embedding_dimensions:
            raise EmbeddingDimensionMismatch(
                expected=settings.embedding_dimensions,
                actual=len(vector.values),
            )
    except (EmbeddingDimensionMismatch, EmbeddingProviderUnavailable):
        logging.getLogger("slowtalk.matching").warning(
            "embedding_readiness_failed model=%s expected_dimensions=%s",
            settings.upstage_embedding_model,
            settings.embedding_dimensions,
        )
        return _readiness(settings, ready=False)
    return _readiness(settings, ready=True)


async def check_embedding_readiness(settings: Settings) -> EmbeddingReadiness:
    if settings.matching_mode == "disabled" or not matching_configuration_complete(settings):
        return _readiness(
            settings,
            ready=settings.matching_mode == "disabled",
        )

    assert settings.upstage_api_key is not None
    assert settings.upstage_embedding_model is not None
    async with httpx.AsyncClient(
        base_url=str(settings.upstage_base_url),
        timeout=10.0,
    ) as client:
        gateway = UpstageEmbeddingGateway(
            client=client,
            api_key=settings.upstage_api_key,
            model=settings.upstage_embedding_model,
            expected_dimensions=settings.embedding_dimensions,
        )
        return await probe_embedding_readiness(settings, gateway)


def _readiness(settings: Settings, *, ready: bool) -> EmbeddingReadiness:
    return EmbeddingReadiness(
        mode=settings.matching_mode,
        model=settings.upstage_embedding_model,
        expected_dimensions=settings.embedding_dimensions,
        ready=ready,
    )


async def get_matching_service(
    request: Request,
    session: Session,
) -> MatchingService:
    """Assemble one request-scoped matching service for every delivery path."""
    settings = get_settings()
    repository = MatchingRepository(session)
    profile = ProfileMatchingPolicy()
    metrics = getattr(request.app.state, "matching_metrics", None)
    semantic = None
    if settings.matching_mode != "disabled":
        gateway = getattr(request.app.state, "embedding_gateway", None)
        if gateway is None or not matching_configuration_complete(settings):
            raise ApiError(
                "EMBEDDING_SERVICE_UNAVAILABLE", "Semantic matching is unavailable.", 503
            )
        assert settings.upstage_embedding_model is not None
        assert settings.match_min_similarity is not None
        semantic = SemanticMatchingPolicy(
            gateway,
            repository,
            profile,
            active_model_name=settings.upstage_embedding_model,
            active_model_version=settings.upstage_embedding_model,
            minimum_similarity=settings.match_min_similarity,
            observer=MetricsSemanticObserver(metrics) if metrics is not None else None,
        )
    return MatchingService(
        repository,
        profile,
        mode=settings.matching_mode,
        semantic=semantic,  # type: ignore[arg-type]
        metrics=metrics,
    )


Matching = Annotated[MatchingService, Depends(get_matching_service)]
