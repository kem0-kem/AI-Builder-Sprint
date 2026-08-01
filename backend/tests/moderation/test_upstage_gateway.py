import json

import httpx
import pytest
import respx
from pydantic import SecretStr, ValidationError

from app.core.config import Settings
from app.moderation.gateway import ModerationProviderUnavailable
from app.moderation.schemas import ContentType, ModerationCategory, ModerationDecision, Severity
from app.moderation.upstage_gateway import UpstageModerationGateway


def build_gateway(client: httpx.AsyncClient) -> UpstageModerationGateway:
    return UpstageModerationGateway(
        client=client,
        api_key=SecretStr("test-api-key"),
        model="solar-test",
    )


def upstage_response(content: object, *, request_id: str = "request-123") -> httpx.Response:
    return httpx.Response(
        200,
        json={"choices": [{"message": {"content": content}}]},
        headers={"x-request-id": request_id},
    )


@pytest.mark.asyncio
@respx.mock
async def test_gateway_sends_only_type_and_text() -> None:
    route = respx.post("https://api.upstage.ai/v1/chat/completions").mock(
        return_value=upstage_response(
            json.dumps(
                {
                    "decision": "ALLOW",
                    "categories": [],
                    "severity": "NONE",
                    "confidence": 0.97,
                    "reason": "safe",
                }
            )
        )
    )
    async with httpx.AsyncClient(base_url="https://api.upstage.ai/v1") as client:
        assessment = await build_gateway(client).classify(ContentType.LETTER, "오늘 산책했어요")

    sent = json.loads(route.calls[0].request.content)
    user_payload = json.loads(sent["messages"][1]["content"])
    serialized = json.dumps(sent, ensure_ascii=False)
    assert assessment.decision is ModerationDecision.ALLOW
    assert assessment.provider_request_id == "request-123"
    assert user_payload == {"type": "LETTER", "text": "오늘 산책했어요"}
    assert route.calls[0].request.headers["Authorization"] == "Bearer test-api-key"
    assert "user@example.com" not in serialized
    assert "오늘 산책했어요" in serialized


@pytest.mark.asyncio
@respx.mock
async def test_gateway_parses_all_supported_categories() -> None:
    respx.post("https://api.upstage.ai/v1/chat/completions").mock(
        return_value=upstage_response(
            json.dumps(
                {
                    "decision": "BLOCK",
                    "categories": ["HARASSMENT", "PERSONAL_DATA"],
                    "severity": "HIGH",
                    "confidence": 0.98,
                    "reason": "policy violation",
                }
            )
        )
    )
    async with httpx.AsyncClient(base_url="https://api.upstage.ai/v1") as client:
        assessment = await build_gateway(client).classify(ContentType.CHAT_MESSAGE, "검사 대상")

    assert assessment.categories == {
        ModerationCategory.HARASSMENT,
        ModerationCategory.PERSONAL_DATA,
    }
    assert assessment.severity is Severity.HIGH


@pytest.mark.asyncio
@respx.mock
@pytest.mark.parametrize(
    "content",
    [
        "not-json",
        json.dumps(
            {
                "decision": "UNKNOWN",
                "categories": [],
                "severity": "NONE",
                "confidence": 0.5,
                "reason": "invalid",
            }
        ),
        json.dumps(
            {
                "decision": "ALLOW",
                "categories": [],
                "severity": "NONE",
                "confidence": True,
                "reason": "invalid",
            }
        ),
        json.dumps(
            {
                "decision": "ALLOW",
                "categories": [],
                "severity": "NONE",
                "confidence": "0.97",
                "reason": "invalid",
            }
        ),
        json.dumps(
            {
                "decision": "ALLOW",
                "categories": [],
                "severity": "NONE",
                "confidence": 1.5,
                "reason": "invalid",
            }
        ),
    ],
)
async def test_gateway_maps_malformed_model_output_to_provider_unavailable(content: str) -> None:
    respx.post("https://api.upstage.ai/v1/chat/completions").mock(
        return_value=upstage_response(content)
    )
    async with httpx.AsyncClient(base_url="https://api.upstage.ai/v1") as client:
        with pytest.raises(ModerationProviderUnavailable) as caught:
            await build_gateway(client).classify(ContentType.FEED, "private-input-marker")

    assert "private-input-marker" not in str(caught.value)


@pytest.mark.asyncio
@respx.mock
async def test_gateway_maps_timeout_without_exposing_content() -> None:
    respx.post("https://api.upstage.ai/v1/chat/completions").mock(
        side_effect=httpx.ReadTimeout("private-input-marker")
    )
    async with httpx.AsyncClient(base_url="https://api.upstage.ai/v1") as client:
        with pytest.raises(ModerationProviderUnavailable) as caught:
            await build_gateway(client).classify(ContentType.REPORT, "private-input-marker")

    assert str(caught.value) == "moderation provider unavailable"
    assert caught.value.__cause__ is None


@pytest.mark.asyncio
@respx.mock
async def test_gateway_maps_non_success_response_to_provider_unavailable() -> None:
    respx.post("https://api.upstage.ai/v1/chat/completions").mock(
        return_value=httpx.Response(503, text="private-input-marker")
    )
    async with httpx.AsyncClient(base_url="https://api.upstage.ai/v1") as client:
        with pytest.raises(ModerationProviderUnavailable) as caught:
            await build_gateway(client).classify(ContentType.OCR_TEXT, "private-input-marker")

    assert "private-input-marker" not in str(caught.value)


@pytest.mark.asyncio
@respx.mock
async def test_gateway_rejects_unexpected_assessment_fields() -> None:
    respx.post("https://api.upstage.ai/v1/chat/completions").mock(
        return_value=upstage_response(
            json.dumps(
                {
                    "decision": "ALLOW",
                    "categories": [],
                    "severity": "NONE",
                    "confidence": 0.97,
                    "reason": "safe",
                    "provider_request_id": "model-controlled-id",
                }
            )
        )
    )
    async with httpx.AsyncClient(base_url="https://api.upstage.ai/v1") as client:
        with pytest.raises(ModerationProviderUnavailable):
            await build_gateway(client).classify(ContentType.COMMENT, "검사 대상")


@pytest.mark.asyncio
@respx.mock
@pytest.mark.parametrize(
    "envelope",
    [
        {},
        {"choices": []},
        {"choices": [{}]},
        {"choices": [{"message": {}}]},
        {"choices": [{"message": {"content": None}}]},
        {"choices": [{"message": {"content": {"decision": "ALLOW"}}}]},
    ],
)
async def test_gateway_maps_malformed_provider_envelope_to_unavailable(
    envelope: dict[str, object],
) -> None:
    respx.post("https://api.upstage.ai/v1/chat/completions").mock(
        return_value=httpx.Response(200, json=envelope)
    )
    async with httpx.AsyncClient(base_url="https://api.upstage.ai/v1") as client:
        with pytest.raises(ModerationProviderUnavailable):
            await build_gateway(client).classify(ContentType.FEED, "검사 대상")


def test_enforce_mode_requires_provider_and_encryption_secrets() -> None:
    with pytest.raises(ValidationError, match="enforce moderation requires"):
        Settings(_env_file=None, moderation_mode="enforce")


def test_enforce_mode_accepts_complete_moderation_configuration() -> None:
    settings = Settings(
        _env_file=None,
        moderation_mode="enforce",
        upstage_api_key="test-api-key",
        upstage_chat_model="configured-solar-model",
        moderation_encryption_key="test-encryption-key",
        content_hash_pepper="test-content-pepper",
        internal_moderation_token="test-internal-token",
        moderation_allow_confidence=0.80,
        moderation_block_confidence=0.90,
    )

    assert settings.moderation_mode == "enforce"
    assert settings.upstage_api_key is not None
    assert settings.upstage_api_key.get_secret_value() == "test-api-key"


def test_moderation_confidence_thresholds_must_be_ordered() -> None:
    with pytest.raises(ValidationError, match="allow confidence must be lower"):
        Settings(
            _env_file=None,
            moderation_allow_confidence=0.95,
            moderation_block_confidence=0.90,
        )


def test_moderation_secrets_are_redacted_from_repr_and_json_serialization() -> None:
    secrets = {
        "upstage_api_key": "visible-api-key-marker",
        "moderation_encryption_key": "visible-encryption-key-marker",
        "content_hash_pepper": "visible-pepper-marker",
        "internal_moderation_token": "visible-internal-token-marker",
    }
    settings = Settings(_env_file=None, **secrets)

    rendered = repr(settings) + settings.model_dump_json()
    assert all(secret not in rendered for secret in secrets.values())
    assert all(isinstance(getattr(settings, field), SecretStr) for field in secrets)
    assert settings.internal_moderation_token is not None
    assert settings.internal_moderation_token.get_secret_value() == secrets[
        "internal_moderation_token"
    ]


@pytest.mark.parametrize(
    "field",
    [
        "upstage_api_key",
        "upstage_chat_model",
        "moderation_encryption_key",
        "content_hash_pepper",
        "internal_moderation_token",
    ],
)
def test_enforce_mode_rejects_whitespace_only_required_values(field: str) -> None:
    configuration: dict[str, object] = {
        "moderation_mode": "enforce",
        "upstage_api_key": "test-api-key",
        "upstage_chat_model": "configured-solar-model",
        "moderation_encryption_key": "test-encryption-key",
        "content_hash_pepper": "test-content-pepper",
        "internal_moderation_token": "test-internal-token",
        "moderation_allow_confidence": 0.80,
        "moderation_block_confidence": 0.90,
    }
    configuration[field] = "   "

    with pytest.raises(ValidationError, match="enforce moderation requires"):
        Settings(_env_file=None, **configuration)


def test_upstage_base_url_requires_https() -> None:
    with pytest.raises(ValidationError, match="HTTPS"):
        Settings(_env_file=None, upstage_base_url="http://localhost:8443/v1")


def test_upstage_base_url_accepts_https_localhost() -> None:
    settings = Settings(_env_file=None, upstage_base_url="https://localhost:8443/v1")

    assert str(settings.upstage_base_url) == "https://localhost:8443/v1"


@pytest.mark.parametrize(
    "invalid_configuration",
    [
        {
            "moderation_mode": "enforce",
            "upstage_chat_model": "   ",
            "moderation_allow_confidence": 0.80,
            "moderation_block_confidence": 0.90,
        },
        {
            "moderation_mode": "shadow",
            "upstage_chat_model": "configured-solar-model",
            "moderation_allow_confidence": 0.95,
            "moderation_block_confidence": 0.90,
        },
    ],
)
def test_settings_validation_errors_never_render_secret_inputs(
    invalid_configuration: dict[str, object],
) -> None:
    assert Settings.model_config.get("hide_input_in_errors") is True
    secret_markers = {
        "upstage_api_key": "api-secret-visible-marker",
        "moderation_encryption_key": "encryption-secret-visible-marker",
        "content_hash_pepper": "pepper-secret-visible-marker",
        "internal_moderation_token": "internal-secret-visible-marker",
    }

    with pytest.raises(ValidationError) as caught:
        Settings(_env_file=None, **secret_markers, **invalid_configuration)

    rendered_error = str(caught.value) + repr(caught.value)
    assert all(marker not in rendered_error for marker in secret_markers.values())
