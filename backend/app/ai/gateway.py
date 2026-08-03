from collections.abc import AsyncIterator
from enum import StrEnum
from html.parser import HTMLParser
from typing import Protocol

import httpx
from pydantic import BaseModel

from app.core.config import get_settings


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


class UpstageWritingAssistant:
    def __init__(
        self,
        *,
        client: httpx.AsyncClient,
        api_key: str,
        document_model: str,
        feedback_fallback: WritingAssistantGateway | None = None,
    ) -> None:
        self.client = client
        self.api_key = api_key
        self.document_model = document_model
        self.feedback_fallback = feedback_fallback or LocalWritingAssistant()

    async def ocr(self, image_bytes: bytes, mime_type: str) -> str:
        extension = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}.get(
            mime_type, "bin"
        )
        try:
            response = await self.client.post(
                "/document-digitization",
                headers={"Authorization": f"Bearer {self.api_key}"},
                files={"document": (f"letter.{extension}", image_bytes, mime_type)},
                data={"ocr": "force", "model": self.document_model},
            )
            response.raise_for_status()
            payload = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise TimeoutError("Upstage OCR request failed") from exc

        text = _document_text(payload)
        if not text:
            raise TimeoutError("Upstage OCR returned no text")
        return text

    async def feedback(
        self, context: WritingContext, title: str | None, content: str
    ) -> WritingFeedback:
        return await self.feedback_fallback.feedback(context, title, content)


def _document_text(payload: object) -> str:
    if not isinstance(payload, dict):
        return ""

    direct = payload.get("text")
    if isinstance(direct, str) and direct.strip():
        return direct.strip()

    content = payload.get("content")
    if isinstance(content, str) and content.strip():
        return content.strip()
    if isinstance(content, dict):
        for key in ("text", "markdown"):
            value = content.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        html = content.get("html")
        if isinstance(html, str) and html.strip():
            return _html_to_text(html)

    elements = payload.get("elements")
    if isinstance(elements, list):
        chunks = [_document_text(element) for element in elements]
        return "\n".join(chunk for chunk in chunks if chunk).strip()
    return ""


class _DocumentHtmlParser(HTMLParser):
    _BLOCK_TAGS = {"br", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "p", "tr"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in self._BLOCK_TAGS:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in self._BLOCK_TAGS:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        self.parts.append(data)


def _html_to_text(value: str) -> str:
    parser = _DocumentHtmlParser()
    parser.feed(value)
    lines = [" ".join(line.split()) for line in "".join(parser.parts).splitlines()]
    return "\n".join(line for line in lines if line).strip()


async def get_writing_assistant() -> AsyncIterator[WritingAssistantGateway]:
    settings = get_settings()
    if settings.upstage_api_key is None:
        yield LocalWritingAssistant()
        return

    async with httpx.AsyncClient(
        base_url=str(settings.upstage_base_url), timeout=30.0
    ) as client:
        yield UpstageWritingAssistant(
            client=client,
            api_key=settings.upstage_api_key.get_secret_value(),
            document_model=settings.upstage_document_model,
        )
