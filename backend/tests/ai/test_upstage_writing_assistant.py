import json

import httpx
import pytest

from app.ai.gateway import UpstageWritingAssistant, WritingContext


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


@pytest.mark.asyncio
async def test_feedback_sends_draft_to_solar_and_returns_structured_feedback() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/chat/completions"
        assert request.headers["Authorization"] == "Bearer test-key"
        payload = json.loads((await request.aread()).decode("utf-8"))
        assert payload["model"] == "solar-pro"
        assert payload["temperature"] == 0.3
        submitted = json.loads(payload["messages"][1]["content"])
        assert submitted == {
            "context": "LETTER",
            "title": None,
            "draft": "비가 그친 뒤 골목의 풀 냄새가 좋았다.",
        }
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "summary": "비가 갠 골목에서 발견한 산뜻함이 중심인 편지예요.",
                                    "suggestions": [
                                        "풀 냄새를 맡았을 때 떠오른 감정을 한 문장 덧붙여 보세요.",
                                        "비가 오기 전과 후의 골목 모습을 대비해 보세요.",
                                    ],
                                },
                                ensure_ascii=False,
                            )
                        }
                    }
                ]
            },
        )

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler), base_url="https://api.upstage.ai/v1"
    ) as client:
        assistant = UpstageWritingAssistant(
            client=client,
            api_key="test-key",
            document_model="document-parse",
            chat_model="solar-pro",
        )
        result = await assistant.feedback(
            WritingContext.LETTER,
            None,
            "비가 그친 뒤 골목의 풀 냄새가 좋았다.",
        )

    assert "골목" in result.summary
    assert len(result.suggestions) == 2


@pytest.mark.asyncio
async def test_feedback_varies_with_the_submitted_draft() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads((await request.aread()).decode("utf-8"))
        draft = json.loads(payload["messages"][1]["content"])["draft"]
        topic = "산책" if "산책" in draft else "요리"
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "summary": f"{topic}에서 찾은 기쁨이 잘 드러나요.",
                                    "suggestions": [f"{topic}의 구체적인 장면을 더 적어 보세요."],
                                },
                                ensure_ascii=False,
                            )
                        }
                    }
                ]
            },
        )

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler), base_url="https://api.upstage.ai/v1"
    ) as client:
        assistant = UpstageWritingAssistant(
            client=client,
            api_key="test-key",
            document_model="document-parse",
            chat_model="solar-pro",
        )
        walking = await assistant.feedback(
            WritingContext.LETTER, None, "강변을 산책하니 마음이 가벼워졌다."
        )
        cooking = await assistant.feedback(
            WritingContext.LETTER, None, "가족과 함께 요리해서 즐거웠다."
        )

    assert walking.summary != cooking.summary
    assert walking.suggestions != cooking.suggestions


@pytest.mark.asyncio
async def test_feedback_retries_once_after_malformed_model_output() -> None:
    calls = 0

    async def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(200, json={"choices": []})
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "summary": "두 번째 시도에서 본문의 산책 장면을 분석했어요.",
                                    "suggestions": ["산책 중 들었던 소리를 한 가지 덧붙여 보세요."],
                                },
                                ensure_ascii=False,
                            )
                        }
                    }
                ]
            },
        )

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler), base_url="https://api.upstage.ai/v1"
    ) as client:
        assistant = UpstageWritingAssistant(
            client=client,
            api_key="test-key",
            document_model="document-parse",
            chat_model="solar-pro",
        )
        result = await assistant.feedback(WritingContext.LETTER, None, "저녁에 천천히 산책했다.")

    assert calls == 2
    assert "산책" in result.summary


@pytest.mark.asyncio
async def test_feedback_retries_once_when_model_responds_in_english() -> None:
    calls = 0

    async def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        feedback = (
            {
                "summary": "The draft describes a peaceful walk.",
                "suggestions": ["Add one concrete sound from the walk."],
            }
            if calls == 1
            else {
                "summary": "조용한 산책에서 느낀 평온함이 중심인 글이에요.",
                "suggestions": ["산책 중 들었던 소리를 한 가지 덧붙여 보세요."],
            }
        )
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps(feedback, ensure_ascii=False)}}]},
        )

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler), base_url="https://api.upstage.ai/v1"
    ) as client:
        assistant = UpstageWritingAssistant(
            client=client,
            api_key="test-key",
            document_model="document-parse",
            chat_model="solar-pro",
        )
        result = await assistant.feedback(
            WritingContext.LETTER, None, "A quiet walk made me feel peaceful."
        )

    assert calls == 2
    assert "평온함" in result.summary


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(503, text="private-provider-response"),
        httpx.Response(200, content=b"not-json"),
        httpx.Response(200, json={"choices": []}),
        httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {"summary": "분석", "suggestions": []},
                                ensure_ascii=False,
                            )
                        }
                    }
                ]
            },
        ),
    ],
)
async def test_feedback_maps_provider_failures_without_leaking_content(
    response: httpx.Response,
) -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return response

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler), base_url="https://api.upstage.ai/v1"
    ) as client:
        assistant = UpstageWritingAssistant(
            client=client,
            api_key="test-key",
            document_model="document-parse",
            chat_model="solar-pro",
        )
        with pytest.raises(TimeoutError) as caught:
            await assistant.feedback(WritingContext.LETTER, None, "private-draft-marker")

    rendered = str(caught.value) + repr(caught.value)
    assert "private-draft-marker" not in rendered
    assert "private-provider-response" not in rendered


@pytest.mark.asyncio
async def test_feedback_uses_local_fallback_only_without_chat_model() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("Solar must not be called without a configured chat model")

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler), base_url="https://api.upstage.ai/v1"
    ) as client:
        assistant = UpstageWritingAssistant(
            client=client,
            api_key="test-key",
            document_model="document-parse",
        )
        result = await assistant.feedback(WritingContext.LETTER, None, "개발 환경 편지")

    assert result.summary == "편지의 흐름이 자연스럽습니다."
