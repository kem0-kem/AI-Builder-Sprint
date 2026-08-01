import json
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query, WebSocket, WebSocketDisconnect, status
from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.dependencies import CurrentUserId, Session
from app.auth.security import decode_access_token
from app.chat.models import ChatMessage, ChatParticipant, ChatRoom, OutboxEvent
from app.chat.schemas import MessageCreate, ReadPositionUpdate
from app.common.responses import page, success
from app.core.errors import ApiError
from app.db.session import get_session

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


@router.post("/chat-rooms/{room_id}/messages", status_code=status.HTTP_201_CREATED)
async def create_message(
    room_id: UUID,
    request: MessageCreate,
    user_id: CurrentUserId,
    session: Session,
) -> dict[str, object]:
    await require_participant(session, room_id, user_id)
    existing = await session.scalar(
        select(ChatMessage).where(
            and_(
                ChatMessage.room_id == room_id,
                ChatMessage.client_message_id == request.client_message_id,
            )
        )
    )
    if existing is not None:
        return success(await message_view(session, existing, user_id))
    message = ChatMessage(
        room_id=room_id,
        sender_id=user_id,
        client_message_id=request.client_message_id,
        content=request.content,
    )
    session.add(message)
    await session.flush()
    session.add(
        OutboxEvent(
            topic="message.created",
            aggregate_id=room_id,
            payload=json.dumps({"messageId": str(message.id), "roomId": str(room_id)}),
        )
    )
    await session.commit()
    return success(await message_view(session, message, user_id))


@router.put("/chat-rooms/{room_id}/read-position")
async def update_read_position(
    room_id: UUID,
    request: ReadPositionUpdate,
    user_id: CurrentUserId,
    session: Session,
) -> dict[str, object]:
    participant = await require_participant(session, room_id, user_id)
    message = await session.get(ChatMessage, request.message_id)
    if message is None or message.room_id != room_id:
        raise ApiError("VALIDATION_ERROR", "채팅방에 속하지 않은 메시지입니다.", 400)
    participant.last_read_message_id = message.id
    await session.commit()
    return success({"messageId": str(message.id)})


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
