from unittest.mock import AsyncMock
from uuid import uuid4

import pytest

from app.core.redaction import redact_log_fields
from app.moderation.gateway import ModerationProviderUnavailable
from app.moderation.local_rules import LocalRuleEngine
from app.moderation.metrics import ModerationMetrics
from app.moderation.models import SubmissionStatus
from app.moderation.repository import ModerationCommand
from app.moderation.schemas import (
    ContentType,
    ModerationAssessment,
    ModerationCategory,
    ModerationDecision,
    Severity,
)
from app.moderation.service import ModerationOrchestrator, ShadowModerationOrchestrator


def test_moderation_sensitive_fields_are_redacted_recursively() -> None:
    payload = {
        "ciphertext": "secret",
        "nonce": "nonce",
        "embedding": [0.1],
        "content": "raw",
        "nested": {"payload": {"command": "private"}},
    }

    assert redact_log_fields(payload) == {
        "ciphertext": "[REDACTED]",
        "nonce": "[REDACTED]",
        "embedding": "[REDACTED]",
        "content": "[REDACTED]",
        "nested": {"payload": "[REDACTED]"},
    }


def test_metrics_accept_only_bounded_non_identifying_labels() -> None:
    metrics = ModerationMetrics()
    metrics.record_decision(
        ContentType.FEED,
        ModerationDecision.BLOCK,
        {ModerationCategory.HARASSMENT},
        42.0,
    )

    assert metrics.decisions == {("FEED", "BLOCK"): 1}
    assert metrics.categories == {("FEED", "HARASSMENT"): 1}
    assert metrics.latencies == {("FEED", "le_50ms"): 1}


def moderation_command(text: str = "ordinary text") -> ModerationCommand:
    return ModerationCommand(
        owner_id=uuid4(),
        content_type=ContentType.FEED,
        operation="CREATE_FEED",
        text=text,
        payload={"content": text},
        idempotency_key="shadow-test",
    )


def assessment(decision: ModerationDecision) -> ModerationAssessment:
    return ModerationAssessment(
        decision=decision,
        categories={ModerationCategory.HARASSMENT}
        if decision is not ModerationDecision.ALLOW
        else set(),
        severity=Severity.NONE if decision is ModerationDecision.ALLOW else Severity.HIGH,
        confidence=0.99,
        reason="provider result",
    )


@pytest.mark.parametrize(
    "provider_assessment",
    [
        assessment(ModerationDecision.ALLOW),
        assessment(ModerationDecision.REVIEW),
        assessment(ModerationDecision.BLOCK),
    ],
)
async def test_shadow_records_decision_and_always_returns_immediate(
    provider_assessment: ModerationAssessment,
) -> None:
    metrics = ModerationMetrics()
    gateway = AsyncMock()
    gateway.classify.return_value = provider_assessment
    shadow = ShadowModerationOrchestrator(
        gateway,
        metrics,
        0.80,
        0.90,
        local_rules=LocalRuleEngine(),
    )

    outcome = await shadow.evaluate(moderation_command())

    assert outcome.is_immediate
    assert sum(metrics.decisions.values()) == 1
    assert metrics.decisions == {("FEED", provider_assessment.decision.value): 1}
    assert not hasattr(shadow, "_repository")


async def test_shadow_provider_failure_is_immediate_and_bounded() -> None:
    submitted_text = "PRIVATE_MARKER_DO_NOT_RECORD"
    metrics = ModerationMetrics()
    gateway = AsyncMock()
    gateway.classify.side_effect = ModerationProviderUnavailable()
    shadow = ShadowModerationOrchestrator(
        gateway,
        metrics,
        0.80,
        0.90,
        local_rules=LocalRuleEngine(),
    )

    outcome = await shadow.evaluate(moderation_command(submitted_text))

    assert outcome.is_immediate
    assert metrics.decisions == {("FEED", "PROVIDER_FAILURE"): 1}
    assert sum(metrics.latencies.values()) == 1
    assert submitted_text not in repr(metrics)


async def test_shadow_unexpected_gateway_error_does_not_record_provider_failure() -> None:
    gateway_error = AttributeError("gateway implementation bug")
    metrics = ModerationMetrics()
    gateway = AsyncMock()
    gateway.classify.side_effect = gateway_error
    shadow = ShadowModerationOrchestrator(
        gateway,
        metrics,
        0.80,
        0.90,
        local_rules=LocalRuleEngine(),
    )

    with pytest.raises(AttributeError) as caught:
        await shadow.evaluate(moderation_command())

    assert caught.value is gateway_error
    assert metrics.decisions == {}
    assert metrics.categories == {}
    assert metrics.latencies == {}


@pytest.mark.parametrize(
    ("raw_decision", "confidence", "expected"),
    (
        (ModerationDecision.ALLOW, 0.81, ModerationDecision.ALLOW),
        (ModerationDecision.ALLOW, 0.80, ModerationDecision.ALLOW),
        (ModerationDecision.ALLOW, 0.79, ModerationDecision.REVIEW),
        (ModerationDecision.BLOCK, 0.91, ModerationDecision.BLOCK),
        (ModerationDecision.BLOCK, 0.90, ModerationDecision.BLOCK),
        (ModerationDecision.BLOCK, 0.89, ModerationDecision.REVIEW),
    ),
)
async def test_shadow_and_enforce_share_effective_confidence_decision(
    raw_decision: ModerationDecision,
    confidence: float,
    expected: ModerationDecision,
) -> None:
    provider_assessment = assessment(raw_decision).model_copy(
        update={"confidence": confidence}
    )
    shadow_gateway = AsyncMock()
    shadow_gateway.classify.return_value = provider_assessment
    enforce_gateway = AsyncMock()
    enforce_gateway.classify.return_value = provider_assessment
    repository = AsyncMock()
    repository.create_pending.return_value.id = uuid4()
    metrics = ModerationMetrics()
    shadow = ShadowModerationOrchestrator(
        shadow_gateway, metrics, 0.80, 0.90, local_rules=LocalRuleEngine()
    )
    enforce = ModerationOrchestrator(
        enforce_gateway, repository, 0.80, 0.90, local_rules=LocalRuleEngine()
    )

    shadow_outcome = await shadow.evaluate(moderation_command())
    enforce_outcome = await enforce.evaluate(moderation_command())

    if enforce_outcome.status is SubmissionStatus.BLOCKED:
        enforce_decision = ModerationDecision.BLOCK
    elif enforce_outcome.status is SubmissionStatus.PENDING_REVIEW:
        enforce_decision = ModerationDecision.REVIEW
    else:
        enforce_decision = ModerationDecision.ALLOW
    assert shadow_outcome.is_immediate
    assert metrics.decisions == {("FEED", expected.value): 1}
    assert enforce_decision is expected
    assert not hasattr(shadow, "_repository")


async def test_shadow_low_confidence_block_records_review_without_storage() -> None:
    metrics = ModerationMetrics()
    gateway = AsyncMock()
    gateway.classify.return_value = assessment(ModerationDecision.BLOCK).model_copy(
        update={"confidence": 0.89}
    )
    shadow = ShadowModerationOrchestrator(
        gateway, metrics, 0.80, 0.90, local_rules=LocalRuleEngine()
    )

    outcome = await shadow.evaluate(moderation_command())

    assert outcome.is_immediate
    assert outcome.assessment is not None
    assert outcome.assessment.decision is ModerationDecision.BLOCK
    assert metrics.decisions == {("FEED", "REVIEW"): 1}
    assert not hasattr(shadow, "_repository")


async def test_shadow_local_hard_block_remains_effective_block() -> None:
    metrics = ModerationMetrics()
    gateway = AsyncMock()
    shadow = ShadowModerationOrchestrator(
        gateway, metrics, 0.80, 0.90, local_rules=LocalRuleEngine()
    )

    outcome = await shadow.evaluate(
        moderation_command("https://a.co https://b.co https://c.co https://d.co")
    )

    assert outcome.is_immediate
    assert outcome.assessment is not None
    assert outcome.assessment.confidence == 1.0
    assert metrics.decisions == {("FEED", "BLOCK"): 1}
    gateway.classify.assert_not_awaited()
