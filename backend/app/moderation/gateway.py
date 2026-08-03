from typing import Protocol

from app.moderation.schemas import ContentType, ModerationAssessment


class ModerationProviderUnavailable(RuntimeError):
    """Raised when a provider response cannot be trusted or obtained."""


class ModerationGateway(Protocol):
    async def classify(
        self,
        content_type: ContentType,
        text: str,
    ) -> ModerationAssessment:
        raise NotImplementedError
