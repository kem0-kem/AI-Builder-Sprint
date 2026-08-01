from datetime import UTC, datetime, timedelta
from uuid import UUID

from app.core.errors import ApiError
from app.moderation.command_handlers import ModeratedCommandRegistry
from app.moderation.gateway import ModerationGateway
from app.moderation.local_rules import LocalRuleEngine
from app.moderation.models import ContentSubmission, SubmissionStatus
from app.moderation.repository import (
    LOCAL_RULE_PROVENANCE,
    ModerationRepository,
)
from app.moderation.schemas import (
    ContentType,
    ModerationAssessment,
    ModerationDecision,
    Severity,
)
from app.moderation.service import combine_assessments

RETRY_DELAYS = (
    timedelta(minutes=1),
    timedelta(minutes=5),
    timedelta(minutes=30),
)
OCR_RESULT_TTL = timedelta(hours=24)


class ModerationRetryWorker:
    """Reclassify due quarantined commands with the synchronous policy matrix."""

    def __init__(
        self,
        gateway: ModerationGateway,
        repository: ModerationRepository,
        registry: ModeratedCommandRegistry,
        *,
        allow_confidence: float,
        block_confidence: float,
        local_rules: LocalRuleEngine | None = None,
    ) -> None:
        self._gateway = gateway
        self._repository = repository
        self._registry = registry
        self._allow_confidence = allow_confidence
        self._block_confidence = block_confidence
        self._local_rules = local_rules or LocalRuleEngine()

    async def process(self, submission_id: UUID, *, now: datetime | None = None) -> None:
        submission = await self._repository.get_pending(submission_id)
        if submission is None:
            return
        current_time = _as_utc(now or datetime.now(UTC))
        if (
            submission.next_attempt_at is not None
            and _as_utc(submission.next_attempt_at) > current_time
        ):
            return
        command = self._repository.decrypt_command(submission)
        text = _moderated_text(command)
        local = self._local_rules.inspect(text)
        if local.decision is ModerationDecision.BLOCK:
            assessment = ModerationAssessment(
                decision=ModerationDecision.BLOCK,
                categories=set(local.categories),
                severity=Severity.HIGH,
                confidence=1.0,
                reason=local.reason_code,
            )
            won = await self._repository.resolve_blocked(
                submission,
                assessment,
                provenance=LOCAL_RULE_PROVENANCE,
            )
            await self._accept_lost_transition(
                submission, won, equivalent=SubmissionStatus.BLOCKED
            )
            return

        try:
            untrusted = await self._gateway.classify(submission.content_type, text)
            provider = ModerationAssessment.model_validate(untrusted.model_dump())
        except Exception:
            await self._provider_failure(submission, now=current_time, assessment=None)
            return

        assessment = combine_assessments(local, provider)
        stored = assessment.model_copy(
            update={"reason": f"PROVIDER_{assessment.decision.value}"}
        )
        if (
            assessment.decision is ModerationDecision.ALLOW
            and assessment.confidence >= self._allow_confidence
        ):
            await self.allow(submission, stored, command, now=current_time)
            return
        if (
            assessment.decision is ModerationDecision.BLOCK
            and assessment.confidence >= self._block_confidence
        ):
            won = await self._repository.resolve_blocked(submission, stored)
            await self._accept_lost_transition(
                submission, won, equivalent=SubmissionStatus.BLOCKED
            )
            return
        await self._provider_failure(
            submission, now=current_time, assessment=stored
        )

    async def allow(
        self,
        submission: ContentSubmission,
        assessment: ModerationAssessment,
        command: dict[str, object] | None = None,
        *,
        now: datetime | None = None,
    ) -> None:
        payload = command or self._repository.decrypt_command(submission)
        current_time = _as_utc(now or datetime.now(UTC))
        if submission.content_type is ContentType.OCR_TEXT:
            _validate_ocr_result(payload)
            won = await self._repository.resolve_allowed(
                submission,
                None,
                assessment,
                result_expires_at=current_time + OCR_RESULT_TTL,
            )
            await self._accept_lost_transition(
                submission, won, equivalent=SubmissionStatus.ALLOWED
            )
            return
        resource_id = await self._registry.execute(
            submission.operation, payload, submission.idempotency_key
        )
        won = await self._repository.resolve_allowed(
            submission, resource_id, assessment
        )
        await self._accept_lost_transition(
            submission,
            won,
            equivalent=SubmissionStatus.ALLOWED,
            resource_id=resource_id,
        )

    async def _provider_failure(
        self,
        submission: ContentSubmission,
        *,
        now: datetime,
        assessment: ModerationAssessment | None,
    ) -> None:
        completed_attempts = submission.attempt_count + 1
        if completed_attempts >= len(RETRY_DELAYS):
            won = await self._repository.resolve_manual_review(
                submission,
                attempt_count=completed_attempts,
                assessment=assessment,
            )
            await self._accept_lost_transition(
                submission, won, equivalent=SubmissionStatus.MANUAL_REVIEW
            )
            return
        won = await self._repository.resolve_retry(
            submission,
            attempt_count=completed_attempts,
            next_attempt_at=now + RETRY_DELAYS[completed_attempts],
            assessment=assessment,
        )
        if not won:
            current = await self._repository.get(submission.id)
            if current is None or (
                current.status is SubmissionStatus.PENDING_REVIEW
                and current.attempt_count <= submission.attempt_count
            ):
                raise ApiError(
                    "RESOURCE_CONFLICT", "검토 제출 상태가 이미 변경되었습니다.", 409
                )

    async def _accept_lost_transition(
        self,
        submission: ContentSubmission,
        won: bool,
        *,
        equivalent: SubmissionStatus,
        resource_id: UUID | None = None,
    ) -> None:
        if won:
            return
        current = await self._repository.get(submission.id)
        if current is None or current.status is not equivalent:
            raise ApiError(
                "RESOURCE_CONFLICT", "검토 제출 상태가 이미 변경되었습니다.", 409
            )
        if equivalent is SubmissionStatus.ALLOWED and (
            current.resolved_resource_id != resource_id
        ):
            raise ApiError(
                "RESOURCE_CONFLICT", "검토 제출 상태가 이미 변경되었습니다.", 409
            )


class ModerationResultCleanupWorker:
    def __init__(self, repository: ModerationRepository) -> None:
        self._repository = repository

    async def process(self, *, now: datetime | None = None) -> int:
        return await self._repository.clear_expired_ocr_results(now=now)


def _moderated_text(command: dict[str, object]) -> str:
    for key in ("text", "content", "reason"):
        value = command.get(key)
        if isinstance(value, str):
            return value
    raise ValueError("moderated command has no text")


def _validate_ocr_result(command: dict[str, object]) -> None:
    if set(command) != {"text"} or not isinstance(command.get("text"), str):
        raise ValueError("OCR moderation result must contain exactly text")


def _as_utc(value: datetime) -> datetime:
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)
