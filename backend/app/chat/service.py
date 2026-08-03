import json
from dataclasses import dataclass
from uuid import UUID

from sqlalchemy import and_, select

from app.auth.dependencies import Session
from app.chat.models import ChatMessage, ChatParticipant, ChatRoom
from app.chat.schemas import MessageCreate
from app.core.errors import ApiError
from app.events.outbox import OutboxEvent


@dataclass(frozen=True, slots=True)
class MessageResult:
    resource_id: UUID
    message: ChatMessage


def direct_key(first: UUID, second: UUID) -> str:
    return ":".join(sorted((str(first), str(second))))


async def get_or_create_direct_room(
    session: Session,
    first: UUID,
    second: UUID,
) -> ChatRoom:
    key = direct_key(first, second)
    room = await session.scalar(select(ChatRoom).where(ChatRoom.direct_key == key))
    if room is not None:
        participant_ids = set(
            (
                await session.execute(
                    select(ChatParticipant.user_id).where(
                        ChatParticipant.room_id == room.id
                    )
                )
            ).scalars()
        )
        if participant_ids != {first, second}:
            raise ApiError(
                "RESOURCE_CONFLICT",
                "채팅방 참가자 정보가 올바르지 않습니다.",
                409,
            )
        return room
    room = ChatRoom(type="DIRECT", name=None, direct_key=key)
    session.add(room)
    await session.flush()
    session.add_all(
        [
            ChatParticipant(room_id=room.id, user_id=first, alias="익명의 이웃 01"),
            ChatParticipant(room_id=room.id, user_id=second, alias="익명의 이웃 02"),
        ]
    )
    return room


class ChatCommandHandler:
    def __init__(self, session: Session) -> None:
        self._session = session

    async def execute(
        self, owner_id: UUID, payload: dict[str, object], _idempotency_key: str
    ) -> MessageResult:
        room_value = payload.get("roomId")
        if not isinstance(room_value, str):
            raise ApiError("VALIDATION_ERROR", "채팅방 값이 올바르지 않습니다.", 400)
        room_id = UUID(room_value)
        request = MessageCreate.model_validate(payload)
        participant = await self._session.get(ChatParticipant, (room_id, owner_id))
        if participant is None:
            raise ApiError("RESOURCE_NOT_FOUND", "채팅방을 찾을 수 없습니다.", 404)
        existing = await self._session.scalar(
            select(ChatMessage).where(
                and_(
                    ChatMessage.room_id == room_id,
                    ChatMessage.client_message_id == request.client_message_id,
                )
            )
        )
        if existing is not None:
            return MessageResult(existing.id, existing)
        message = ChatMessage(
            room_id=room_id,
            sender_id=owner_id,
            client_message_id=request.client_message_id,
            content=request.content,
        )
        self._session.add(message)
        await self._session.flush()
        self._session.add(
            OutboxEvent(
                topic="message.created",
                aggregate_id=room_id,
                payload=json.dumps({"messageId": str(message.id), "roomId": str(room_id)}),
            )
        )
        await self._session.commit()
        return MessageResult(message.id, message)
