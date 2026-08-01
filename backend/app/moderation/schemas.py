from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field, model_validator


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
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    decision: ModerationDecision
    categories: set[ModerationCategory]
    severity: Severity
    confidence: float = Field(strict=True, ge=0, le=1)
    reason: str = Field(max_length=300, repr=False)
    provider_request_id: str | None = Field(
        default=None,
        max_length=255,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}$",
        repr=False,
    )

    @model_validator(mode="after")
    def validate_decision_shape(self) -> "ModerationAssessment":
        if self.decision is ModerationDecision.ALLOW and (
            self.categories or self.severity is not Severity.NONE
        ):
            raise ValueError("ALLOW assessment must have no categories and NONE severity")
        if self.decision is not ModerationDecision.ALLOW and (
            not self.categories or self.severity is Severity.NONE
        ):
            raise ValueError(
                "non-ALLOW assessment must have categories and non-NONE severity"
            )
        return self


class ProviderModerationAssessment(BaseModel):
    """The exact JSON object accepted from the moderation model."""

    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    decision: ModerationDecision
    categories: set[ModerationCategory]
    severity: Severity
    confidence: float = Field(strict=True, ge=0, le=1)
    reason: str = Field(max_length=300, repr=False)

    @model_validator(mode="after")
    def validate_decision_shape(self) -> "ProviderModerationAssessment":
        if self.decision is ModerationDecision.ALLOW and (
            self.categories or self.severity is not Severity.NONE
        ):
            raise ValueError("ALLOW assessment must have no categories and NONE severity")
        if self.decision is not ModerationDecision.ALLOW and (
            not self.categories or self.severity is Severity.NONE
        ):
            raise ValueError(
                "non-ALLOW assessment must have categories and non-NONE severity"
            )
        return self
