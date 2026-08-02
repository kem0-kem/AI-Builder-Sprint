import logging

import httpx
from pydantic import BaseModel, ConfigDict, Field

from app.core.config import Settings, matching_configuration_complete
from app.matching.gateway import (
    EmbeddingDimensionMismatch,
    EmbeddingGateway,
    EmbeddingProviderUnavailable,
)
from app.matching.upstage_gateway import UpstageEmbeddingGateway

EMBEDDING_READINESS_PROBE = "SlowTalk 임베딩 준비 상태 확인"


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
