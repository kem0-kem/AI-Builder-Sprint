import hashlib
import secrets
from datetime import UTC, datetime, timedelta
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header
from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.auth.dependencies import CurrentUserId, Session
from app.chat.service import ChatCommandHandler
from app.common.responses import success
from app.core.config import get_settings
from app.core.errors import ApiError
from app.feeds.service import FeedCommandHandler
from app.letters.service import LetterCommandHandler
from app.matching.dependencies import get_matching_service
from app.matching.service import MatchingService
from app.moderation.command_handlers import ModeratedCommandRegistry
from app.moderation.crypto import CommandCipher
from app.moderation.models import ContentSubmission, SubmissionStatus
from app.moderation.repository import DecisionProvenance, ModerationRepository
from app.moderation.schemas import (
    ContentType,
    ModerationAssessment,
    ModerationCategory,
    ModerationDecision,
    Severity,
)
from app.reports.service import ReportCommandHandler

router = APIRouter(tags=["moderation"])


class ManualDecisionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    decision: ModerationDecision
    categories: set[ModerationCategory] = Field(default_factory=set)

    @model_validator(mode="after")
    def validate_manual_decision(self) -> "ManualDecisionRequest":
        if self.decision is ModerationDecision.REVIEW:
            raise ValueError("manual decision must resolve to ALLOW or BLOCK")
        if self.decision is ModerationDecision.ALLOW and self.categories:
            raise ValueError("ALLOW decision cannot have categories")
        if self.decision is ModerationDecision.BLOCK and not self.categories:
            raise ValueError("BLOCK decision requires public categories")
        return self


def get_command_registry(
    session: Session,
    matching: Annotated[MatchingService | None, Depends(get_matching_service)] = None,
) -> ModeratedCommandRegistry:
    registry = ModeratedCommandRegistry()

    async def create_letter(
        owner_id: UUID, command: dict[str, object], key: str
    ) -> UUID:
        handler = (
            LetterCommandHandler(session, matching)
            if isinstance(matching, MatchingService)
            else LetterCommandHandler(session)
        )
        return (await handler.execute(owner_id, command, key)).resource_id

    async def create_chat_message(
        owner_id: UUID, command: dict[str, object], key: str
    ) -> UUID:
        return (await ChatCommandHandler(session).execute(owner_id, command, key)).resource_id

    async def create_feed(owner_id: UUID, command: dict[str, object], key: str) -> UUID:
        return (await FeedCommandHandler(session).create_feed(owner_id, command, key)).resource_id

    async def patch_feed(owner_id: UUID, command: dict[str, object], key: str) -> UUID:
        return (await FeedCommandHandler(session).patch_feed(owner_id, command, key)).resource_id

    async def create_comment(
        owner_id: UUID, command: dict[str, object], key: str
    ) -> UUID:
        return (
            await FeedCommandHandler(session).create_comment(owner_id, command, key)
        ).resource_id

    async def patch_comment(
        owner_id: UUID, command: dict[str, object], key: str
    ) -> UUID:
        return (
            await FeedCommandHandler(session).patch_comment(owner_id, command, key)
        ).resource_id

    async def create_report(
        owner_id: UUID, command: dict[str, object], key: str
    ) -> UUID:
        return (
            await ReportCommandHandler(session).create_report(owner_id, command, key)
        ).resource_id

    registry.register("CREATE_LETTER", create_letter)
    registry.register("CREATE_CHAT_MESSAGE", create_chat_message)
    registry.register("CREATE_FEED", create_feed)
    registry.register("PATCH_FEED", patch_feed)
    registry.register("CREATE_COMMENT", create_comment)
    registry.register("PATCH_COMMENT", patch_comment)
    registry.register("CREATE_REPORT", create_report)
    return registry


def get_moderation_repository(session: Session) -> ModerationRepository:
    settings = get_settings()
    encryption = settings.moderation_encryption_key
    pepper = settings.content_hash_pepper
    if encryption is None or pepper is None:
        raise ApiError(
            "MODERATION_STORAGE_UNAVAILABLE",
            "검토 저장소를 사용할 수 없습니다.",
            503,
        )
    return ModerationRepository(
        session,
        CommandCipher(encryption.get_secret_value()),
        pepper.get_secret_value(),
        model=settings.upstage_chat_model or "solar",
    )


Repository = Annotated[ModerationRepository, Depends(get_moderation_repository)]
Registry = Annotated[ModeratedCommandRegistry, Depends(get_command_registry)]


def require_internal_token(
    internal_token: Annotated[str | None, Header(alias="X-Internal-Token")] = None,
) -> None:
    configured_secret = get_settings().internal_moderation_token
    configured = configured_secret.get_secret_value() if configured_secret else ""
    candidate = internal_token or ""
    if (
        not configured.strip()
        or len(configured.encode("utf-8")) < 32
        or not secrets.compare_digest(
            hashlib.sha256(candidate.encode("utf-8")).digest(),
            hashlib.sha256(configured.encode("utf-8")).digest(),
        )
    ):
        raise ApiError("RESOURCE_NOT_FOUND", "리소스를 찾을 수 없습니다.", 404)


@router.get("/moderation-submissions/{submission_id}")
async def get_submission(
    submission_id: UUID,
    user_id: CurrentUserId,
    repository: Repository,
) -> dict[str, object]:
    submission = await repository.get_owned(submission_id, user_id)
    decision = await repository.latest_decision(submission.id)
    categories: list[str] = []
    if submission.status is SubmissionStatus.BLOCKED and decision is not None:
        categories = list(decision.categories)

    result: dict[str, str] | None = None
    if _available_ocr_result(submission):
        payload = repository.decrypt_command(submission)
        if set(payload) == {"text"} and isinstance(payload["text"], str):
            result = {"text": payload["text"]}

    return success(
        {
            "submissionId": str(submission.id),
            "status": submission.status.value,
            "resourceId": (
                str(submission.resolved_resource_id)
                if submission.resolved_resource_id is not None
                else None
            ),
            "result": result,
            "categories": categories,
        }
    )


@router.post(
    "/internal/moderation-submissions/{submission_id}/decision",
    dependencies=[Depends(require_internal_token)],
)
async def decide_submission(
    submission_id: UUID,
    request: ManualDecisionRequest,
    session: Session,
    repository: Repository,
    registry: Registry,
) -> dict[str, object]:
    submission = await repository.get(submission_id)
    if submission is None:
        raise ApiError("RESOURCE_NOT_FOUND", "검토 제출을 찾을 수 없습니다.", 404)
    if submission.status not in (
        SubmissionStatus.PENDING_REVIEW,
        SubmissionStatus.MANUAL_REVIEW,
    ):
        return success(_internal_result(submission))
    is_manual = submission.status is SubmissionStatus.MANUAL_REVIEW

    assessment = ModerationAssessment(
        decision=request.decision,
        categories=request.categories,
        severity=(
            Severity.NONE
            if request.decision is ModerationDecision.ALLOW
            else Severity.HIGH
        ),
        confidence=1.0,
        reason=(
            "MANUAL_ALLOW"
            if request.decision is ModerationDecision.ALLOW
            else "MANUAL_BLOCK"
        ),
    )
    if request.decision is ModerationDecision.BLOCK:
        transitioned = await _block_submission(
            repository, submission, is_manual=is_manual
        )
    else:
        transitioned = await _allow_submission(
            repository, registry, submission, is_manual=is_manual
        )
    if transitioned:
        await repository.record_decision(
            submission.id,
            assessment,
            DecisionProvenance(
                provider="internal",
                model="manual",
                prompt_version="v1",
            ),
        )
    await session.commit()
    refreshed = await repository.get(submission.id)
    if refreshed is None:
        raise ApiError("RESOURCE_NOT_FOUND", "검토 제출을 찾을 수 없습니다.", 404)
    return success(_internal_result(refreshed))


async def _allow_submission(
    repository: ModerationRepository,
    registry: ModeratedCommandRegistry,
    submission: ContentSubmission,
    *,
    is_manual: bool,
) -> bool:
    command = repository.decrypt_command(submission)
    if submission.content_type is ContentType.OCR_TEXT:
        if set(command) != {"text"} or not isinstance(command.get("text"), str):
            raise ApiError("RESOURCE_CONFLICT", "OCR 결과 형식이 올바르지 않습니다.", 409)
        try:
            method = (
                repository.mark_manual_allowed
                if is_manual
                else repository.mark_allowed
            )
            await method(
                submission.id,
                None,
                result_expires_at=datetime.now(UTC) + timedelta(hours=24),
            )
            return True
        except ApiError as exc:
            return await _allow_race_result(repository, submission, None, exc)
    resource_id = await registry.execute(
        submission.operation,
        command,
        submission.idempotency_key,
        owner_id=submission.owner_id,
    )
    try:
        method = (
            repository.mark_manual_allowed if is_manual else repository.mark_allowed
        )
        await method(submission.id, resource_id)
        return True
    except ApiError as exc:
        return await _allow_race_result(repository, submission, resource_id, exc)


async def _allow_race_result(
    repository: ModerationRepository,
    submission: ContentSubmission,
    resource_id: UUID | None,
    error: ApiError,
) -> bool:
    if error.code != "RESOURCE_CONFLICT":
        raise error
    current = await repository.get(submission.id)
    if (
        current is None
        or current.status is not SubmissionStatus.ALLOWED
        or current.resolved_resource_id != resource_id
    ):
        raise error
    return False


async def _block_submission(
    repository: ModerationRepository,
    submission: ContentSubmission,
    *,
    is_manual: bool,
) -> bool:
    try:
        if is_manual:
            await repository.mark_manual_blocked(submission.id)
        else:
            await repository.mark_blocked(submission.id)
        return True
    except ApiError as exc:
        if exc.code != "RESOURCE_CONFLICT":
            raise
        current = await repository.get(submission.id)
        if current is None or current.status is not SubmissionStatus.BLOCKED:
            raise
        return False


def _available_ocr_result(submission: ContentSubmission) -> bool:
    expiry = submission.result_expires_at
    if expiry is not None and expiry.tzinfo is None:
        expiry = expiry.replace(tzinfo=UTC)
    return bool(
        submission.status is SubmissionStatus.ALLOWED
        and submission.content_type is ContentType.OCR_TEXT
        and submission.ciphertext is not None
        and submission.nonce is not None
        and expiry is not None
        and expiry > datetime.now(UTC)
    )


def _internal_result(submission: ContentSubmission) -> dict[str, object]:
    return {
        "submissionId": str(submission.id),
        "status": submission.status.value,
        "resourceId": (
            str(submission.resolved_resource_id)
            if submission.resolved_resource_id is not None
            else None
        ),
    }
