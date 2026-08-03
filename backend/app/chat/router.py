from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query, WebSocket, WebSocketDisconnect, status
from fastapi.responses import JSONResponse
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.dependencies import CurrentUserId, Session
from app.auth.security import decode_access_token
from app.chat.models import ChatMessage, ChatParticipant, ChatRoom
from app.chat.schemas import ChatRoomReadUpdate, MessageCreate, ReadPositionUpdate
from app.chat.service import ChatCommandHandler
from app.common.responses import page, success
from app.core.errors import ApiError
from app.db.session import get_session
from app.moderation.dependencies import MODERATION_RESPONSES, Moderation
from app.moderation.models import SubmissionStatus
from app.moderation.repository import ModerationCommand
from app.moderation.schemas import ContentType

router = APIRouter(tags=["chat"])


async def require_participant(session: Session, room_id: UUID, user_id: UUID) -> ChatParticipant:
    participant = await session.get(ChatParticipant, (room_id, user_id))
    if participant is None:
        raise ApiError("RESOURCE_NOT_FOUND", "채팅방을 찾을 수 없습니다.", 404)
    return participant


async def message_view(
    session: Session, message: ChatMessage, viewer_id: UUID
) -> dict[str, object]:
    participant = await session.get(ChatParticipant, (message.room_id, message.sender_id))
    if participant is None:
        raise ApiError("RESOURCE_CONFLICT", "메시지 발신자 정보가 없습니다.", 409)
    return {
        "id": str(message.id),
        "clientMessageId": str(message.client_message_id) if message.client_message_id else None,
        "type": message.type,
        "sender": {
            "displayName": "나" if message.sender_id == viewer_id else participant.alias,
            "isMe": message.sender_id == viewer_id,
        },
        "content": message.content,
        "createdAt": message.created_at.isoformat(),
    }


@router.get("/chat-rooms")
async def list_rooms(user_id: CurrentUserId, session: Session) -> dict[str, object]:
    rooms = (
        await session.execute(
            select(ChatRoom)
            .join(ChatParticipant, ChatParticipant.room_id == ChatRoom.id)
            .where(ChatParticipant.user_id == user_id)
            .order_by(ChatRoom.created_at.desc())
        )
    ).scalars()
    return success(
        [
            {
                "id": str(room.id),
                "type": room.type,
                "name": room.name,
                "createdAt": room.created_at.isoformat(),
            }
            for room in rooms
        ]
    )


@router.get("/chat-rooms/{room_id}")
async def get_room(room_id: UUID, user_id: CurrentUserId, session: Session) -> dict[str, object]:
    await require_participant(session, room_id, user_id)
    room = await session.get(ChatRoom, room_id)
    if room is None:
        raise ApiError("RESOURCE_NOT_FOUND", "채팅방을 찾을 수 없습니다.", 404)
    return success({"id": str(room.id), "type": room.type, "name": room.name})


@router.delete("/chat-rooms/{room_id}", status_code=status.HTTP_204_NO_CONTENT)
async def leave_room(room_id: UUID, user_id: CurrentUserId, session: Session) -> None:
    """Remove the current user's membership without deleting other participants' history."""
    participant = await require_participant(session, room_id, user_id)
    await session.delete(participant)
    await session.commit()


@router.get("/chat-rooms/{room_id}/messages")
async def list_messages(
    room_id: UUID,
    user_id: CurrentUserId,
    session: Session,
    cursor: UUID | None = None,
    limit: int = Query(30, ge=1, le=100),
) -> dict[str, object]:
    await require_participant(session, room_id, user_id)
    statement = select(ChatMessage).where(ChatMessage.room_id == room_id)
    if cursor is not None:
        pivot = await session.get(ChatMessage, cursor)
        if pivot is None or pivot.room_id != room_id:
            raise ApiError("VALIDATION_ERROR", "유효하지 않은 커서입니다.", 400)
        statement = statement.where(ChatMessage.created_at < pivot.created_at)
    items = list(
        (
            await session.execute(
                statement.order_by(ChatMessage.created_at.desc()).limit(limit + 1)
            )
        ).scalars()
    )
    next_cursor = str(items[limit - 1].id) if len(items) > limit else None
    return page(
        [await message_view(session, item, user_id) for item in items[:limit]],
        next_cursor=next_cursor,
    )


@router.post(
    "/chat-rooms/{room_id}/messages",
    status_code=status.HTTP_201_CREATED,
    response_model=None,
    responses=MODERATION_RESPONSES,
)
async def create_message(
    room_id: UUID,
    request: MessageCreate,
    user_id: CurrentUserId,
    session: Session,
    moderation: Moderation,
) -> dict[str, object] | JSONResponse:
    await require_participant(session, room_id, user_id)
    payload = {**request.model_dump(by_alias=True, mode="json"), "roomId": str(room_id)}
    if moderation is not None:
        outcome = await moderation.evaluate(
            ModerationCommand(
                owner_id=user_id,
                content_type=ContentType.CHAT_MESSAGE,
                operation="CREATE_CHAT_MESSAGE",
                target_id=room_id,
                text=request.content,
                payload=payload,
                idempotency_key=str(request.client_message_id),
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
    result = await ChatCommandHandler(session).execute(
        user_id, payload, str(request.client_message_id)
    )
    return success(await message_view(session, result.message, user_id))


@router.patch("/chat-rooms/{room_id}/read")
async def mark_room_read(
    room_id: UUID,
    request: ChatRoomReadUpdate,
    user_id: CurrentUserId,
    session: Session,
) -> dict[str, object]:
    message, unread_count = await set_read_position(
        session, room_id, user_id, request.last_read_message_id
    )
    return success(
        {"lastReadMessageId": str(message.id), "unreadCount": unread_count}
    )


@router.put("/chat-rooms/{room_id}/read-position")
async def update_read_position(
    room_id: UUID,
    request: ReadPositionUpdate,
    user_id: CurrentUserId,
    session: Session,
) -> dict[str, object]:
    message, _ = await set_read_position(session, room_id, user_id, request.message_id)
    return success({"messageId": str(message.id)})


async def set_read_position(
    session: Session,
    room_id: UUID,
    user_id: UUID,
    message_id: UUID,
) -> tuple[ChatMessage, int]:
    participant = await require_participant(session, room_id, user_id)
    target = await session.get(ChatMessage, message_id)
    if target is None or target.room_id != room_id:
        raise ApiError("VALIDATION_ERROR", "Message does not belong to this chat room.", 400)

    effective = target
    if participant.last_read_message_id is not None:
        current = await session.get(ChatMessage, participant.last_read_message_id)
        if (
            current is not None
            and current.room_id == room_id
            and current.created_at > target.created_at
        ):
            effective = current

    participant.last_read_message_id = effective.id
    unread_count = await session.scalar(
        select(func.count())
        .select_from(ChatMessage)
        .where(
            ChatMessage.room_id == room_id,
            ChatMessage.created_at > effective.created_at,
            ChatMessage.sender_id != user_id,
        )
    )
    await session.commit()
    return effective, int(unread_count or 0)


@router.websocket("/ws/chat-rooms/{room_id}")
async def chat_socket(
    websocket: WebSocket,
    room_id: UUID,
    session: Annotated[AsyncSession, Depends(get_session)],
    token: str | None = Query(None),
) -> None:
    if token is None:
        await websocket.close(code=4401)
        return
    try:
        user_id = decode_access_token(token)
    except ApiError:
        await websocket.close(code=4401)
        return
    if await session.get(ChatParticipant, (room_id, user_id)) is None:
        await websocket.close(code=4404)
        return
    await websocket.accept()
    try:
        while True:
            payload = await websocket.receive_json()
            await websocket.send_json({"type": "ack", "roomId": str(room_id), "data": payload})
    except WebSocketDisconnect:
        return
