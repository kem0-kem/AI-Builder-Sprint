from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker

from app.auth.models import User
from app.chat.models import ChatParticipant, ChatRoom
from tests.letters.test_letter_delivery import register


async def test_anonymous_candidates_create_group_meeting(
    client: AsyncClient,
    session_factory: async_sessionmaker,
) -> None:
    alice = await register(client, "meeting-alice@example.com", "앨리스")
    await register(client, "meeting-bob@example.com", "밥")
    await register(client, "meeting-stranger@example.com", "낯선 이웃")
    async with session_factory() as session:
        alice_user = await session.scalar(select(User).where(User.email == "meeting-alice@example.com"))
        bob_user = await session.scalar(select(User).where(User.email == "meeting-bob@example.com"))
        assert alice_user is not None and bob_user is not None
        room = ChatRoom(type="DIRECT", direct_key=f"{alice_user.id}:{bob_user.id}")
        session.add(room)
        await session.flush()
        session.add_all(
            [
                ChatParticipant(room_id=room.id, user_id=alice_user.id, alias="익명의 이웃 01"),
                ChatParticipant(room_id=room.id, user_id=bob_user.id, alias="익명의 이웃 02"),
            ]
        )
        await session.commit()
    candidates = await client.get("/api/v1/meeting-invite-candidates", headers=alice)
    assert candidates.status_code == 200
    assert len(candidates.json()["data"]) == 1
    candidate = candidates.json()["data"][0]
    assert set(candidate) == {"candidateId", "displayName"}

    created = await client.post(
        "/api/v1/meetings",
        headers=alice,
        json={
            "title": "주말 산책",
            "description": "천천히 걸어요",
            "inviteCandidateIds": [candidate["candidateId"]],
        },
    )
    assert created.status_code == 201
    assert created.json()["data"]["chatRoom"]["type"] == "GROUP"
    assert created.json()["data"]["participantCount"] == 2
