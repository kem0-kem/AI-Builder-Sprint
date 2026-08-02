import base64
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from sqlalchemy import select

from app.moderation.crypto import CommandCipher, content_hash
from app.moderation.gateway import ModerationProviderUnavailable
from app.moderation.models import (
    ContentSubmission,
    ModerationDecisionRecord,
    SubmissionStatus,
)
from app.moderation.repository import ModerationCommand, ModerationRepository
from app.moderation.schemas import (
    ContentType,
    ModerationAssessment,
    ModerationCategory,
    ModerationDecision,
    Severity,
)
from app.moderation.service import (
    InvalidModerationCommand,
    InvalidModerationConfiguration,
    ModerationOrchestrator,
    ModerationOutcome,
)


def command(text: str = "hello") -> ModerationCommand:
    return ModerationCommand(
        owner_id=uuid4(),
        content_type=ContentType.LETTER,
        operation="CREATE_LETTER",
        text=text,
        payload={"content": text, "match": True},
        idempotency_key="key-1",
    )


def assessment(
    decision: ModerationDecision,
    confidence: float,
    categories: set[ModerationCategory] | None = None,
) -> ModerationAssessment:
    effective_categories = categories
    if effective_categories is None and decision is not ModerationDecision.ALLOW:
        effective_categories = {ModerationCategory.SPAM}
    return ModerationAssessment(
        decision=decision,
        categories=effective_categories or set(),
        severity=(
            Severity.NONE
            if decision is ModerationDecision.ALLOW
            else Severity.HIGH
            if decision is ModerationDecision.BLOCK
            else Severity.MEDIUM
        ),
        confidence=confidence,
        reason="model decision",
    )


@pytest.fixture
def repository() -> AsyncMock:
    value = AsyncMock()
    pending = AsyncMock()
    pending.id = uuid4()
    value.create_pending.return_value = pending
    value.create_blocked.return_value = pending
    return value


@pytest.fixture
def gateway() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def orchestrator(gateway: AsyncMock, repository: AsyncMock) -> ModerationOrchestrator:
    return ModerationOrchestrator(
        gateway=gateway,
        repository=repository,
        allow_confidence=0.80,
        block_confidence=0.90,
    )


async def test_high_confidence_allow_is_immediate(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    gateway.classify.return_value = assessment(ModerationDecision.ALLOW, 0.95)
    outcome = await orchestrator.evaluate(command())
    assert outcome.status is None
    assert outcome.http_status == 200
    assert outcome.is_immediate
    repository.create_pending.assert_not_awaited()


async def test_review_is_encrypted_pending(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    gateway.classify.return_value = assessment(ModerationDecision.REVIEW, 0.99)
    outcome = await orchestrator.evaluate(command())
    assert outcome.status is SubmissionStatus.PENDING_REVIEW
    assert outcome.http_status == 202
    assert outcome.submission_id is not None
    repository.create_pending.assert_awaited_once()


async def test_low_confidence_allow_is_pending(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    gateway.classify.return_value = assessment(ModerationDecision.ALLOW, 0.79)
    outcome = await orchestrator.evaluate(command())
    assert outcome.status is SubmissionStatus.PENDING_REVIEW
    repository.create_pending.assert_awaited_once()


async def test_low_confidence_block_is_pending(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    gateway.classify.return_value = assessment(
        ModerationDecision.BLOCK, 0.89, {ModerationCategory.HARASSMENT}
    )
    outcome = await orchestrator.evaluate(command("uncertain"))
    assert outcome.status is SubmissionStatus.PENDING_REVIEW
    repository.create_blocked.assert_not_awaited()


async def test_high_confidence_block_stores_only_hash_and_metadata(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    gateway.classify.return_value = assessment(
        ModerationDecision.BLOCK, 0.96, {ModerationCategory.HARASSMENT}
    )
    outcome = await orchestrator.evaluate(command("raw marker"))
    assert outcome.status is SubmissionStatus.BLOCKED
    assert outcome.http_status == 422
    assert outcome.error_code == "CONTENT_POLICY_VIOLATION"
    assert outcome.categories == frozenset({ModerationCategory.HARASSMENT})
    repository.create_blocked.assert_awaited_once()
    repository.create_pending.assert_not_awaited()


async def test_provider_failure_quarantines_without_domain_write(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    gateway.classify.side_effect = ModerationProviderUnavailable()
    outcome = await orchestrator.evaluate(command("provider failure marker"))
    assert outcome.status is SubmissionStatus.PENDING_REVIEW
    assert outcome.http_status == 202
    pending_command, pending_assessment = repository.create_pending.await_args.args
    assert pending_assessment is None
    assert pending_command.text == "provider failure marker"


async def test_unexpected_gateway_error_creates_no_moderation_storage(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    gateway_error = KeyError("gateway implementation bug")
    gateway.classify.side_effect = gateway_error

    with pytest.raises(KeyError) as caught:
        await orchestrator.evaluate(command("unexpected error marker"))

    assert caught.value is gateway_error
    repository.create_pending.assert_not_awaited()
    repository.create_blocked.assert_not_awaited()


async def test_contradictory_provider_assessment_is_quarantined(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    gateway.classify.return_value = ModerationAssessment.model_construct(
        decision=ModerationDecision.ALLOW,
        categories={ModerationCategory.SPAM},
        severity=Severity.HIGH,
        confidence=0.99,
        reason="private-input-marker",
        provider_request_id=None,
    )
    outcome = await orchestrator.evaluate(command("private-input-marker"))
    assert outcome.status is SubmissionStatus.PENDING_REVIEW
    pending_command, pending_assessment = repository.create_pending.await_args.args
    assert pending_command.text == "private-input-marker"
    assert pending_assessment is None


async def test_custom_gateway_empty_block_is_pending_not_empty_category_422(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    gateway.classify.return_value = ModerationAssessment.model_construct(
        decision=ModerationDecision.BLOCK,
        categories=set(),
        severity=Severity.NONE,
        confidence=0.99,
        reason="invalid block",
        provider_request_id=None,
    )

    outcome = await orchestrator.evaluate(command("ordinary text"))

    assert outcome.status is SubmissionStatus.PENDING_REVIEW
    assert outcome.error_code is None
    repository.create_blocked.assert_not_awaited()
    repository.create_pending.assert_awaited_once()


def test_blocked_outcome_constructor_rejects_empty_categories() -> None:
    with pytest.raises(ValueError, match="requires categories"):
        ModerationOutcome.blocked(set())


async def test_orchestrator_discards_request_id_copied_from_content(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock
) -> None:
    gateway.classify.return_value = ModerationAssessment(
        decision=ModerationDecision.ALLOW,
        categories=set(),
        severity=Severity.NONE,
        confidence=0.99,
        reason="safe",
        provider_request_id="RAW-MARKER",
    )

    outcome = await orchestrator.evaluate(command("prefix RAW-MARKER suffix"))

    assert outcome.is_immediate
    assert outcome.assessment is not None
    assert outcome.assessment.provider_request_id is None


async def test_local_review_cannot_be_overridden_by_provider_allow(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    gateway.classify.return_value = assessment(ModerationDecision.ALLOW, 0.99)
    outcome = await orchestrator.evaluate(command("person@example.com"))
    assert outcome.status is SubmissionStatus.PENDING_REVIEW


async def test_local_review_does_not_downgrade_high_confidence_provider_block(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    gateway.classify.return_value = assessment(
        ModerationDecision.BLOCK, 0.99, {ModerationCategory.HARASSMENT}
    )

    outcome = await orchestrator.evaluate(command("person@example.com"))

    assert outcome.status is SubmissionStatus.BLOCKED
    repository.create_blocked.assert_awaited_once()
    repository.create_pending.assert_not_awaited()


async def test_local_review_preserves_low_confidence_provider_block_decision(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    gateway.classify.return_value = assessment(
        ModerationDecision.BLOCK, 0.89, {ModerationCategory.HARASSMENT}
    )

    outcome = await orchestrator.evaluate(command("person@example.com"))

    assert outcome.status is SubmissionStatus.PENDING_REVIEW
    persisted = repository.create_pending.await_args.args[1]
    assert persisted.decision is ModerationDecision.BLOCK
    assert persisted.categories == {
        ModerationCategory.HARASSMENT,
        ModerationCategory.PERSONAL_DATA,
    }


async def test_local_exact_block_short_circuits_provider(
    orchestrator: ModerationOrchestrator, gateway: AsyncMock, repository: AsyncMock
) -> None:
    outcome = await orchestrator.evaluate(
        command("https://a.co https://b.co https://c.co https://d.co")
    )
    assert outcome.status is SubmissionStatus.BLOCKED
    gateway.classify.assert_not_awaited()
    repository.create_blocked.assert_awaited_once()


def test_command_and_outcome_repr_do_not_leak_raw_content(
    repository: AsyncMock, gateway: AsyncMock
) -> None:
    orchestrator = ModerationOrchestrator(gateway, repository, 0.8, 0.9)
    value = command("SECRET_MARKER")
    assert "SECRET_MARKER" not in repr(value)
    assert "SECRET_MARKER" not in repr(orchestrator)
    provider_result = assessment(ModerationDecision.REVIEW, 0.5).model_copy(
        update={"reason": "SECRET_MARKER"}
    )
    assert "SECRET_MARKER" not in repr(provider_result)


@pytest.mark.parametrize(
    ("allow_threshold", "block_threshold"),
    [
        (True, 0.9),
        (0, 0.9),
        ("0.8", 0.9),
        (float("nan"), 0.9),
        (float("inf"), 0.9),
        (0.8, float("inf")),
        (0.8, float("nan")),
        (0.8, 1),
        (0.8, True),
        (0.8, "0.9"),
        (0.0, 0.0),
        (1.0, 1.0),
        (1.0, 0.0),
        (-0.1, 0.9),
        (0.8, 1.1),
    ],
)
def test_runtime_thresholds_require_exact_finite_floats(
    gateway: AsyncMock,
    repository: AsyncMock,
    allow_threshold: object,
    block_threshold: object,
) -> None:
    with pytest.raises(InvalidModerationConfiguration) as caught:
        ModerationOrchestrator(
            gateway,
            repository,
            allow_threshold,  # type: ignore[arg-type]
            block_threshold,  # type: ignore[arg-type]
        )
    assert str(caught.value) == "invalid moderation configuration"
    assert repr(caught.value) == (
        "InvalidModerationConfiguration('invalid moderation configuration')"
    )


def test_runtime_threshold_float_boundaries_are_accepted(
    gateway: AsyncMock, repository: AsyncMock
) -> None:
    ModerationOrchestrator(gateway, repository, 0.0, 1.0)


@pytest.mark.parametrize(
    "payload_kind",
    ["cycle", "deep", "oversized", "tuple", "nonfinite", "non_string_key"],
)
async def test_invalid_payload_shape_fails_with_safe_fixed_error(
    gateway: AsyncMock, repository: AsyncMock, payload_kind: str
) -> None:
    payload: dict[str, object] = {"marker": "RAW_PAYLOAD_MARKER"}
    if payload_kind == "cycle":
        payload["cycle"] = payload
    elif payload_kind == "deep":
        nested: dict[str, object] = payload
        for _ in range(30):
            child: dict[str, object] = {}
            nested["child"] = child
            nested = child
    else:
        if payload_kind == "oversized":
            payload["items"] = list(range(2_000))
        elif payload_kind == "tuple":
            payload["items"] = (1, 2)
        elif payload_kind == "nonfinite":
            payload["number"] = float("nan")
        else:
            payload[1] = "invalid"  # type: ignore[index]
    value = command("safe text")
    value = ModerationCommand(
        owner_id=value.owner_id,
        content_type=value.content_type,
        operation=value.operation,
        text=value.text,
        payload=payload,
        idempotency_key=value.idempotency_key,
    )
    orchestrator = ModerationOrchestrator(gateway, repository, 0.8, 0.9)

    with pytest.raises(InvalidModerationCommand) as caught:
        await orchestrator.evaluate(value)

    rendered = str(caught.value) + repr(caught.value)
    assert rendered == (
        "invalid moderation command"
        "InvalidModerationCommand('invalid moderation command')"
    )
    assert "RAW_PAYLOAD_MARKER" not in rendered
    gateway.classify.assert_not_awaited()


async def test_real_blocked_persistence_contains_no_raw_command_or_payload(
    session_factory,
) -> None:
    raw = "RAW_BLOCKED_MARKER"
    async with session_factory() as session:
        repository = ModerationRepository(
            session,
            CommandCipher(base64.b64encode(b"b" * 32).decode("ascii")),
            "test-pepper",
        )
        gateway = AsyncMock()
        gateway.classify.return_value = assessment(
            ModerationDecision.BLOCK, 0.99, {ModerationCategory.HARASSMENT}
        ).model_copy(update={"reason": raw})
        orchestrator = ModerationOrchestrator(gateway, repository, 0.8, 0.9)

        outcome = await orchestrator.evaluate(command(raw))
        stored = await session.scalar(select(ContentSubmission))
        decision = await session.scalar(select(ModerationDecisionRecord))

        assert outcome.status is SubmissionStatus.BLOCKED
        assert stored is not None
        assert stored.ciphertext is None
        assert stored.nonce is None
        assert stored.content_hash == content_hash(raw, "test-pepper")
        assert raw not in repr(stored.__dict__)
        assert decision is not None
        assert raw not in repr(decision.__dict__)


async def test_local_review_and_provider_block_persists_only_hash_and_metadata(
    session_factory,
) -> None:
    raw = "contact me at private@example.com"
    provider_reason = "PROVIDER_REASON_RAW_MARKER"
    async with session_factory() as session:
        repository = ModerationRepository(
            session,
            CommandCipher(base64.b64encode(b"r" * 32).decode("ascii")),
            "test-pepper",
        )
        gateway = AsyncMock()
        gateway.classify.return_value = assessment(
            ModerationDecision.BLOCK, 0.99, {ModerationCategory.HARASSMENT}
        ).model_copy(update={"reason": provider_reason})
        orchestrator = ModerationOrchestrator(gateway, repository, 0.8, 0.9)

        outcome = await orchestrator.evaluate(command(raw))
        stored = await session.scalar(select(ContentSubmission))
        decision = await session.scalar(select(ModerationDecisionRecord))

        assert outcome.status is SubmissionStatus.BLOCKED
        assert stored is not None
        assert stored.ciphertext is None
        assert stored.nonce is None
        assert stored.content_hash == content_hash(raw, "test-pepper")
        assert raw not in repr(stored.__dict__)
        assert decision is not None
        assert decision.decision is ModerationDecision.BLOCK
        assert set(decision.categories) == {"HARASSMENT", "PERSONAL_DATA"}
        assert raw not in repr(decision.__dict__)
        assert provider_reason not in repr(decision.__dict__)


async def test_local_short_circuit_block_records_local_rule_provenance(
    session_factory,
) -> None:
    raw = "https://a.co,https://b.co;https://c.co,https://d.co"
    async with session_factory() as session:
        repository = ModerationRepository(
            session,
            CommandCipher(base64.b64encode(b"l" * 32).decode("ascii")),
            "test-pepper",
        )
        gateway = AsyncMock()
        orchestrator = ModerationOrchestrator(gateway, repository, 0.8, 0.9)

        outcome = await orchestrator.evaluate(command(raw))
        decision = await session.scalar(select(ModerationDecisionRecord))

        assert outcome.status is SubmissionStatus.BLOCKED
        gateway.classify.assert_not_awaited()
        assert decision is not None
        assert decision.provider == "local-rules"
        assert decision.model == "deterministic"
        assert decision.prompt_version == "v1"
        assert decision.reason == "EXCESSIVE_URLS"


async def test_unsafe_internal_provider_request_id_quarantines_without_db_overflow(
    session_factory,
) -> None:
    unsafe_request_id = "RAW_REQUEST_ID_MARKER" * 1_000
    async with session_factory() as session:
        repository = ModerationRepository(
            session,
            CommandCipher(base64.b64encode(b"i" * 32).decode("ascii")),
            "test-pepper",
        )
        gateway = AsyncMock()
        gateway.classify.return_value = ModerationAssessment.model_construct(
            decision=ModerationDecision.ALLOW,
            categories=set(),
            severity=Severity.NONE,
            confidence=0.99,
            reason="safe",
            provider_request_id=unsafe_request_id,
        )
        orchestrator = ModerationOrchestrator(gateway, repository, 0.8, 0.9)

        outcome = await orchestrator.evaluate(command("safe content"))
        stored = await session.scalar(select(ContentSubmission))
        decision = await session.scalar(select(ModerationDecisionRecord))

        assert outcome.status is SubmissionStatus.PENDING_REVIEW
        assert stored is not None
        assert decision is None
        assert unsafe_request_id not in repr(stored.__dict__)


async def test_pending_persistence_encrypts_normalized_command(session_factory) -> None:
    async with session_factory() as session:
        repository = ModerationRepository(
            session,
            CommandCipher(base64.b64encode(b"p" * 32).decode("ascii")),
            "test-pepper",
        )
        gateway = AsyncMock()
        gateway.classify.return_value = assessment(ModerationDecision.REVIEW, 0.7)
        orchestrator = ModerationOrchestrator(gateway, repository, 0.8, 0.9)

        outcome = await orchestrator.evaluate(command("  ＨＥＬＬＯ   world  "))
        stored = await session.scalar(select(ContentSubmission))

        assert outcome.status is SubmissionStatus.PENDING_REVIEW
        assert stored is not None
        assert repository.decrypt_command(stored)["content"] == "HELLO world"
