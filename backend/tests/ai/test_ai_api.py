from httpx import AsyncClient

from tests.letters.test_letter_delivery import register


async def test_feed_has_feedback_but_no_ocr_endpoint(client: AsyncClient) -> None:
    headers = await register(client, "ai-feed@example.com", "피드작성자")
    feedback = await client.post(
        "/api/v1/feeds/feedback",
        headers=headers,
        json={"title": "오늘", "content": "느리게 산책했다."},
    )
    assert feedback.status_code == 200
    assert feedback.json()["data"]["suggestions"]

    schema = (await client.get("/openapi.json")).json()
    assert "/api/v1/feeds/ocr" not in schema["paths"]


async def test_ocr_rejects_spoofed_image(client: AsyncClient) -> None:
    headers = await register(client, "ocr@example.com", "OCR")
    response = await client.post(
        "/api/v1/letters/ocr",
        headers=headers,
        files={"image": ("letter.png", b"not-an-image", "image/png")},
    )
    assert response.status_code == 415
    assert response.json()["error"]["code"] == "UNSUPPORTED_FILE_TYPE"
