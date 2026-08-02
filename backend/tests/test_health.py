import pytest
from httpx import ASGITransport, AsyncClient
from pydantic import SecretStr

from app import main
from app.core.config import Settings


async def system_response(
    monkeypatch: pytest.MonkeyPatch, settings: Settings, path: str
) -> tuple[int, dict[str, object]]:
    monkeypatch.setattr(main, "get_settings", lambda: settings)
    async with AsyncClient(
        transport=ASGITransport(app=main.create_app()), base_url="http://test"
    ) as client:
        response = await client.get(path)

    body = response.json()
    assert isinstance(body, dict)
    return response.status_code, body


def incomplete_settings(**overrides: object) -> Settings:
    settings = Settings(_env_file=None)
    for field, value in overrides.items():
        setattr(settings, field, value)
    return settings


def complete_shadow_settings(**overrides: object) -> Settings:
    settings = Settings(
        _env_file=None,
        upstage_api_key=SecretStr("shadow-provider-key"),
        upstage_chat_model="shadow-model",
        moderation_mode="shadow",
        moderation_allow_confidence=0.7,
        moderation_block_confidence=0.9,
    )
    for field, value in overrides.items():
        setattr(settings, field, value)
    return settings


async def test_health_uses_common_envelope(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    status_code, body = await system_response(
        monkeypatch, incomplete_settings(), "/api/v1/health"
    )

    assert status_code == 200
    assert body == {
        "ok": True,
        "data": {"status": "alive"},
        "error": None,
        "meta": None,
    }


async def test_default_incomplete_development_is_not_ready(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    status_code, body = await system_response(
        monkeypatch, incomplete_settings(), "/api/v1/ready"
    )

    assert status_code == 503
    assert body["data"] == {
        "status": "not_ready",
        "moderationMode": "shadow",
        "moderationConfigured": False,
        "fallbackActive": True,
    }


@pytest.mark.parametrize("app_environment", ("development", "test"))
async def test_incomplete_nonproduction_opt_in_allows_fallback(
    monkeypatch: pytest.MonkeyPatch,
    app_environment: str,
) -> None:
    settings = incomplete_settings(
        app_environment=app_environment,
        allow_development_moderation_fallback=True,
    )

    status_code, body = await system_response(monkeypatch, settings, "/api/v1/ready")

    assert status_code == 200
    assert body["data"] == {
        "status": "ready",
        "moderationMode": "shadow",
        "moderationConfigured": False,
        "fallbackActive": True,
    }


async def test_incomplete_production_cannot_opt_in_to_fallback(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = incomplete_settings(
        app_environment="production",
        allow_development_moderation_fallback=True,
    )

    status_code, body = await system_response(monkeypatch, settings, "/api/v1/ready")

    assert status_code == 503
    assert body["data"] == {
        "status": "not_ready",
        "moderationMode": "shadow",
        "moderationConfigured": False,
        "fallbackActive": True,
    }


async def test_complete_shadow_is_ready_without_crypto(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = complete_shadow_settings(
        moderation_encryption_key=None,
        content_hash_pepper=None,
        internal_moderation_token=None,
    )

    status_code, body = await system_response(monkeypatch, settings, "/api/v1/ready")

    assert status_code == 200
    assert body["data"] == {
        "status": "ready",
        "moderationMode": "shadow",
        "moderationConfigured": True,
        "fallbackActive": False,
    }
