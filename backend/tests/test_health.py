import base64
from pathlib import Path

import pytest
from httpx import ASGITransport, AsyncClient
from pydantic import SecretStr

from app import main
from app.core.config import Settings, moderation_configuration_complete

VALID_ENFORCE_KEY = base64.b64encode(b"k" * 32).decode("ascii")
INVALID_ENFORCE_KEYS = (
    "not-base64!",
    VALID_ENFORCE_KEY[:-2] + "t=",
    base64.b64encode(b"short").decode("ascii"),
)


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


def complete_enforce_settings() -> Settings:
    return Settings(
        _env_file=None,
        upstage_api_key=SecretStr("provider-key"),
        upstage_chat_model="moderation-model",
        moderation_mode="enforce",
        moderation_allow_confidence=0.7,
        moderation_block_confidence=0.9,
        moderation_encryption_key=SecretStr(VALID_ENFORCE_KEY),
        content_hash_pepper=SecretStr("content-hash-pepper"),
        internal_moderation_token=SecretStr("t" * 32),
    )


def test_env_example_loads_as_ai_optional_local_configuration() -> None:
    env_example = Path(__file__).resolve().parents[1] / ".env.example"

    settings = Settings(_env_file=env_example)

    assert settings.app_environment == "development"
    assert settings.database_url == (
        "postgresql+asyncpg://slowtalk:slowtalk@localhost:5432/slowtalk"
    )
    assert settings.api_prefix == "/api/v1"
    assert settings.jwt_secret == "local-development-secret-change-before-deploy"
    assert settings.matching_mode == "disabled"
    assert settings.moderation_mode == "disabled"
    assert moderation_configuration_complete(settings) is True


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


async def test_default_development_without_external_ai_is_ready(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(
        _env_file=None,
        app_environment="development",
        matching_mode="disabled",
        moderation_mode="disabled",
        upstage_api_key=None,
        upstage_chat_model=None,
        upstage_embedding_model=None,
    )

    status_code, body = await system_response(monkeypatch, settings, "/api/v1/ready")

    assert status_code == 200
    assert body == {
        "ok": True,
        "data": {
            "status": "ready",
            "moderationMode": "disabled",
            "moderationConfigured": True,
            "fallbackActive": False,
        },
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
        "fallbackActive": False,
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
        "fallbackActive": False,
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


@pytest.mark.parametrize("invalid_key", INVALID_ENFORCE_KEYS)
async def test_invalid_enforce_encryption_key_is_not_ready(
    monkeypatch: pytest.MonkeyPatch,
    invalid_key: str,
) -> None:
    settings = complete_enforce_settings()
    settings.moderation_encryption_key = SecretStr(invalid_key)

    status_code, body = await system_response(monkeypatch, settings, "/api/v1/ready")

    assert moderation_configuration_complete(settings) is False
    assert status_code == 503
    assert body["data"] == {
        "status": "not_ready",
        "moderationMode": "enforce",
        "moderationConfigured": False,
        "fallbackActive": False,
    }
