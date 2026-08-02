from uuid import uuid4

from app.core.redaction import redact_log_fields
from app.moderation.metrics import ModerationMetrics
from app.moderation.repository import ModerationCommand
from app.moderation.schemas import ContentType, ModerationCategory, ModerationDecision
from app.moderation.service import ModerationOutcome, RecordingModerationOrchestrator


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


async def test_shadow_records_block_but_returns_immediate_success() -> None:
    class BlockingOrchestrator:
        async def evaluate(self, _command: ModerationCommand) -> ModerationOutcome:
            return ModerationOutcome.blocked({ModerationCategory.HARASSMENT})

    metrics = ModerationMetrics()
    shadow = RecordingModerationOrchestrator(  # type: ignore[arg-type]
        BlockingOrchestrator(), metrics, shadow=True
    )
    outcome = await shadow.evaluate(
        ModerationCommand(
            owner_id=uuid4(),
            content_type=ContentType.FEED,
            operation="CREATE_FEED",
            text="blocked",
            payload={"content": "blocked"},
            idempotency_key="shadow-test",
        )
    )

    assert outcome.is_immediate
    assert metrics.decisions == {("FEED", "BLOCK"): 1}
