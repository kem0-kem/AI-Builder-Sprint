import math
from collections.abc import Callable
from dataclasses import dataclass, field, replace
from uuid import UUID

from pydantic import ValidationError

from app.moderation.gateway import ModerationGateway, ModerationProviderUnavailable
from app.moderation.local_rules import LocalRuleEngine, LocalRuleResult, normalize_text
from app.moderation.models import SubmissionStatus
from app.moderation.repository import (
    LOCAL_RULE_PROVENANCE,
    ModerationCommand,
    ModerationRepository,
)
from app.moderation.schemas import (
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


class ModerationOrchestrator:
    def __init__(
        self,
        gateway: ModerationGateway,
        repository: ModerationRepository,
        allow_confidence: float,
        block_confidence: float,
        local_rules: LocalRuleEngine | None = None,
    ) -> None:
        if (
            type(allow_confidence) is not float
            or type(block_confidence) is not float
            or not math.isfinite(allow_confidence)
            or not math.isfinite(block_confidence)
            or not 0 <= allow_confidence < block_confidence <= 1
        ):
            raise InvalidModerationConfiguration()
        self._gateway = gateway
        self._repository = repository
        self._allow_confidence = allow_confidence
        self._block_confidence = block_confidence
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
            untrusted_assessment = await self._gateway.classify(
                normalized_command.content_type, normalized_command.text
            )
            provider_assessment = ModerationAssessment.model_validate(
                untrusted_assessment.model_dump()
            )
            if (
                provider_assessment.provider_request_id is not None
                and provider_assessment.provider_request_id in normalized_command.text
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
            submission = await self._repository.create_pending(normalized_command, None)
            return ModerationOutcome.pending(submission.id)

        assessment = _combine(local, provider_assessment)
        if (
            assessment.decision is ModerationDecision.BLOCK
            and assessment.confidence >= self._block_confidence
        ):
            await self._repository.create_blocked(
                normalized_command, _assessment_for_storage(assessment)
            )
            return ModerationOutcome.blocked(assessment.categories)

        if (
            assessment.decision is ModerationDecision.ALLOW
            and assessment.confidence >= self._allow_confidence
        ):
            return ModerationOutcome.immediate(assessment)

        submission = await self._repository.create_pending(
            normalized_command, _assessment_for_storage(assessment)
        )
        return ModerationOutcome.pending(submission.id)


def _combine(
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
