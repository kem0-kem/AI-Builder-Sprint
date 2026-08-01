from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import UUID

from sqlalchemy import select, update
from sqlalchemy.engine import CursorResult
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiError
from app.events.outbox import OutboxRepository
from app.moderation.crypto import CommandCipher, EncryptedPayload, content_hash
from app.moderation.models import (
    ContentSubmission,
    ModerationDecisionRecord,
    SubmissionStatus,
)
from app.moderation.schemas import ContentType, ModerationAssessment


@dataclass(frozen=True, slots=True)
class ModerationCommand:
    owner_id: UUID
    content_type: ContentType
    operation: str
    text: str = field(repr=False)
    payload: dict[str, object] = field(repr=False)
    idempotency_key: str
    target_id: UUID | None = None


@dataclass(frozen=True, slots=True)
class DecisionProvenance:
    provider: str
    model: str
    prompt_version: str


LOCAL_RULE_PROVENANCE = DecisionProvenance(
    provider="local-rules",
    model="deterministic",
    prompt_version="v1",
)


class ModerationRepository:
    def __init__(
        self,
        session: AsyncSession,
        cipher: CommandCipher,
        hash_pepper: str,
        *,
        provider: str = "upstage",
        model: str = "solar",
        prompt_version: str = "v1",
    ) -> None:
        self._session = session
        self._cipher = cipher
        self._hash_pepper = hash_pepper
        self._provider = provider
        self._model = model
        self._prompt_version = prompt_version
        self._outbox = OutboxRepository(session)

    async def create_pending(
        self,
        command: ModerationCommand,
        assessment: ModerationAssessment | None = None,
    ) -> ContentSubmission:
        encrypted = self._cipher.encrypt(command.payload)
        first_retry_at = datetime.now(UTC) + timedelta(minutes=1)
        submission = ContentSubmission(
            owner_id=command.owner_id,
            content_type=command.content_type,
            operation=command.operation,
            target_id=command.target_id,
            ciphertext=encrypted.ciphertext,
            nonce=encrypted.nonce,
            content_hash=content_hash(command.text, self._hash_pepper),
            idempotency_key=command.idempotency_key,
            status=SubmissionStatus.PENDING_REVIEW,
            next_attempt_at=first_retry_at,
        )
        self._session.add(submission)
        await self._session.flush()
        if assessment is not None:
            await self.record_decision(submission.id, assessment)
        await self._outbox.add(
            "moderation.retry",
            submission.id,
            {
                "submissionId": str(submission.id),
                "scheduledAt": first_retry_at.isoformat(),
                "attempt": 0,
            },
            available_at=first_retry_at,
        )
        return submission

    async def create_blocked(
        self,
        command: ModerationCommand,
        assessment: ModerationAssessment,
        provenance: DecisionProvenance | None = None,
    ) -> ContentSubmission:
        """Persist a blocked decision without ever encrypting or storing raw content."""

        submission = ContentSubmission(
            owner_id=command.owner_id,
            content_type=command.content_type,
            operation=command.operation,
            target_id=command.target_id,
            ciphertext=None,
            nonce=None,
            content_hash=content_hash(command.text, self._hash_pepper),
            idempotency_key=command.idempotency_key,
            status=SubmissionStatus.BLOCKED,
            resolved_at=datetime.now(UTC),
        )
        self._session.add(submission)
        await self._session.flush()
        await self.record_decision(submission.id, assessment, provenance)
        return submission

    async def record_decision(
        self,
        submission_id: UUID,
        assessment: ModerationAssessment,
        provenance: DecisionProvenance | None = None,
    ) -> ModerationDecisionRecord:
        """Persist one decision, hashing the raw provider request ID exactly once.

        Callers must pass a fresh ``ModerationAssessment`` from the provider boundary,
        never a value copied from an already-persisted decision record.
        """

        exists = await self._session.scalar(
            select(ContentSubmission.id).where(ContentSubmission.id == submission_id)
        )
        if exists is None:
            raise ApiError("RESOURCE_NOT_FOUND", "검열 제출을 찾을 수 없습니다.", 404)
        source = provenance or DecisionProvenance(
            provider=self._provider,
            model=self._model,
            prompt_version=self._prompt_version,
        )
        record = ModerationDecisionRecord(
            submission_id=submission_id,
            decision=assessment.decision,
            categories=sorted(category.value for category in assessment.categories),
            severity=assessment.severity,
            confidence=assessment.confidence,
            reason=assessment.reason,
            provider_request_id=(
                content_hash(assessment.provider_request_id, self._hash_pepper)
                if assessment.provider_request_id is not None
                else None
            ),
            provider=source.provider,
            model=source.model,
            prompt_version=source.prompt_version,
        )
        self._session.add(record)
        return record

    async def mark_allowed(
        self,
        submission_id: UUID,
        resource_id: UUID | None,
        *,
        result_expires_at: datetime | None = None,
    ) -> None:
        values: dict[str, object] = {
            "status": SubmissionStatus.ALLOWED,
            "resolved_resource_id": resource_id,
            "resolved_at": datetime.now(UTC),
            "result_expires_at": result_expires_at,
            "next_attempt_at": None,
        }
        if result_expires_at is None:
            values.update(ciphertext=None, nonce=None)
        await self._transition(submission_id, values)

    async def mark_blocked(self, submission_id: UUID) -> None:
        await self._transition(
            submission_id,
            {
                "status": SubmissionStatus.BLOCKED,
                "ciphertext": None,
                "nonce": None,
                "resolved_at": datetime.now(UTC),
                "next_attempt_at": None,
                "result_expires_at": None,
            },
        )

    async def mark_manual_allowed(
        self,
        submission_id: UUID,
        resource_id: UUID | None,
        *,
        result_expires_at: datetime | None = None,
    ) -> None:
        values: dict[str, object] = {
            "status": SubmissionStatus.ALLOWED,
            "resolved_resource_id": resource_id,
            "resolved_at": datetime.now(UTC),
            "result_expires_at": result_expires_at,
            "next_attempt_at": None,
        }
        if result_expires_at is None:
            values.update(ciphertext=None, nonce=None)
        await self._transition(
            submission_id,
            values,
            expected_statuses=(SubmissionStatus.MANUAL_REVIEW,),
        )

    async def mark_manual_blocked(self, submission_id: UUID) -> None:
        await self._transition(
            submission_id,
            {
                "status": SubmissionStatus.BLOCKED,
                "ciphertext": None,
                "nonce": None,
                "resolved_at": datetime.now(UTC),
                "next_attempt_at": None,
                "result_expires_at": None,
            },
            expected_statuses=(SubmissionStatus.MANUAL_REVIEW,),
        )

    async def resolve_allowed(
        self,
        submission: ContentSubmission,
        resource_id: UUID | None,
        assessment: ModerationAssessment,
        *,
        provenance: DecisionProvenance | None = None,
        expected_status: SubmissionStatus = SubmissionStatus.PENDING_REVIEW,
        result_expires_at: datetime | None = None,
    ) -> bool:
        values: dict[str, object] = {
            "status": SubmissionStatus.ALLOWED,
            "resolved_resource_id": resource_id,
            "resolved_at": datetime.now(UTC),
            "result_expires_at": result_expires_at,
            "next_attempt_at": None,
        }
        if result_expires_at is None:
            values.update(ciphertext=None, nonce=None)
        won = await self._try_transition(
            submission.id,
            values,
            expected_attempt_count=submission.attempt_count,
            expected_statuses=(expected_status,),
        )
        if won:
            await self.record_decision(submission.id, assessment, provenance)
        return won

    async def resolve_blocked(
        self,
        submission: ContentSubmission,
        assessment: ModerationAssessment,
        *,
        provenance: DecisionProvenance | None = None,
        expected_status: SubmissionStatus = SubmissionStatus.PENDING_REVIEW,
    ) -> bool:
        won = await self._try_transition(
            submission.id,
            {
                "status": SubmissionStatus.BLOCKED,
                "ciphertext": None,
                "nonce": None,
                "resolved_at": datetime.now(UTC),
                "next_attempt_at": None,
                "result_expires_at": None,
            },
            expected_attempt_count=submission.attempt_count,
            expected_statuses=(expected_status,),
        )
        if won:
            await self.record_decision(submission.id, assessment, provenance)
        return won

    async def resolve_retry(
        self,
        submission: ContentSubmission,
        *,
        attempt_count: int,
        next_attempt_at: datetime,
        assessment: ModerationAssessment | None = None,
    ) -> bool:
        won = await self._try_transition(
            submission.id,
            {
                "status": SubmissionStatus.PENDING_REVIEW,
                "attempt_count": attempt_count,
                "next_attempt_at": next_attempt_at,
            },
            expected_attempt_count=submission.attempt_count,
        )
        if not won:
            return False
        if assessment is not None:
            await self.record_decision(submission.id, assessment)
        await self._outbox.add(
            "moderation.retry",
            submission.id,
            {
                "submissionId": str(submission.id),
                "scheduledAt": next_attempt_at.isoformat(),
                "attempt": attempt_count,
            },
            available_at=next_attempt_at,
        )
        return True

    async def resolve_manual_review(
        self,
        submission: ContentSubmission,
        *,
        attempt_count: int,
        assessment: ModerationAssessment | None = None,
    ) -> bool:
        won = await self._try_transition(
            submission.id,
            {
                "status": SubmissionStatus.MANUAL_REVIEW,
                "attempt_count": attempt_count,
                "next_attempt_at": None,
            },
            expected_attempt_count=submission.attempt_count,
        )
        if won and assessment is not None:
            await self.record_decision(submission.id, assessment)
        return won

    async def mark_manual_review(self, submission_id: UUID, *, attempt_count: int) -> None:
        await self._transition(
            submission_id,
            {
                "status": SubmissionStatus.MANUAL_REVIEW,
                "attempt_count": attempt_count,
                "next_attempt_at": None,
            },
            expected_attempt_count=attempt_count - 1,
        )

    async def get_owned(self, submission_id: UUID, owner_id: UUID) -> ContentSubmission:
        submission = await self._session.scalar(
            select(ContentSubmission).where(
                ContentSubmission.id == submission_id,
                ContentSubmission.owner_id == owner_id,
            ).execution_options(populate_existing=True)
        )
        if submission is None:
            raise ApiError("RESOURCE_NOT_FOUND", "검토 제출을 찾을 수 없습니다.", 404)
        return submission

    async def get_pending(self, submission_id: UUID) -> ContentSubmission | None:
        return cast(
            ContentSubmission | None,
            await self._session.scalar(
                select(ContentSubmission)
                .where(
                    ContentSubmission.id == submission_id,
                    ContentSubmission.status == SubmissionStatus.PENDING_REVIEW,
                )
                .execution_options(populate_existing=True)
            ),
        )

    async def get(self, submission_id: UUID) -> ContentSubmission | None:
        return cast(
            ContentSubmission | None,
            await self._session.scalar(
                select(ContentSubmission)
                .where(ContentSubmission.id == submission_id)
                .execution_options(populate_existing=True)
            ),
        )

    async def latest_decision(
        self, submission_id: UUID
    ) -> ModerationDecisionRecord | None:
        return cast(
            ModerationDecisionRecord | None,
            await self._session.scalar(
                select(ModerationDecisionRecord)
                .where(ModerationDecisionRecord.submission_id == submission_id)
                .order_by(
                    ModerationDecisionRecord.created_at.desc(),
                    ModerationDecisionRecord.id.desc(),
                )
                .limit(1)
            ),
        )

    async def clear_expired_ocr_results(self, *, now: datetime | None = None) -> int:
        result = cast(
            CursorResult[Any],
            await self._session.execute(
                update(ContentSubmission)
                .where(
                    ContentSubmission.status == SubmissionStatus.ALLOWED,
                    ContentSubmission.content_type == ContentType.OCR_TEXT,
                    ContentSubmission.result_expires_at.is_not(None),
                    ContentSubmission.result_expires_at <= (now or datetime.now(UTC)),
                )
                .values(ciphertext=None, nonce=None, result_expires_at=None)
                .execution_options(synchronize_session=False)
            ),
        )
        return result.rowcount

    async def schedule_retry(
        self,
        submission_id: UUID,
        attempt_count: int,
        next_attempt_at: datetime,
    ) -> None:
        if type(attempt_count) is not int or attempt_count <= 0:
            raise InvalidRetrySchedule()
        try:
            timezone_aware = (
                isinstance(next_attempt_at, datetime)
                and next_attempt_at.tzinfo is not None
                and next_attempt_at.utcoffset() is not None
            )
        except Exception:
            raise InvalidRetrySchedule() from None
        if not timezone_aware:
            raise InvalidRetrySchedule()
        await self._transition(
            submission_id,
            {
                "status": SubmissionStatus.PENDING_REVIEW,
                "attempt_count": attempt_count,
                "next_attempt_at": next_attempt_at,
            },
            expected_attempt_count=attempt_count - 1,
        )
        await self._outbox.add(
            "moderation.retry",
            submission_id,
            {
                "submissionId": str(submission_id),
                "scheduledAt": next_attempt_at.isoformat(),
                "attempt": attempt_count,
            },
            available_at=next_attempt_at,
        )

    def decrypt_command(self, submission: ContentSubmission) -> dict[str, object]:
        if submission.ciphertext is None or submission.nonce is None:
            raise ValueError("submission has no encrypted command")
        return self._cipher.decrypt(
            EncryptedPayload(ciphertext=submission.ciphertext, nonce=submission.nonce)
        )

    async def _try_transition(
        self,
        submission_id: UUID,
        values: dict[str, object],
        *,
        expected_attempt_count: int | None = None,
        expected_statuses: tuple[SubmissionStatus, ...] = (
            SubmissionStatus.PENDING_REVIEW,
        ),
    ) -> bool:
        conditions = [
            ContentSubmission.id == submission_id,
            ContentSubmission.status.in_(expected_statuses),
        ]
        if expected_attempt_count is not None:
            conditions.append(ContentSubmission.attempt_count == expected_attempt_count)
        statement = (
            update(ContentSubmission)
            .where(*conditions)
            .values(**values)
            .execution_options(synchronize_session=False)
        )
        result = cast(CursorResult[Any], await self._session.execute(statement))
        return result.rowcount == 1

    async def _transition(
        self,
        submission_id: UUID,
        values: dict[str, object],
        *,
        expected_attempt_count: int | None = None,
        expected_statuses: tuple[SubmissionStatus, ...] = (
            SubmissionStatus.PENDING_REVIEW,
        ),
    ) -> None:
        conditions = [
            ContentSubmission.id == submission_id,
            ContentSubmission.status.in_(expected_statuses),
        ]
        if expected_attempt_count is not None:
            conditions.append(ContentSubmission.attempt_count == expected_attempt_count)
        statement = (
            update(ContentSubmission)
            .where(*conditions)
            .values(**values)
            .execution_options(synchronize_session=False)
        )
        result = cast(CursorResult[Any], await self._session.execute(statement))
        if result.rowcount != 1:
            raise ApiError("RESOURCE_CONFLICT", "검열 제출 상태가 이미 변경되었습니다.", 409)


class InvalidRetrySchedule(ValueError):
    def __init__(self) -> None:
        super().__init__("invalid moderation retry schedule")
