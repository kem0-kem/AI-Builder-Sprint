from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, cast
from uuid import UUID

from sqlalchemy import select, update
from sqlalchemy.engine import CursorResult
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiError
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

    async def create_pending(
        self,
        command: ModerationCommand,
        assessment: ModerationAssessment | None = None,
    ) -> ContentSubmission:
        encrypted = self._cipher.encrypt(command.payload)
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
        )
        self._session.add(submission)
        await self._session.flush()
        if assessment is not None:
            await self.record_decision(submission.id, assessment)
        return submission

    async def record_decision(
        self,
        submission_id: UUID,
        assessment: ModerationAssessment,
    ) -> ModerationDecisionRecord:
        exists = await self._session.scalar(
            select(ContentSubmission.id).where(ContentSubmission.id == submission_id)
        )
        if exists is None:
            raise ApiError("RESOURCE_NOT_FOUND", "검열 제출을 찾을 수 없습니다.", 404)
        record = ModerationDecisionRecord(
            submission_id=submission_id,
            decision=assessment.decision,
            categories=sorted(category.value for category in assessment.categories),
            severity=assessment.severity,
            confidence=assessment.confidence,
            reason=assessment.reason,
            provider_request_id=assessment.provider_request_id,
            provider=self._provider,
            model=self._model,
            prompt_version=self._prompt_version,
        )
        self._session.add(record)
        return record

    async def mark_allowed(self, submission_id: UUID, resource_id: UUID | None) -> None:
        await self._transition(
            submission_id,
            status=SubmissionStatus.ALLOWED,
            resolved_resource_id=resource_id,
            resolved_at=datetime.now(UTC),
            next_attempt_at=None,
        )

    async def mark_blocked(self, submission_id: UUID) -> None:
        await self._transition(
            submission_id,
            status=SubmissionStatus.BLOCKED,
            ciphertext=None,
            nonce=None,
            resolved_at=datetime.now(UTC),
            next_attempt_at=None,
        )

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
            status=SubmissionStatus.PENDING_REVIEW,
            expected_attempt_count=attempt_count - 1,
            attempt_count=attempt_count,
            next_attempt_at=next_attempt_at,
        )

    def decrypt_command(self, submission: ContentSubmission) -> dict[str, object]:
        if submission.ciphertext is None or submission.nonce is None:
            raise ValueError("submission has no encrypted command")
        return self._cipher.decrypt(
            EncryptedPayload(ciphertext=submission.ciphertext, nonce=submission.nonce)
        )

    async def _transition(
        self,
        submission_id: UUID,
        expected_attempt_count: int | None = None,
        **values: object,
    ) -> None:
        conditions = [
            ContentSubmission.id == submission_id,
            ContentSubmission.status == SubmissionStatus.PENDING_REVIEW,
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
