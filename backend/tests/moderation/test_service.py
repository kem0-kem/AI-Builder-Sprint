from unittest.mock import AsyncMock
from uuid import uuid4

import pytest

from app.moderation.gateway import ModerationProviderUnavailable
from app.moderation.local_rules import LocalRuleEngine
from app.moderation.repository import ModerationCommand
from app.moderation.schemas import (
    ContentType,
    ModerationAssessment,
    ModerationCategory,
    ModerationDecision,
    Severity,
)
from app.moderation.service import (
    ModerationClassificationUnavailable,
    classify_normalized,
)


def command(text: str = "ordinary text") -> ModerationCommand:
    return ModerationCommand(
        owner_id=uuid4(),
        content_type=ContentType.LETTER,
        operation="CREATE_LETTER",
        text=text,
        payload={"content": text},
        idempotency_key="key-1",
    )


def assessment(
    decision: ModerationDecision = ModerationDecision.ALLOW,
    categories: set[ModerationCategory] | None = None,
) -> ModerationAssessment:
    return ModerationAssessment(
        decision=decision,
        categories=categories or set(),
        severity=Severity.NONE if decision is ModerationDecision.ALLOW else Severity.MEDIUM,
        confidence=0.99,
        reason="provider result",
    )


async def test_classify_normalized_local_hard_block_skips_provider() -> None:
    gateway = AsyncMock()

    result = await classify_normalized(
        gateway,
        LocalRuleEngine(),
        command("https://a.co https://b.co https://c.co https://d.co"),
    )

    assert result.decision is ModerationDecision.BLOCK
    assert result.categories == {ModerationCategory.SPAM}
    gateway.classify.assert_not_awaited()


async def test_classify_normalized_combines_provider_with_local_review() -> None:
    gateway = AsyncMock()
    gateway.classify.return_value = assessment()

    result = await classify_normalized(
        gateway, LocalRuleEngine(), command("contact person@example.com")
    )

    assert result.decision is ModerationDecision.REVIEW
    assert result.categories == {ModerationCategory.PERSONAL_DATA}
    gateway.classify.assert_awaited_once_with(
        ContentType.LETTER, "contact person@example.com"
    )


async def test_classify_normalized_wraps_provider_unavailable() -> None:
    gateway = AsyncMock()
    gateway.classify.side_effect = ModerationProviderUnavailable()

    with pytest.raises(ModerationClassificationUnavailable) as caught:
        await classify_normalized(gateway, LocalRuleEngine(), command())

    assert caught.value.__cause__ is None


async def test_classify_normalized_wraps_malformed_provider_result_without_echo() -> None:
    submitted_text = "PRIVATE_SUBMITTED_CONTENT"
    gateway = AsyncMock()
    gateway.classify.return_value = ModerationAssessment.model_construct(
        decision=ModerationDecision.ALLOW,
        categories={ModerationCategory.SPAM},
        severity=Severity.HIGH,
        confidence=0.99,
        reason=submitted_text,
        provider_request_id=None,
    )

    with pytest.raises(ModerationClassificationUnavailable) as caught:
        await classify_normalized(gateway, LocalRuleEngine(), command(submitted_text))

    rendered = str(caught.value) + repr(caught.value)
    assert submitted_text not in rendered
    assert caught.value.__cause__ is None
