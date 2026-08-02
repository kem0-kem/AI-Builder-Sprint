import json
from dataclasses import dataclass
from uuid import UUID

from sqlalchemy import and_, select

from app.auth.dependencies import Session
from app.auth.models import User
from app.chat.models import ChatMessage, ChatParticipant, ChatRoom
from app.events.outbox import OutboxEvent, OutboxRepository
from app.letters.models import IdempotencyRecord, Letter, MailboxEntry, UserBlock
from app.letters.schemas import LetterCreate


def direct_key(first: UUID, second: UUID) -> str:
    return ":".join(sorted((str(first), str(second))))


def letter_view(letter: Letter, viewer_id: UUID) -> dict[str, object]:
    return {
        "id": str(letter.id),
        "direction": "SENT" if letter.sender_id == viewer_id else "RECEIVED",
        "content": letter.content,
        "createdAt": letter.created_at.isoformat(),
    }


@dataclass(frozen=True, slots=True)
class LetterResult:
    resource_id: UUID
    data: dict[str, object]


class LetterCommandHandler:
    def __init__(self, session: Session) -> None:
        self._session = session

    async def execute(
        self, owner_id: UUID, payload: dict[str, object], idempotency_key: str
    ) -> LetterResult:
        request = LetterCreate.model_validate(payload)
        prior = await self._session.scalar(
            select(IdempotencyRecord).where(
                and_(
                    IdempotencyRecord.user_id == owner_id,
                    IdempotencyRecord.key == idempotency_key,
                )
            )
        )
        if prior is not None:
            letter_data = prior.response.get("letter")
            if not isinstance(letter_data, dict) or not isinstance(
                letter_data.get("id"), str
            ):
                from app.core.errors import ApiError

                raise ApiError("RESOURCE_CONFLICT", "저장된 요청 결과가 올바르지 않습니다.", 409)
            return LetterResult(UUID(letter_data["id"]), prior.response)

        recipient = await self._select_candidate(owner_id) if request.match else None
        from app.core.errors import ApiError

        if request.match and recipient is None:
            raise ApiError("MATCH_NOT_FOUND", "현재 매칭 가능한 이웃이 없습니다.", 409)
        letter = Letter(
            sender_id=owner_id,
            recipient_id=recipient.id if recipient else None,
            content=request.content,
        )
        self._session.add(letter)
        await self._session.flush()
        self._session.add(MailboxEntry(user_id=owner_id, letter_id=letter.id, direction="SENT"))
        room = None
        message = None
        if recipient is not None:
            self._session.add(
                MailboxEntry(user_id=recipient.id, letter_id=letter.id, direction="RECEIVED")
            )
            room = await self._session.scalar(
                select(ChatRoom).where(ChatRoom.direct_key == direct_key(owner_id, recipient.id))
            )
            if room is None:
                room = ChatRoom(type="DIRECT", direct_key=direct_key(owner_id, recipient.id))
                self._session.add(room)
                await self._session.flush()
                self._session.add_all(
                    [
                        ChatParticipant(room_id=room.id, user_id=owner_id, alias="익명의 이웃 01"),
                        ChatParticipant(
                            room_id=room.id,
                            user_id=recipient.id,
                            alias="익명의 이웃 02",
                        ),
                    ]
                )
            message = ChatMessage(
                room_id=room.id,
                sender_id=owner_id,
                type="LETTER",
                content=request.content,
                letter_id=letter.id,
            )
            self._session.add(message)
            await self._session.flush()
            self._session.add(
                OutboxEvent(
                    topic="message.created",
                    aggregate_id=room.id,
                    payload=json.dumps({"messageId": str(message.id), "roomId": str(room.id)}),
                )
            )
            await OutboxRepository(self._session).add(
                "letter.embedding.requested",
                letter.id,
                {
                    "letterId": str(letter.id),
                    "senderId": str(owner_id),
                },
            )
        result: dict[str, object] = {
            "letter": letter_view(letter, owner_id),
            "matching": {"matched": recipient is not None},
            "chatRoom": {"id": str(room.id), "type": room.type} if room else None,
            "firstMessage": {"id": str(message.id), "type": message.type} if message else None,
        }
        self._session.add(IdempotencyRecord(user_id=owner_id, key=idempotency_key, response=result))
        await self._session.commit()
        return LetterResult(letter.id, result)

    async def _select_candidate(self, owner_id: UUID) -> User | None:
        blocked = select(UserBlock.blocked_id).where(UserBlock.blocker_id == owner_id)
        blockers = select(UserBlock.blocker_id).where(UserBlock.blocked_id == owner_id)
        result = await self._session.scalar(
            select(User)
            .where(
                User.id != owner_id,
                User.is_active.is_(True),
                User.id.not_in(blocked),
                User.id.not_in(blockers),
            )
            .order_by(User.created_at)
            .limit(1)
            .with_for_update(skip_locked=True)
        )
        return result
