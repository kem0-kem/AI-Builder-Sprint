from datetime import UTC, datetime, timedelta
from uuid import UUID

from fastapi import APIRouter, Query, status
from sqlalchemy import select
from sqlalchemy.orm import aliased

from app.auth.dependencies import CurrentUserId, Session
from app.auth.models import User
from app.chat.models import ChatParticipant, ChatRoom
from app.common.responses import success
from app.core.errors import ApiError
from app.meetings.models import InviteCandidate, Meeting, MeetingParticipant
from app.meetings.schemas import MeetingCreate

router = APIRouter(tags=["meetings"])


@router.get("/meeting-invite-candidates")
async def list_candidates(
    user_id: CurrentUserId,
    session: Session,
    keyword: str | None = Query(None, max_length=30),
) -> dict[str, object]:
    requester_participant = aliased(ChatParticipant)
    candidate_participant = aliased(ChatParticipant)
    statement = (
        select(User)
        .join(candidate_participant, candidate_participant.user_id == User.id)
        .join(ChatRoom, ChatRoom.id == candidate_participant.room_id)
        .join(requester_participant, requester_participant.room_id == ChatRoom.id)
        .where(
            requester_participant.user_id == user_id,
            candidate_participant.user_id != user_id,
            ChatRoom.type == "DIRECT",
            User.is_active.is_(True),
        )
        .distinct()
    )
    if keyword:
        statement = statement.where(User.nickname.contains(keyword))
    users = list((await session.execute(statement.order_by(User.created_at).limit(30))).scalars())
    result: list[dict[str, str]] = []
    expires_at = datetime.now(UTC) + timedelta(minutes=15)
    for index, candidate in enumerate(users, start=1):
        token = await session.scalar(
            select(InviteCandidate).where(
                InviteCandidate.requester_id == user_id,
                InviteCandidate.candidate_user_id == candidate.id,
            )
        )
        if token is None:
            token = InviteCandidate(
                requester_id=user_id,
                candidate_user_id=candidate.id,
                expires_at=expires_at,
            )
            session.add(token)
            await session.flush()
        else:
            token.expires_at = expires_at
        result.append({"candidateId": str(token.id), "displayName": f"익명의 이웃 {index:02d}"})
    await session.commit()
    return success(result)


@router.post("/meetings", status_code=status.HTTP_201_CREATED)
async def create_meeting(
    request: MeetingCreate, user_id: CurrentUserId, session: Session
) -> dict[str, object]:
    unique_ids = set(request.invite_candidate_ids)
    if len(unique_ids) != len(request.invite_candidate_ids):
        raise ApiError("VALIDATION_ERROR", "초대 후보는 중복될 수 없습니다.", 400)
    tokens = list(
        (
            await session.execute(
                select(InviteCandidate).where(
                    InviteCandidate.id.in_(unique_ids),
                    InviteCandidate.requester_id == user_id,
                )
            )
        ).scalars()
    )
    now = datetime.now(UTC)
    if len(tokens) != len(unique_ids) or any(
        token.expires_at.replace(tzinfo=UTC) <= now for token in tokens
    ):
        raise ApiError("RESOURCE_CONFLICT", "초대 후보가 만료되었거나 유효하지 않습니다.", 409)
    participant_ids = [token.candidate_user_id for token in tokens]
    room = ChatRoom(type="GROUP", name=request.title)
    session.add(room)
    await session.flush()
    meeting = Meeting(
        owner_id=user_id,
        chat_room_id=room.id,
        title=request.title,
        description=request.description,
    )
    session.add(meeting)
    await session.flush()
    all_users = [user_id, *participant_ids]
    session.add_all(
        [
            ChatParticipant(room_id=room.id, user_id=item, alias=f"참여자 {index:02d}")
            for index, item in enumerate(all_users, start=1)
        ]
    )
    session.add_all([MeetingParticipant(meeting_id=meeting.id, user_id=item) for item in all_users])
    await session.commit()
    return success(
        {
            "id": str(meeting.id),
            "title": meeting.title,
            "description": meeting.description,
            "chatRoom": {"id": str(room.id), "type": "GROUP"},
            "participantCount": len(all_users),
        }
    )


@router.get("/meetings/{meeting_id}")
async def get_meeting(
    meeting_id: UUID, user_id: CurrentUserId, session: Session
) -> dict[str, object]:
    membership = await session.get(MeetingParticipant, (meeting_id, user_id))
    meeting = await session.get(Meeting, meeting_id)
    if membership is None or meeting is None:
        raise ApiError("RESOURCE_NOT_FOUND", "모임을 찾을 수 없습니다.", 404)
    count = len(
        list(
            (
                await session.execute(
                    select(MeetingParticipant).where(MeetingParticipant.meeting_id == meeting_id)
                )
            ).scalars()
        )
    )
    return success(
        {
            "id": str(meeting.id),
            "title": meeting.title,
            "description": meeting.description,
            "chatRoomId": str(meeting.chat_room_id),
            "participantCount": count,
        }
    )
