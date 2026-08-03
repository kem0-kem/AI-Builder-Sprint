import httpx
import pytest

from app.ai.gateway import UpstageWritingAssistant


@pytest.mark.asyncio
async def test_ocr_sends_exact_image_type_and_returns_document_text() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer test-key"
        body = await request.aread()
        assert b'name="document"; filename="letter.png"' in body
        assert b"Content-Type: image/png" in body
        assert b'name="ocr"' in body and b"force" in body
        assert b'name="model"' in body and b"document-parse" in body
        return httpx.Response(200, json={"content": {"text": "안녕하세요\n반갑습니다"}})

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler), base_url="https://api.upstage.ai/v1"
    ) as client:
        assistant = UpstageWritingAssistant(
            client=client,
            api_key="test-key",
            document_model="document-parse",
        )
        text = await assistant.ocr(b"\x89PNG\r\n\x1a\ncontent", "image/png")

    assert text == "안녕하세요\n반갑습니다"


@pytest.mark.asyncio
async def test_ocr_extracts_plain_text_from_document_html() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"content": {"html": "<p>첫 번째 문장</p><p>두 번째 문장</p>"}},
        )

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler), base_url="https://api.upstage.ai/v1"
    ) as client:
        assistant = UpstageWritingAssistant(
            client=client,
            api_key="test-key",
            document_model="document-parse",
        )
        text = await assistant.ocr(b"\xff\xd8\xffcontent", "image/jpeg")

    assert text == "첫 번째 문장\n두 번째 문장"


@pytest.mark.asyncio
async def test_ocr_maps_provider_errors_without_leaking_response() -> None:
    marker = "private-provider-response"

    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text=marker)

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler), base_url="https://api.upstage.ai/v1"
    ) as client:
        assistant = UpstageWritingAssistant(
            client=client,
            api_key="test-key",
            document_model="document-parse",
        )
        with pytest.raises(TimeoutError) as caught:
            await assistant.ocr(b"\xff\xd8\xffcontent", "image/jpeg")

    assert marker not in str(caught.value)
