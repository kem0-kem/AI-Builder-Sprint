from collections.abc import AsyncIterator
from typing import Annotated

import httpx
from fastapi import Depends

from app.auth.dependencies import Session
from app.core.config import get_settings
from app.moderation.crypto import CommandCipher
from app.moderation.repository import ModerationRepository
from app.moderation.service import ModerationOrchestrator
from app.moderation.upstage_gateway import UpstageModerationGateway


async def get_moderation_orchestrator(
    session: Session,
) -> AsyncIterator[ModerationOrchestrator | None]:
    settings = get_settings()
    if settings.moderation_mode != "enforce":
        yield None
        return

    assert settings.upstage_api_key is not None
    assert settings.upstage_chat_model is not None
    assert settings.moderation_encryption_key is not None
    assert settings.content_hash_pepper is not None
    assert settings.moderation_allow_confidence is not None
    assert settings.moderation_block_confidence is not None
    repository = ModerationRepository(
        session,
        CommandCipher(settings.moderation_encryption_key.get_secret_value()),
        settings.content_hash_pepper.get_secret_value(),
        model=settings.upstage_chat_model,
    )
    async with httpx.AsyncClient(
        base_url=str(settings.upstage_base_url), timeout=10.0
    ) as client:
        yield ModerationOrchestrator(
            UpstageModerationGateway(
                client=client,
                api_key=settings.upstage_api_key,
                model=settings.upstage_chat_model,
            ),
            repository,
            settings.moderation_allow_confidence,
            settings.moderation_block_confidence,
        )


Moderation = Annotated[
    ModerationOrchestrator | None, Depends(get_moderation_orchestrator)
]
