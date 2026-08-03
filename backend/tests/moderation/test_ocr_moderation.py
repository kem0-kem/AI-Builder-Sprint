from uuid import uuid4

from app.ai.gateway import get_writing_assistant
from app.moderation.dependencies import get_moderation_orchestrator
from app.moderation.schemas import (
    ModerationAssessment,
    ModerationCategory,
    ModerationDecision,
    Severity,
)
from app.moderation.service import ModerationOutcome
from tests.letters.test_letter_delivery import register


class StubAssistant:
    async def ocr(self, _image_bytes: bytes, _mime_type: str) -> str:
        return "extracted private text"


class StubOcrModeration:
    def __init__(self, outcome: ModerationOutcome) -> None:
        self.outcome = outcome
        self.texts: list[str] = []

    async def evaluate_ocr(self, _owner_id, text: str) -> ModerationOutcome:
        self.texts.append(text)
        return self.outcome


def set_dependencies(client, outcome: ModerationOutcome) -> StubOcrModeration:
    app = client._transport.app
    stub = StubOcrModeration(outcome)
    app.dependency_overrides[get_writing_assistant] = lambda: StubAssistant()
    app.dependency_overrides[get_moderation_orchestrator] = lambda: stub
    return stub


async def post_ocr(client, headers, path: str):
    return await client.post(
        path,
        headers=headers,
        files={"image": ("scan.png", b"\x89PNG\r\n\x1a\ncontent", "image/png")},
    )


async def test_allowed_ocr_returns_text(client) -> None:
    headers = await register(client, "ocr-allow@example.com", "OCR Allow")
    stub = set_dependencies(
        client,
        ModerationOutcome.immediate(
            ModerationAssessment(
                decision=ModerationDecision.ALLOW,
                categories=set(),
                severity=Severity.NONE,
                confidence=1.0,
                reason="safe",
            )
        ),
    )

    response = await post_ocr(client, headers, "/api/v1/letters/ocr")

    assert response.status_code == 200
    assert response.json()["data"]["text"] == "extracted private text"
    assert stub.texts == ["extracted private text"]


async def test_pending_ocr_hides_text(client) -> None:
    headers = await register(client, "ocr-pending@example.com", "OCR Pending")
    set_dependencies(client, ModerationOutcome.pending(uuid4()))

    response = await post_ocr(client, headers, "/api/v1/reports/ocr")

    assert response.status_code == 202
    assert "text" not in response.text
    assert response.json()["data"]["moderationStatus"] == "PENDING_REVIEW"


async def test_blocked_ocr_hides_text(client) -> None:
    headers = await register(client, "ocr-blocked@example.com", "OCR Blocked")
    set_dependencies(
        client, ModerationOutcome.blocked({ModerationCategory.HARASSMENT})
    )

    response = await post_ocr(client, headers, "/api/v1/letters/ocr")

    assert response.status_code == 422
    assert "extracted private text" not in response.text


async def test_openapi_exposes_only_letter_and_report_ocr(client) -> None:
    schema = (await client.get("/openapi.json")).json()
    paths = schema["paths"]

    assert "/api/v1/letters/ocr" in paths
    assert "/api/v1/reports/ocr" in paths
    assert "/api/v1/feeds/ocr" not in paths
