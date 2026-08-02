from unittest.mock import AsyncMock
from uuid import uuid4

import pytest

from app.core.redaction import redact_log_fields
from app.moderation.gateway import ModerationProviderUnavailable
from app.moderation.local_rules import LocalRuleEngine
from app.moderation.metrics import ModerationMetrics
from app.moderation.repository import ModerationCommand
from app.moderation.schemas import (
    ContentType,
    ModerationAssessment,
    ModerationCategory,
    ModerationDecision,
    Severity,
)
from app.moderation.service import ShadowModerationOrchestrator


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
        gateway, metrics, local_rules=LocalRuleEngine()
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
    shadow = ShadowModerationOrchestrator(gateway, metrics, local_rules=LocalRuleEngine())

    outcome = await shadow.evaluate(moderation_command(submitted_text))

    assert outcome.is_immediate
    assert metrics.decisions == {("FEED", "PROVIDER_FAILURE"): 1}
    assert sum(metrics.latencies.values()) == 1
    assert submitted_text not in repr(metrics)
