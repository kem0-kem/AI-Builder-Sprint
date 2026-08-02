from collections.abc import AsyncIterator
from typing import Annotated, Any

import httpx
from fastapi import Depends
from fastapi.responses import JSONResponse

from app.auth.dependencies import Session
from app.common.responses import success
from app.core.config import get_settings
from app.core.errors import ApiError
from app.moderation.crypto import CommandCipher
from app.moderation.metrics import moderation_metrics
from app.moderation.models import SubmissionStatus
from app.moderation.repository import ModerationRepository
from app.moderation.service import (
    ModerationOrchestrator,
    ModerationOutcome,
    RecordingModerationOrchestrator,
    ShadowModerationOrchestrator,
)
from app.moderation.upstage_gateway import UpstageModerationGateway

MODERATION_RESPONSES: dict[int | str, dict[str, Any]] = {
    202: {"description": "Content accepted for moderation review"},
    422: {"description": "Content blocked by policy"},
}


async def get_moderation_orchestrator(
    session: Session,
) -> AsyncIterator[
    ModerationOrchestrator
    | RecordingModerationOrchestrator
    | ShadowModerationOrchestrator
    | None
]:
    settings = get_settings()
    required = (
        settings.upstage_api_key,
        settings.upstage_chat_model,
        settings.moderation_allow_confidence,
        settings.moderation_block_confidence,
    )
    if any(value is None for value in required):
        yield None
        return

    assert settings.upstage_api_key is not None
    assert settings.upstage_chat_model is not None
    assert settings.moderation_allow_confidence is not None
    assert settings.moderation_block_confidence is not None
    async with httpx.AsyncClient(
        base_url=str(settings.upstage_base_url), timeout=10.0
    ) as client:
        gateway = UpstageModerationGateway(
            client=client,
            api_key=settings.upstage_api_key,
            model=settings.upstage_chat_model,
        )
        if settings.moderation_mode == "shadow":
            yield ShadowModerationOrchestrator(gateway, moderation_metrics)
            return

        if (
            settings.moderation_encryption_key is None
            or settings.content_hash_pepper is None
        ):
            yield None
            return
        repository = ModerationRepository(
            session,
            CommandCipher(settings.moderation_encryption_key.get_secret_value()),
            settings.content_hash_pepper.get_secret_value(),
            model=settings.upstage_chat_model,
        )
        inner = ModerationOrchestrator(
            gateway,
            repository,
            settings.moderation_allow_confidence,
            settings.moderation_block_confidence,
        )
        yield RecordingModerationOrchestrator(inner, moderation_metrics, shadow=False)


Moderation = Annotated[
    ModerationOrchestrator
    | RecordingModerationOrchestrator
    | ShadowModerationOrchestrator
    | None,
    Depends(get_moderation_orchestrator),
]


async def moderation_response(
    outcome: ModerationOutcome, session: Session
) -> JSONResponse | None:
    if outcome.status is SubmissionStatus.PENDING_REVIEW:
        await session.commit()
        return JSONResponse(
            status_code=202,
            content=success(
                {
                    "moderationStatus": outcome.status.value,
                    "submissionId": str(outcome.submission_id),
                }
            ),
        )
    if outcome.status is SubmissionStatus.BLOCKED:
        await session.commit()
        raise ApiError(
            "CONTENT_POLICY_VIOLATION",
            "콘텐츠 정책을 확인해 주세요.",
            422,
            {"categories": sorted(category.value for category in outcome.categories)},
        )
    return None
