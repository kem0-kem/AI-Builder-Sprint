from typing import Annotated

from fastapi import APIRouter, Depends, File, UploadFile
from fastapi.responses import JSONResponse

from app.ai.gateway import (
    WritingAssistantGateway,
    WritingContext,
    get_writing_assistant,
)
from app.ai.schemas import FeedFeedbackRequest, TextFeedbackRequest
from app.auth.dependencies import CurrentUserId, Session
from app.common.responses import success
from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.rate_limit import ai_limiter
from app.moderation.dependencies import Moderation, moderation_response

router = APIRouter(tags=["ai"])
Assistant = Annotated[WritingAssistantGateway, Depends(get_writing_assistant)]

IMAGE_SIGNATURES = {
    "image/png": (b"\x89PNG\r\n\x1a\n",),
    "image/jpeg": (b"\xff\xd8\xff",),
    "image/webp": (b"RIFF",),
}


async def validated_image(image: UploadFile) -> tuple[bytes, str]:
    mime = image.content_type or ""
    content = await image.read(get_settings().max_upload_bytes + 1)
    if len(content) > get_settings().max_upload_bytes:
        raise ApiError("FILE_TOO_LARGE", "이미지 파일 크기가 제한을 초과했습니다.", 413)
    signatures = IMAGE_SIGNATURES.get(mime)
    valid = signatures is not None and any(content.startswith(item) for item in signatures)
    if mime == "image/webp":
        valid = valid and len(content) >= 12 and content[8:12] == b"WEBP"
    if not valid:
        raise ApiError("UNSUPPORTED_FILE_TYPE", "지원하지 않는 이미지 형식입니다.", 415)
    return content, mime


async def ocr_response(
    image: UploadFile,
    assistant: Assistant,
    user_id: CurrentUserId,
    moderation: Moderation,
    session: Session,
) -> dict[str, object] | JSONResponse:
    content, mime = await validated_image(image)
    try:
        text = await assistant.ocr(content, mime)
    except TimeoutError as exc:
        raise ApiError("AI_SERVICE_UNAVAILABLE", "OCR 서비스를 사용할 수 없습니다.", 503) from exc
    if moderation is not None:
        outcome = await moderation.evaluate_ocr(user_id, text)
        response = await moderation_response(outcome, session)
        if response is not None:
            return response
    return success({"text": text})


@router.post(
    "/letters/ocr", dependencies=[Depends(ai_limiter)], response_model=None
)
async def letter_ocr(
    user_id: CurrentUserId,
    assistant: Assistant,
    image: Annotated[UploadFile, File()],
    moderation: Moderation,
    session: Session,
) -> dict[str, object] | JSONResponse:
    return await ocr_response(image, assistant, user_id, moderation, session)


@router.post(
    "/reports/ocr", dependencies=[Depends(ai_limiter)], response_model=None
)
async def report_ocr(
    user_id: CurrentUserId,
    assistant: Assistant,
    image: Annotated[UploadFile, File()],
    moderation: Moderation,
    session: Session,
) -> dict[str, object] | JSONResponse:
    return await ocr_response(image, assistant, user_id, moderation, session)


@router.post("/letters/feedback", dependencies=[Depends(ai_limiter)])
async def letter_feedback(
    request: TextFeedbackRequest, _: CurrentUserId, assistant: Assistant
) -> dict[str, object]:
    result = await assistant.feedback(WritingContext.LETTER, None, request.content)
    return success(result.model_dump())


@router.post("/feeds/feedback", dependencies=[Depends(ai_limiter)])
async def feed_feedback(
    request: FeedFeedbackRequest, _: CurrentUserId, assistant: Assistant
) -> dict[str, object]:
    result = await assistant.feedback(WritingContext.FEED, request.title, request.content)
    return success(result.model_dump())
