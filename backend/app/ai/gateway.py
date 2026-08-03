from enum import StrEnum
from typing import Protocol

from pydantic import BaseModel


class WritingContext(StrEnum):
    LETTER = "LETTER"
    FEED = "FEED"
    REPORT = "REPORT"


class WritingFeedback(BaseModel):
    summary: str
    suggestions: list[str]


class WritingAssistantGateway(Protocol):
    async def ocr(self, image_bytes: bytes, mime_type: str) -> str:
        raise NotImplementedError

    async def feedback(
        self, context: WritingContext, title: str | None, content: str
    ) -> WritingFeedback:
        raise NotImplementedError


class LocalWritingAssistant:
    """Safe development fallback; replace through the dependency in production."""

    async def ocr(self, image_bytes: bytes, mime_type: str) -> str:
        return "이미지 텍스트 추출 결과"

    async def feedback(
        self, context: WritingContext, title: str | None, content: str
    ) -> WritingFeedback:
        label = {"LETTER": "편지", "FEED": "피드", "REPORT": "회고"}[context.value]
        suggestions = ["핵심 감정을 한 문장 더 구체적으로 표현해 보세요."]
        if title is not None and len(title.strip()) < 4:
            suggestions.append("제목에 글의 핵심 장면을 담아 보세요.")
        return WritingFeedback(summary=f"{label}의 흐름이 자연스럽습니다.", suggestions=suggestions)


def get_writing_assistant() -> WritingAssistantGateway:
    return LocalWritingAssistant()
