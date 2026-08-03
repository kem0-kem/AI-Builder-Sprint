from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.auth.models import User
from app.chat.models import ChatParticipant
from app.chat.service import direct_key, get_or_create_direct_room
from tests.letters.test_letter_delivery import register


async def test_direct_room_key_is_order_independent_and_room_is_reused(
    client: AsyncClient,
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    await register(client, "direct-room-alice@example.com", "Alice")
    await register(client, "direct-room-bob@example.com", "Bob")

    async with session_factory() as session:
        alice_id = await session.scalar(
            select(User.id).where(User.email == "direct-room-alice@example.com")
        )
        bob_id = await session.scalar(
            select(User.id).where(User.email == "direct-room-bob@example.com")
        )
        assert alice_id is not None
        assert bob_id is not None
        assert direct_key(alice_id, bob_id) == direct_key(bob_id, alice_id)

        first = await get_or_create_direct_room(session, alice_id, bob_id)
        second = await get_or_create_direct_room(session, bob_id, alice_id)

        assert second.id == first.id
        participants = (
            await session.execute(
                select(ChatParticipant).where(ChatParticipant.room_id == first.id)
            )
        ).scalars().all()
        assert {item.user_id for item in participants} == {alice_id, bob_id}
