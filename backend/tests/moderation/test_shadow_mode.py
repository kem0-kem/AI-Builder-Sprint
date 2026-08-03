import base64
from collections.abc import AsyncGenerator
from dataclasses import dataclass
from typing import cast

import pytest
from httpx import AsyncClient, Response
from pydantic import SecretStr
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.ai.gateway import get_writing_assistant
from app.core.config import Settings, moderation_configuration_complete
from app.events.outbox import OutboxEvent
from app.feeds.models import Feed
from app.moderation import dependencies
from app.moderation.gateway import ModerationProviderUnavailable
from app.moderation.metrics import ModerationMetrics
from app.moderation.models import ContentSubmission, ModerationDecisionRecord
from app.moderation.schemas import (
    ContentType,
    ModerationAssessment,
    ModerationCategory,
    ModerationDecision,
    Severity,
)
from app.moderation.service import ShadowModerationOrchestrator
from tests.letters.test_letter_delivery import register

VALID_ENFORCE_KEY = base64.b64encode(b"k" * 32).decode("ascii")
INVALID_ENFORCE_KEYS = (
    "not-base64!",
    VALID_ENFORCE_KEY[:-2] + "t=",
    base64.b64encode(b"short").decode("ascii"),
)


class StubGateway:
    def __init__(self, result: ModerationAssessment | Exception) -> None:
        self.result = result
        self.calls: list[tuple[ContentType, str]] = []

    async def classify(
        self, content_type: ContentType, text: str
    ) -> ModerationAssessment:
        self.calls.append((content_type, text))
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


class StubAssistant:
    async def ocr(self, _image_bytes: bytes, _mime_type: str) -> str:
        return "extracted private text"


@dataclass(slots=True)
class ShadowHarness:
    settings: Settings
    gateway: StubGateway
    metrics: ModerationMetrics
    gateway_builds: list[dict[str, object]]


def assessment(decision: ModerationDecision) -> ModerationAssessment:
    categories = (
        {ModerationCategory.HARASSMENT}
        if decision is ModerationDecision.BLOCK
        else set()
    )
    return ModerationAssessment(
        decision=decision,
        categories=categories,
        severity=Severity.HIGH if categories else Severity.NONE,
        confidence=0.99,
        reason="bounded test result",
    )


@pytest.fixture
def shadow_harness(monkeypatch: pytest.MonkeyPatch) -> ShadowHarness:
    settings = Settings(
        _env_file=None,
        upstage_api_key=SecretStr("shadow-provider-key"),
        upstage_chat_model="shadow-model",
        moderation_mode="shadow",
        moderation_allow_confidence=0.7,
        moderation_block_confidence=0.9,
        moderation_encryption_key=None,
        content_hash_pepper=None,
        internal_moderation_token=None,
    )
    gateway = StubGateway(assessment(ModerationDecision.ALLOW))
    metrics = ModerationMetrics()
    gateway_builds: list[dict[str, object]] = []

    def gateway_factory(**_kwargs: object) -> StubGateway:
        gateway_builds.append(_kwargs)
        return gateway

    def forbidden_storage_dependency(*_args: object, **_kwargs: object) -> None:
        raise AssertionError("shadow constructed a moderation storage dependency")

    monkeypatch.setattr(dependencies, "get_settings", lambda: settings)
    monkeypatch.setattr(dependencies, "moderation_metrics", metrics)
    monkeypatch.setattr(dependencies, "UpstageModerationGateway", gateway_factory)
    monkeypatch.setattr(
        dependencies, "ModerationRepository", forbidden_storage_dependency
    )
    monkeypatch.setattr(dependencies, "CommandCipher", forbidden_storage_dependency)
    return ShadowHarness(
        settings=settings,
        gateway=gateway,
        metrics=metrics,
        gateway_builds=gateway_builds,
    )


async def count_rows(
    session_factory: async_sessionmaker[AsyncSession],
    model: type[object],
) -> int:
    async with session_factory() as session:
        return await session.scalar(select(func.count()).select_from(model)) or 0


async def category_id(client: AsyncClient) -> str:
    response = await client.get("/api/v1/feed-categories")
    assert response.status_code == 200
    category = response.json()["data"][0]["id"]
    assert isinstance(category, str)
    return category


async def post_feed(
    client: AsyncClient, headers: dict[str, str], category: str, content: str
) -> Response:
    return await client.post(
        "/api/v1/feeds",
        headers=headers,
        json={"categoryId": category, "title": "shadow title", "content": content},
    )


async def assert_no_moderation_state(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    assert await count_rows(session_factory, ContentSubmission) == 0
    assert await count_rows(session_factory, ModerationDecisionRecord) == 0
    assert await count_rows(session_factory, OutboxEvent) == 0


async def test_shadow_dependency_needs_no_crypto_or_storage(
    session_factory: async_sessionmaker[AsyncSession],
    shadow_harness: ShadowHarness,
) -> None:
    assert shadow_harness.settings.moderation_encryption_key is None
    assert shadow_harness.settings.content_hash_pepper is None

    async with session_factory() as session:
        dependency = cast(
            AsyncGenerator[object, None],
            dependencies.get_moderation_orchestrator(session),
        )
        orchestrator = await anext(dependency)
        await dependency.aclose()

    assert isinstance(orchestrator, ShadowModerationOrchestrator)


@pytest.mark.parametrize(
    ("field", "blank_value"),
    (
        ("upstage_api_key", SecretStr(" \t ")),
        ("upstage_chat_model", " \t "),
    ),
)
async def test_blank_shadow_provider_config_uses_incomplete_fallback(
    session_factory: async_sessionmaker[AsyncSession],
    shadow_harness: ShadowHarness,
    field: str,
    blank_value: object,
) -> None:
    setattr(shadow_harness.settings, field, blank_value)

    async with session_factory() as session:
        dependency = cast(
            AsyncGenerator[object, None],
            dependencies.get_moderation_orchestrator(session),
        )
        orchestrator = await anext(dependency)
        await dependency.aclose()

    assert orchestrator is None
    assert shadow_harness.gateway_builds == []
    assert shadow_harness.gateway.calls == []


@pytest.mark.parametrize(
    ("overrides", "expected"),
    (
        ({"upstage_api_key": SecretStr(" \t ")}, False),
        ({"upstage_chat_model": " \t "}, False),
        ({"moderation_allow_confidence": None}, False),
        ({"moderation_block_confidence": None}, False),
        ({}, True),
    ),
)
def test_shadow_configuration_completeness(
    overrides: dict[str, object],
    expected: bool,
) -> None:
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

    assert moderation_configuration_complete(settings) is expected


def test_complete_enforce_configuration_is_complete() -> None:
    settings = Settings(
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

    assert moderation_configuration_complete(settings) is True


@pytest.mark.parametrize("invalid_key", INVALID_ENFORCE_KEYS)
async def test_invalid_enforce_key_stops_before_storage_construction(
    session_factory: async_sessionmaker[AsyncSession],
    monkeypatch: pytest.MonkeyPatch,
    invalid_key: str,
) -> None:
    settings = Settings(
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
    settings.moderation_encryption_key = SecretStr(invalid_key)

    def forbidden_storage_dependency(*_args: object, **_kwargs: object) -> None:
        raise AssertionError("invalid enforce key reached storage construction")

    monkeypatch.setattr(dependencies, "get_settings", lambda: settings)
    monkeypatch.setattr(dependencies, "CommandCipher", forbidden_storage_dependency)
    monkeypatch.setattr(
        dependencies, "ModerationRepository", forbidden_storage_dependency
    )

    async with session_factory() as session:
        dependency = cast(
            AsyncGenerator[object, None],
            dependencies.get_moderation_orchestrator(session),
        )
        orchestrator = await anext(dependency)
        await dependency.aclose()

    assert moderation_configuration_complete(settings) is False
    assert orchestrator is None


async def test_dependency_and_completeness_share_incomplete_result(
    session_factory: async_sessionmaker[AsyncSession],
    shadow_harness: ShadowHarness,
) -> None:
    shadow_harness.settings.moderation_allow_confidence = None

    async with session_factory() as session:
        dependency = cast(
            AsyncGenerator[object, None],
            dependencies.get_moderation_orchestrator(session),
        )
        orchestrator = await anext(dependency)
        await dependency.aclose()

    assert moderation_configuration_complete(shadow_harness.settings) is False
    assert orchestrator is None
    assert shadow_harness.gateway_builds == []


async def test_shadow_block_persists_feed_only(
    client: AsyncClient,
    session_factory: async_sessionmaker[AsyncSession],
    shadow_harness: ShadowHarness,
) -> None:
    shadow_harness.gateway.result = assessment(ModerationDecision.BLOCK)
    author = await register(client, "shadow-block@example.com", "Shadow Block")
    category = await category_id(client)

    response = await post_feed(client, author, category, "provider should block this")

    assert response.status_code == 201
    assert await count_rows(session_factory, Feed) == 1
    await assert_no_moderation_state(session_factory)
    assert len(shadow_harness.gateway.calls) == 1
    assert shadow_harness.metrics.decisions == {("FEED", "BLOCK"): 1}


async def test_shadow_provider_failure_persists_feed_without_retry_state(
    client: AsyncClient,
    session_factory: async_sessionmaker[AsyncSession],
    shadow_harness: ShadowHarness,
) -> None:
    shadow_harness.gateway.result = ModerationProviderUnavailable(
        "provider unavailable"
    )
    author = await register(client, "shadow-failure@example.com", "Shadow Failure")
    category = await category_id(client)

    response = await post_feed(client, author, category, "provider failure content")

    assert response.status_code == 201
    assert await count_rows(session_factory, Feed) == 1
    await assert_no_moderation_state(session_factory)
    assert len(shadow_harness.gateway.calls) == 1
    assert shadow_harness.metrics.decisions == {("FEED", "PROVIDER_FAILURE"): 1}


async def test_shadow_ocr_returns_text_and_records_only_ocr_metric(
    client: AsyncClient,
    session_factory: async_sessionmaker[AsyncSession],
    shadow_harness: ShadowHarness,
) -> None:
    application = client._transport.app  # type: ignore[attr-defined]
    application.dependency_overrides[get_writing_assistant] = lambda: StubAssistant()
    author = await register(client, "shadow-ocr@example.com", "Shadow OCR")

    response = await client.post(
        "/api/v1/letters/ocr",
        headers=author,
        files={"image": ("scan.png", b"\x89PNG\r\n\x1a\ncontent", "image/png")},
    )

    assert response.status_code == 200
    assert response.json()["data"]["text"] == "extracted private text"
    await assert_no_moderation_state(session_factory)
    assert shadow_harness.gateway.calls == [
        (ContentType.OCR_TEXT, "extracted private text")
    ]
    assert shadow_harness.metrics.decisions == {("OCR_TEXT", "ALLOW"): 1}
