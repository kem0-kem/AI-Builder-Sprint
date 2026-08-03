from uuid import UUID

from fastapi import APIRouter, Header, Query, status
from fastapi.responses import JSONResponse
from sqlalchemy import and_, select

from app.auth.dependencies import CurrentUserId, Session
from app.common.responses import page, success
from app.core.errors import ApiError
from app.letters.models import Letter, MailboxEntry
from app.letters.schemas import LetterCreate
from app.letters.service import LetterCommandHandler, letter_view
from app.matching.dependencies import Matching
from app.moderation.dependencies import MODERATION_RESPONSES, Moderation
from app.moderation.models import SubmissionStatus
from app.moderation.repository import ModerationCommand
from app.moderation.schemas import ContentType

router = APIRouter(tags=["letters"])


@router.post(
    "/letters",
    status_code=status.HTTP_201_CREATED,
    response_model=None,
    responses=MODERATION_RESPONSES,
)
async def create_letter(
    request: LetterCreate,
    user_id: CurrentUserId,
    session: Session,
    matching: Matching,
    moderation: Moderation,
    idempotency_key: str = Header(alias="Idempotency-Key", min_length=8, max_length=80),
) -> dict[str, object] | JSONResponse:
    payload = request.model_dump()
    if moderation is not None:
        outcome = await moderation.evaluate(
            ModerationCommand(
                owner_id=user_id,
                content_type=ContentType.LETTER,
                operation="CREATE_LETTER",
                text=request.content,
                payload=payload,
                idempotency_key=idempotency_key,
            )
        )
        if outcome.status is SubmissionStatus.PENDING_REVIEW:
            await session.commit()
            return JSONResponse(
                status_code=202,
                content=success(
                    {
                        "moderationStatus": outcome.status.value,
                        "submissionId": str(outcome.submission_id),
                    }
                ),
            )
        if outcome.status is SubmissionStatus.BLOCKED:
            await session.commit()
            raise ApiError(
                "CONTENT_POLICY_VIOLATION",
                "콘텐츠 정책을 확인해 주세요.",
                422,
                {"categories": sorted(category.value for category in outcome.categories)},
            )
    result = await LetterCommandHandler(session, matching).execute(
        user_id, payload, idempotency_key
    )
    return success(result.data)


@router.get("/letters")
async def list_letters(
    user_id: CurrentUserId,
    session: Session,
    direction: str = Query("sent", pattern="^(sent|received)$"),
    limit: int = Query(30, ge=1, le=100),
) -> dict[str, object]:
    expected = direction.upper()
    items = list(
        (
            await session.execute(
                select(Letter)
                .join(MailboxEntry, MailboxEntry.letter_id == Letter.id)
                .where(MailboxEntry.user_id == user_id, MailboxEntry.direction == expected)
                .order_by(Letter.created_at.desc())
                .limit(limit)
            )
        ).scalars()
    )
    return page([letter_view(item, user_id) for item in items], next_cursor=None)


@router.get("/letters/{letter_id}")
async def get_letter(
    letter_id: UUID, user_id: CurrentUserId, session: Session
) -> dict[str, object]:
    letter = await session.scalar(
        select(Letter)
        .join(MailboxEntry, MailboxEntry.letter_id == Letter.id)
        .where(and_(Letter.id == letter_id, MailboxEntry.user_id == user_id))
    )
    if letter is None:
        raise ApiError("RESOURCE_NOT_FOUND", "편지를 찾을 수 없습니다.", 404)
    return success(letter_view(letter, user_id))
