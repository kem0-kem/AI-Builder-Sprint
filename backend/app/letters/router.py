import json
from uuid import UUID

from fastapi import APIRouter, Header, Query, status
from sqlalchemy import and_, select

from app.auth.dependencies import CurrentUserId, Session
from app.auth.models import User
from app.chat.models import ChatMessage, ChatParticipant, ChatRoom
from app.common.responses import page, success
from app.core.errors import ApiError
from app.events.outbox import OutboxEvent
from app.letters.models import IdempotencyRecord, Letter, MailboxEntry, UserBlock
from app.letters.schemas import LetterCreate

router = APIRouter(tags=["letters"])


def direct_key(first: UUID, second: UUID) -> str:
    return ":".join(sorted((str(first), str(second))))


def letter_view(letter: Letter, viewer_id: UUID) -> dict[str, object]:
    return {
        "id": str(letter.id),
        "direction": "SENT" if letter.sender_id == viewer_id else "RECEIVED",
        "content": letter.content,
        "createdAt": letter.created_at.isoformat(),
    }


async def select_candidate(session: Session, sender_id: UUID) -> User | None:
    blocked = select(UserBlock.blocked_id).where(UserBlock.blocker_id == sender_id)
    blockers = select(UserBlock.blocker_id).where(UserBlock.blocked_id == sender_id)
    statement = (
        select(User)
        .where(
            User.id != sender_id,
            User.is_active.is_(True),
            User.id.not_in(blocked),
            User.id.not_in(blockers),
        )
        .order_by(User.created_at)
        .limit(1)
        .with_for_update(skip_locked=True)
    )
    result: User | None = await session.scalar(statement)
    return result


@router.post("/letters", status_code=status.HTTP_201_CREATED)
async def create_letter(
    request: LetterCreate,
    user_id: CurrentUserId,
    session: Session,
    idempotency_key: str = Header(alias="Idempotency-Key", min_length=8, max_length=80),
) -> dict[str, object]:
    prior = await session.scalar(
        select(IdempotencyRecord).where(
            and_(IdempotencyRecord.user_id == user_id, IdempotencyRecord.key == idempotency_key)
        )
    )
    if prior is not None:
        return success(prior.response)

    recipient = await select_candidate(session, user_id) if request.match else None
    if request.match and recipient is None:
        raise ApiError("MATCH_NOT_FOUND", "현재 매칭 가능한 이웃이 없습니다.", 409)

    letter = Letter(
        sender_id=user_id,
        recipient_id=recipient.id if recipient else None,
        content=request.content,
    )
    session.add(letter)
    await session.flush()
    session.add(MailboxEntry(user_id=user_id, letter_id=letter.id, direction="SENT"))

    room = None
    message = None
    if recipient is not None:
        session.add(MailboxEntry(user_id=recipient.id, letter_id=letter.id, direction="RECEIVED"))
        key = direct_key(user_id, recipient.id)
        room = await session.scalar(select(ChatRoom).where(ChatRoom.direct_key == key))
        if room is None:
            room = ChatRoom(type="DIRECT", direct_key=key)
            session.add(room)
            await session.flush()
            session.add_all(
                [
                    ChatParticipant(room_id=room.id, user_id=user_id, alias="익명의 이웃 01"),
                    ChatParticipant(room_id=room.id, user_id=recipient.id, alias="익명의 이웃 02"),
                ]
            )
        message = ChatMessage(
            room_id=room.id,
            sender_id=user_id,
            type="LETTER",
            content=request.content,
            letter_id=letter.id,
        )
        session.add(message)
        await session.flush()
        session.add(
            OutboxEvent(
                topic="message.created",
                aggregate_id=room.id,
                payload=json.dumps({"messageId": str(message.id), "roomId": str(room.id)}),
            )
        )

    result: dict[str, object] = {
        "letter": letter_view(letter, user_id),
        "matching": {"matched": recipient is not None},
        "chatRoom": {"id": str(room.id), "type": room.type} if room else None,
        "firstMessage": {"id": str(message.id), "type": message.type} if message else None,
    }
    session.add(IdempotencyRecord(user_id=user_id, key=idempotency_key, response=result))
    await session.commit()
    return success(result)


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
