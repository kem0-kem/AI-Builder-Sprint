import hashlib
import json
from dataclasses import dataclass
from uuid import UUID

from sqlalchemy import and_, func, select

from app.auth.dependencies import Session
from app.chat.models import ChatMessage, ChatParticipant, ChatRoom
from app.core.config import get_settings
from app.core.errors import ApiError
from app.events.outbox import OutboxEvent, OutboxRepository
from app.letters.models import IdempotencyRecord, Letter, MailboxEntry
from app.letters.schemas import LetterCreate
from app.matching.metrics import FallbackReason
from app.matching.models import MatchHistory, MatchStrategy
from app.matching.privacy import (
    PublicMatchStrategy,
    public_matching_result,
    validate_public_matching_payload,
)
from app.matching.profile_policy import ProfileMatchingPolicy
from app.matching.repository import MatchingRepository
from app.matching.service import MatchingService


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
    def __init__(self, session: Session, matching: MatchingService | None = None) -> None:
        self._session = session
        # Keep existing direct and replay construction working until Task 6's
        # semantic dependency is available for request-level assembly.
        self._matching = matching or MatchingService(
            MatchingRepository(session),
            ProfileMatchingPolicy(),
            mode=get_settings().matching_mode,
        )

    async def execute(
        self, owner_id: UUID, payload: dict[str, object], idempotency_key: str
    ) -> LetterResult:
        request = LetterCreate.model_validate(payload)
        await self._lock_idempotency(owner_id, idempotency_key)
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
                raise ApiError("RESOURCE_CONFLICT", "저장된 요청 결과가 올바르지 않습니다.", 409)
            return LetterResult(UUID(letter_data["id"]), prior.response)

        locked = (
            await self._matching.select_and_lock(self._session, owner_id, request.content)
            if request.match
            else None
        )
        recipient = locked.user if locked is not None else None
        if request.match and recipient is None:
            raise ApiError("MATCH_NOT_FOUND", "현재 매칭 가능한 이웃이 없습니다.", 409)
        if locked is not None:
            candidate = locked.candidate
            semantic = candidate.strategy is MatchStrategy.SEMANTIC
            self._matching.record_match(
                MatchHistory.create(
                    owner_id,
                    locked.user.id,
                    candidate.strategy,
                    similarity_score=candidate.score if semantic else None,
                    model_name=getattr(candidate, "model_name", None) if semantic else None,
                    model_version=(
                        getattr(candidate, "model_version", None) if semantic else None
                    ),
                )
            )

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
        public_match = public_matching_result(
            matched=locked is not None,
            strategy=(
                PublicMatchStrategy(locked.candidate.strategy.value) if locked is not None else None
            ),
            fallback_reason=(
                FallbackReason(locked.candidate.fallback_reason)
                if locked is not None and locked.candidate.fallback_reason is not None
                else None
            ),
        ).model_dump(by_alias=True)
        validate_public_matching_payload(public_match)
        result: dict[str, object] = {
            "letter": letter_view(letter, owner_id),
            "matching": public_match,
            "chatRoom": {"id": str(room.id), "type": room.type} if room else None,
            "firstMessage": {"id": str(message.id), "type": message.type} if message else None,
        }
        self._session.add(IdempotencyRecord(user_id=owner_id, key=idempotency_key, response=result))
        await self._session.commit()
        return LetterResult(letter.id, result)

    async def _lock_idempotency(self, owner_id: UUID, idempotency_key: str) -> None:
        """Serialize same-key PostgreSQL requests before their replay lookup."""

        if self._session.get_bind().dialect.name != "postgresql":
            return
        await self._session.execute(
            select(func.pg_advisory_xact_lock(idempotency_lock_key(owner_id, idempotency_key)))
        )


def idempotency_lock_key(owner_id: UUID, idempotency_key: str) -> int:
    digest = hashlib.sha256(f"{owner_id}:{idempotency_key}".encode()).digest()
    return int.from_bytes(digest[:8], byteorder="big", signed=True)
