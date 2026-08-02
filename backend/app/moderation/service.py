import math
from collections.abc import Callable
from dataclasses import dataclass, field, replace
from time import perf_counter
from uuid import UUID, uuid4

from pydantic import ValidationError

from app.moderation.gateway import ModerationGateway, ModerationProviderUnavailable
from app.moderation.local_rules import LocalRuleEngine, LocalRuleResult, normalize_text
from app.moderation.metrics import ModerationMetrics
from app.moderation.models import SubmissionStatus
from app.moderation.repository import (
    LOCAL_RULE_PROVENANCE,
    ModerationCommand,
    ModerationRepository,
)
from app.moderation.schemas import (
    ContentType,
    ModerationAssessment,
    ModerationCategory,
    ModerationDecision,
    Severity,
)


@dataclass(frozen=True, slots=True)
class ModerationOutcome:
    http_status: int
    status: SubmissionStatus | None = None
    submission_id: UUID | None = None
    error_code: str | None = None
    categories: frozenset[ModerationCategory] = field(default_factory=frozenset)
    assessment: ModerationAssessment | None = field(default=None, repr=False)

    def __post_init__(self) -> None:
        if self.status is SubmissionStatus.BLOCKED and not self.categories:
            raise ValueError("blocked moderation outcome requires categories")

    @property
    def is_immediate(self) -> bool:
        return self.status is None and self.error_code is None

    @classmethod
    def immediate(cls, assessment: ModerationAssessment) -> "ModerationOutcome":
        return cls(http_status=200, assessment=assessment)

    @classmethod
    def pending(cls, submission_id: UUID) -> "ModerationOutcome":
        return cls(
            http_status=202,
            status=SubmissionStatus.PENDING_REVIEW,
            submission_id=submission_id,
        )

    @classmethod
    def blocked(
        cls, categories: set[ModerationCategory] | frozenset[ModerationCategory]
    ) -> "ModerationOutcome":
        return cls(
            http_status=422,
            status=SubmissionStatus.BLOCKED,
            error_code="CONTENT_POLICY_VIOLATION",
            categories=frozenset(categories),
        )


class InvalidModerationConfiguration(ValueError):
    def __init__(self) -> None:
        super().__init__("invalid moderation configuration")


class InvalidModerationCommand(ValueError):
    def __init__(self) -> None:
        super().__init__("invalid moderation command")


class ModerationClassificationUnavailable(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class ModerationConfidencePolicy:
    allow_confidence: float
    block_confidence: float

    def __post_init__(self) -> None:
        if (
            type(self.allow_confidence) is not float
            or type(self.block_confidence) is not float
            or not math.isfinite(self.allow_confidence)
            or not math.isfinite(self.block_confidence)
            or not 0 <= self.allow_confidence < self.block_confidence <= 1
        ):
            raise InvalidModerationConfiguration()

    def resolve(self, assessment: ModerationAssessment) -> ModerationDecision:
        if (
            assessment.decision is ModerationDecision.BLOCK
            and assessment.confidence >= self.block_confidence
        ):
            return ModerationDecision.BLOCK
        if (
            assessment.decision is ModerationDecision.ALLOW
            and assessment.confidence >= self.allow_confidence
        ):
            return ModerationDecision.ALLOW
        return ModerationDecision.REVIEW


async def classify_normalized(
    gateway: ModerationGateway,
    local_rules: LocalRuleEngine,
    command: ModerationCommand,
) -> ModerationAssessment:
    """Classify an already-normalized command without storing any state."""

    local = local_rules.inspect(command.text)
    if local.decision is ModerationDecision.BLOCK:
        return _local_block_assessment(local)

    try:
        untrusted_assessment = await gateway.classify(command.content_type, command.text)
        provider_assessment = ModerationAssessment.model_validate(
            untrusted_assessment.model_dump()
        )
        if (
            provider_assessment.provider_request_id is not None
            and provider_assessment.provider_request_id in command.text
        ):
            provider_assessment = provider_assessment.model_copy(
                update={"provider_request_id": None}
            )
    except (
        AttributeError,
        ModerationProviderUnavailable,
        TypeError,
        ValidationError,
    ):
        raise ModerationClassificationUnavailable() from None

    return combine_assessments(local, provider_assessment)


class ModerationOrchestrator:
    def __init__(
        self,
        gateway: ModerationGateway,
        repository: ModerationRepository,
        allow_confidence: float,
        block_confidence: float,
        local_rules: LocalRuleEngine | None = None,
    ) -> None:
        self._gateway = gateway
        self._repository = repository
        self._confidence_policy = ModerationConfidencePolicy(
            allow_confidence, block_confidence
        )
        self._local_rules = local_rules or LocalRuleEngine()

    async def evaluate(self, command: ModerationCommand) -> ModerationOutcome:
        normalized_command = _normalize_command(command)
        local = self._local_rules.inspect(normalized_command.text)

        if local.decision is ModerationDecision.BLOCK:
            assessment = _local_block_assessment(local)
            await self._repository.create_blocked(
                normalized_command, assessment, LOCAL_RULE_PROVENANCE
            )
            return ModerationOutcome.blocked(local.categories)

        try:
            assessment = await classify_normalized(
                self._gateway, self._local_rules, normalized_command
            )
        except ModerationClassificationUnavailable:
            submission = await self._repository.create_pending(normalized_command, None)
            return ModerationOutcome.pending(submission.id)

        effective_decision = self._confidence_policy.resolve(assessment)
        if effective_decision is ModerationDecision.BLOCK:
            await self._repository.create_blocked(
                normalized_command, _assessment_for_storage(assessment)
            )
            return ModerationOutcome.blocked(assessment.categories)

        if effective_decision is ModerationDecision.ALLOW:
            return ModerationOutcome.immediate(assessment)

        submission = await self._repository.create_pending(
            normalized_command, _assessment_for_storage(assessment)
        )
        return ModerationOutcome.pending(submission.id)

    async def evaluate_ocr(self, owner_id: UUID, text: str) -> ModerationOutcome:
        return await self.evaluate(
            ModerationCommand(
                owner_id=owner_id,
                content_type=ContentType.OCR_TEXT,
                operation="OCR_TEXT",
                text=text,
                payload={"text": text},
                idempotency_key=str(uuid4()),
            )
        )


class ShadowModerationOrchestrator:
    """Classify content without retaining a moderation submission or command."""

    __slots__ = ("_confidence_policy", "_gateway", "_local_rules", "_metrics")

    def __init__(
        self,
        gateway: ModerationGateway,
        metrics: ModerationMetrics,
        allow_confidence: float,
        block_confidence: float,
        *,
        local_rules: LocalRuleEngine | None = None,
    ) -> None:
        self._gateway = gateway
        self._metrics = metrics
        self._confidence_policy = ModerationConfidencePolicy(
            allow_confidence, block_confidence
        )
        self._local_rules = local_rules or LocalRuleEngine()

    async def evaluate(self, command: ModerationCommand) -> ModerationOutcome:
        normalized_command = _normalize_command(command)
        started = perf_counter()
        try:
            assessment = await classify_normalized(
                self._gateway, self._local_rules, normalized_command
            )
        except ModerationClassificationUnavailable:
            self._metrics.record_provider_failure(
                normalized_command.content_type, (perf_counter() - started) * 1000
            )
            return ModerationOutcome(http_status=200)

        self._metrics.record_decision(
            normalized_command.content_type,
            self._confidence_policy.resolve(assessment),
            assessment.categories,
            (perf_counter() - started) * 1000,
        )
        return ModerationOutcome.immediate(assessment)

    async def evaluate_ocr(self, owner_id: UUID, text: str) -> ModerationOutcome:
        return await self.evaluate(
            ModerationCommand(
                owner_id=owner_id,
                content_type=ContentType.OCR_TEXT,
                operation="OCR_TEXT",
                text=text,
                payload={"text": text},
                idempotency_key=str(uuid4()),
            )
        )


class RecordingModerationOrchestrator:
    def __init__(
        self,
        inner: ModerationOrchestrator,
        metrics: ModerationMetrics,
        *,
        shadow: bool,
    ) -> None:
        self._inner = inner
        self._metrics = metrics
        self._shadow = shadow

    async def evaluate(self, command: ModerationCommand) -> ModerationOutcome:
        started = perf_counter()
        outcome = await self._inner.evaluate(command)
        decision, categories = _outcome_labels(outcome)
        self._metrics.record_decision(
            command.content_type,
            decision,
            categories,
            (perf_counter() - started) * 1000,
        )
        return ModerationOutcome(http_status=200) if self._shadow else outcome

    async def evaluate_ocr(self, owner_id: UUID, text: str) -> ModerationOutcome:
        return await self.evaluate(
            ModerationCommand(
                owner_id=owner_id,
                content_type=ContentType.OCR_TEXT,
                operation="OCR_TEXT",
                text=text,
                payload={"text": text},
                idempotency_key=str(uuid4()),
            )
        )


def _outcome_labels(
    outcome: ModerationOutcome,
) -> tuple[ModerationDecision, set[ModerationCategory] | frozenset[ModerationCategory]]:
    if outcome.status is SubmissionStatus.BLOCKED:
        return ModerationDecision.BLOCK, outcome.categories
    if outcome.status is SubmissionStatus.PENDING_REVIEW:
        categories = outcome.assessment.categories if outcome.assessment else set()
        return ModerationDecision.REVIEW, categories
    if outcome.assessment is not None:
        return outcome.assessment.decision, outcome.assessment.categories
    return ModerationDecision.ALLOW, set()


def combine_assessments(
    local: LocalRuleResult, provider: ModerationAssessment
) -> ModerationAssessment:
    if local.decision is ModerationDecision.ALLOW:
        return provider
    categories = set(provider.categories) | set(local.categories)
    combined_decision = (
        ModerationDecision.BLOCK
        if provider.decision is ModerationDecision.BLOCK
        else ModerationDecision.REVIEW
    )
    return provider.model_copy(
        update={
            "decision": combined_decision,
            "categories": categories,
            "severity": max(provider.severity, Severity.MEDIUM, key=_severity_rank),
            "reason": "local policy requires review",
        }
    )


def _local_block_assessment(local: LocalRuleResult) -> ModerationAssessment:
    return ModerationAssessment(
        decision=ModerationDecision.BLOCK,
        categories=set(local.categories),
        severity=Severity.HIGH,
        confidence=1.0,
        reason=local.reason_code,
    )


def _assessment_for_storage(assessment: ModerationAssessment) -> ModerationAssessment:
    """Strip free-form provider prose that could echo submitted content."""

    return assessment.model_copy(
        update={"reason": f"PROVIDER_{assessment.decision.value}"}
    )


def _severity_rank(severity: Severity) -> int:
    return {
        Severity.NONE: 0,
        Severity.LOW: 1,
        Severity.MEDIUM: 2,
        Severity.HIGH: 3,
        Severity.CRITICAL: 4,
    }[severity]


def _normalize_command(command: ModerationCommand) -> ModerationCommand:
    return replace(
        command,
        text=normalize_text(command.text),
        payload=_normalize_payload(command.payload),
    )


def _normalize_payload(payload: dict[str, object]) -> dict[str, object]:
    state = _NormalizationState()
    normalized = _normalize_value(payload, depth=0, state=state)
    if type(normalized) is not dict:
        raise InvalidModerationCommand()
    return normalized


@dataclass(slots=True)
class _NormalizationState:
    nodes: int = 0
    active_containers: set[int] = field(default_factory=set)


_MAX_PAYLOAD_DEPTH = 20
_MAX_PAYLOAD_NODES = 1_024


def _normalize_value(
    value: object, *, depth: int, state: _NormalizationState
) -> object:
    state.nodes += 1
    if state.nodes > _MAX_PAYLOAD_NODES or depth > _MAX_PAYLOAD_DEPTH:
        raise InvalidModerationCommand()
    if type(value) is str:
        if not isinstance(value, str):
            raise InvalidModerationCommand()
        return normalize_text(value)
    if value is None or type(value) is bool or type(value) is int:
        return value
    if type(value) is float:
        if not isinstance(value, float) or not math.isfinite(value):
            raise InvalidModerationCommand()
        return value
    if type(value) is list:
        if not isinstance(value, list):
            raise InvalidModerationCommand()
        return _normalize_container(
            value,
            state,
            lambda: [
                _normalize_value(item, depth=depth + 1, state=state)
                for item in value
            ],
        )
    if type(value) is dict:
        if not isinstance(value, dict) or any(type(key) is not str for key in value):
            raise InvalidModerationCommand()
        return _normalize_container(
            value,
            state,
            lambda: {
                key: _normalize_value(item, depth=depth + 1, state=state)
                for key, item in value.items()
            },
        )
    raise InvalidModerationCommand()


def _normalize_container(
    value: list[object] | dict[object, object],
    state: _NormalizationState,
    normalize: Callable[[], object],
) -> object:
    identity = id(value)
    if identity in state.active_containers:
        raise InvalidModerationCommand()
    state.active_containers.add(identity)
    try:
        return normalize()
    finally:
        state.active_containers.remove(identity)
