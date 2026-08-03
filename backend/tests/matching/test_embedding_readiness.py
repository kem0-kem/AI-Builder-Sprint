import logging

import pytest
from httpx import ASGITransport, AsyncClient
from pydantic import SecretStr

from app import main
from app.core.config import Settings, matching_configuration_complete
from app.matching.dependencies import EmbeddingReadiness, probe_embedding_readiness
from app.matching.gateway import (
    EmbeddingDimensionMismatch,
    EmbeddingProviderUnavailable,
    EmbeddingVector,
)


class ReadyGateway:
    def __init__(self) -> None:
        self.queries: list[str] = []

    async def embed_query(self, text: str) -> EmbeddingVector:
        self.queries.append(text)
        return EmbeddingVector(values=[0.1] * 1024)

    async def embed_passages(self, texts: list[str]) -> list[EmbeddingVector]:
        raise AssertionError("readiness must not embed passages")


class FailingGateway(ReadyGateway):
    def __init__(self, error: Exception) -> None:
        super().__init__()
        self.error = error

    async def embed_query(self, text: str) -> EmbeddingVector:
        self.queries.append(text)
        raise self.error


class WrongDimensionGateway(ReadyGateway):
    async def embed_query(self, text: str) -> EmbeddingVector:
        self.queries.append(text)
        return EmbeddingVector(values=[0.1, 0.2, 0.3])


def matching_settings(**overrides: object) -> Settings:
    settings = Settings(
        _env_file=None,
        matching_mode="shadow",
        upstage_api_key=SecretStr("provider-key"),
        upstage_embedding_model="solar-embedding-2",
        match_min_similarity=0.7,
    )
    for field, value in overrides.items():
        setattr(settings, field, value)
    return settings


def test_matching_configuration_requires_provider_model_and_threshold() -> None:
    assert matching_configuration_complete(Settings(_env_file=None)) is True
    assert matching_configuration_complete(matching_settings()) is True
    assert matching_configuration_complete(matching_settings(upstage_api_key=None)) is False
    assert matching_configuration_complete(matching_settings(upstage_embedding_model=" ")) is False
    assert matching_configuration_complete(matching_settings(match_min_similarity=None)) is False


@pytest.mark.asyncio
async def test_disabled_matching_skips_provider_probe() -> None:
    gateway = FailingGateway(AssertionError("must not be called"))

    readiness = await probe_embedding_readiness(Settings(_env_file=None), gateway)

    assert readiness.ready is True
    assert readiness.model is None
    assert readiness.expected_dimensions == 1024
    assert gateway.queries == []


@pytest.mark.asyncio
async def test_incomplete_matching_skips_provider_probe_and_is_not_ready() -> None:
    gateway = FailingGateway(AssertionError("must not be called"))

    readiness = await probe_embedding_readiness(
        matching_settings(upstage_api_key=None), gateway
    )

    assert readiness.ready is False
    assert gateway.queries == []


@pytest.mark.asyncio
async def test_configured_matching_probes_query_once_and_discards_vector() -> None:
    gateway = ReadyGateway()

    readiness = await probe_embedding_readiness(matching_settings(), gateway)

    assert readiness.ready is True
    assert readiness.model == "solar-embedding-2"
    assert readiness.expected_dimensions == 1024
    assert readiness.model_dump() == {
        "mode": "shadow",
        "model": "solar-embedding-2",
        "expectedDimensions": 1024,
        "ready": True,
    }
    assert len(gateway.queries) == 1


@pytest.mark.asyncio
async def test_readiness_independently_checks_gateway_vector_dimension() -> None:
    readiness = await probe_embedding_readiness(
        matching_settings(), WrongDimensionGateway()
    )

    assert readiness.ready is False


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "error",
    [
        EmbeddingDimensionMismatch(expected=1024, actual=3),
        EmbeddingProviderUnavailable("embedding provider unavailable"),
    ],
)
async def test_known_probe_failure_marks_not_ready_without_leaking_probe(
    caplog: pytest.LogCaptureFixture,
    error: Exception,
) -> None:
    caplog.set_level(logging.WARNING)
    gateway = FailingGateway(error)

    readiness = await probe_embedding_readiness(matching_settings(), gateway)

    assert readiness.ready is False
    assert len(gateway.queries) == 1
    assert gateway.queries[0] not in caplog.text
    assert "0.1" not in caplog.text
    assert "solar-embedding-2" in caplog.text


@pytest.mark.asyncio
async def test_unexpected_probe_error_is_not_hidden() -> None:
    gateway = FailingGateway(TypeError("programming bug"))

    with pytest.raises(TypeError, match="programming bug"):
        await probe_embedding_readiness(matching_settings(), gateway)


@pytest.mark.asyncio
async def test_app_lifespan_probes_once_and_exposes_redacted_readiness(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = matching_settings(
        app_environment="test",
        allow_development_moderation_fallback=True,
    )
    readiness = EmbeddingReadiness(
        mode="shadow",
        model="solar-embedding-2",
        expected_dimensions=1024,
        ready=True,
    )
    calls = 0

    async def fake_check(actual_settings: Settings) -> EmbeddingReadiness:
        nonlocal calls
        calls += 1
        assert actual_settings is settings
        return readiness

    async def fake_database_ready() -> bool:
        return True

    monkeypatch.setattr(main, "get_settings", lambda: settings)
    monkeypatch.setattr(main, "check_embedding_readiness", fake_check)
    monkeypatch.setattr(main, "database_is_ready", fake_database_ready)
    application = main.create_app()

    async with application.router.lifespan_context(application):
        async with AsyncClient(
            transport=ASGITransport(app=application), base_url="http://test"
        ) as client:
            response = await client.get("/api/v1/ready")

    assert response.status_code == 200
    assert calls == 1
    assert response.json()["data"]["matching"] == {
        "mode": "shadow",
        "model": "solar-embedding-2",
        "expectedDimensions": 1024,
        "ready": True,
    }
    assert "values" not in response.text


@pytest.mark.asyncio
async def test_failed_startup_probe_makes_readiness_unavailable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = matching_settings(
        app_environment="test",
        allow_development_moderation_fallback=True,
    )

    async def fake_check(_: Settings) -> EmbeddingReadiness:
        return EmbeddingReadiness(
            mode="shadow",
            model="solar-embedding-2",
            expected_dimensions=1024,
            ready=False,
        )

    async def fake_database_ready() -> bool:
        return True

    monkeypatch.setattr(main, "get_settings", lambda: settings)
    monkeypatch.setattr(main, "check_embedding_readiness", fake_check)
    monkeypatch.setattr(main, "database_is_ready", fake_database_ready)
    application = main.create_app()

    async with application.router.lifespan_context(application):
        async with AsyncClient(
            transport=ASGITransport(app=application), base_url="http://test"
        ) as client:
            response = await client.get("/api/v1/ready")

    assert response.status_code == 503
    assert response.json()["data"]["status"] == "not_ready"
    assert response.json()["data"]["matching"]["ready"] is False
