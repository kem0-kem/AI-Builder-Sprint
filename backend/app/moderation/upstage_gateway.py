import json
import re

import httpx
from pydantic import BaseModel, ConfigDict, Field, SecretStr, ValidationError

from app.moderation.gateway import ModerationProviderUnavailable
from app.moderation.schemas import (
    ContentType,
    ModerationAssessment,
    ProviderModerationAssessment,
)

MODERATION_SYSTEM_PROMPT = """You are a strict content-safety classifier.
Return only a JSON object with exactly these fields: decision, categories, severity,
confidence, and reason. decision must be ALLOW, REVIEW, or BLOCK. categories may only
contain HATE, HARASSMENT, SEXUAL, VIOLENCE, SELF_HARM, PERSONAL_DATA, or SPAM.
severity must be NONE, LOW, MEDIUM, HIGH, or CRITICAL. confidence must be between 0 and 1.
Do not repeat the input text in reason.
"""

_SAFE_REQUEST_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}$")


class _UpstageMessage(BaseModel):
    model_config = ConfigDict(extra="ignore")

    content: str


class _UpstageChoice(BaseModel):
    model_config = ConfigDict(extra="ignore")

    message: _UpstageMessage


class _UpstageChatCompletion(BaseModel):
    model_config = ConfigDict(extra="ignore")

    choices: list[_UpstageChoice] = Field(min_length=1)


class UpstageModerationGateway:
    def __init__(
        self,
        *,
        client: httpx.AsyncClient,
        api_key: SecretStr,
        model: str,
    ) -> None:
        self.client = client
        self.api_key = api_key
        self.model = model

    async def classify(
        self,
        content_type: ContentType,
        text: str,
    ) -> ModerationAssessment:
        request_json = {
            "model": self.model,
            "temperature": 0,
            "messages": [
                {"role": "system", "content": MODERATION_SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": json.dumps(
                        {"type": content_type.value, "text": text},
                        ensure_ascii=False,
                    ),
                },
            ],
        }
        try:
            response = await self.client.post(
                "/chat/completions",
                headers={"Authorization": f"Bearer {self.api_key.get_secret_value()}"},
                json=request_json,
            )
        except (httpx.HTTPError, UnicodeEncodeError):
            raise ModerationProviderUnavailable("moderation provider unavailable") from None

        try:
            response.raise_for_status()
        except httpx.HTTPError:
            raise ModerationProviderUnavailable("moderation provider unavailable") from None

        try:
            completion = _UpstageChatCompletion.model_validate(response.json())
            provider_assessment = ProviderModerationAssessment.model_validate_json(
                completion.choices[0].message.content
            )
        except (json.JSONDecodeError, UnicodeDecodeError, ValidationError):
            raise ModerationProviderUnavailable("moderation provider unavailable") from None

        return ModerationAssessment(
            **provider_assessment.model_dump(),
            provider_request_id=_safe_request_id(
                response.headers.get("x-request-id"), text
            ),
        )


def _safe_request_id(value: str | None, submitted_text: str) -> str | None:
    if value is None or _SAFE_REQUEST_ID.fullmatch(value) is None:
        return None
    if value in submitted_text:
        return None
    return value
