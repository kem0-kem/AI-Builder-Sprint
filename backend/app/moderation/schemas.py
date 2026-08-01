from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field


class ContentType(StrEnum):
    LETTER = "LETTER"
    CHAT_MESSAGE = "CHAT_MESSAGE"
    FEED = "FEED"
    COMMENT = "COMMENT"
    REPORT = "REPORT"
    OCR_TEXT = "OCR_TEXT"


class ModerationDecision(StrEnum):
    ALLOW = "ALLOW"
    REVIEW = "REVIEW"
    BLOCK = "BLOCK"


class ModerationCategory(StrEnum):
    HATE = "HATE"
    HARASSMENT = "HARASSMENT"
    SEXUAL = "SEXUAL"
    VIOLENCE = "VIOLENCE"
    SELF_HARM = "SELF_HARM"
    PERSONAL_DATA = "PERSONAL_DATA"
    SPAM = "SPAM"


class Severity(StrEnum):
    NONE = "NONE"
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class ModerationAssessment(BaseModel):
    model_config = ConfigDict(extra="forbid")

    decision: ModerationDecision
    categories: set[ModerationCategory]
    severity: Severity
    confidence: float = Field(strict=True, ge=0, le=1)
    reason: str = Field(max_length=300)
    provider_request_id: str | None = None


class ProviderModerationAssessment(BaseModel):
    """The exact JSON object accepted from the moderation model."""

    model_config = ConfigDict(extra="forbid")

    decision: ModerationDecision
    categories: set[ModerationCategory]
    severity: Severity
    confidence: float = Field(strict=True, ge=0, le=1)
    reason: str = Field(max_length=300)
