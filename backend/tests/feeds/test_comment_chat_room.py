from uuid import UUID, uuid4

from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.chat.models import ChatParticipant
from tests.feeds.test_feed_api import create_feed
from tests.letters.test_letter_delivery import register


async def create_comment(
    client: AsyncClient,
    feed_id: str,
    headers: dict[str, str],
    content: str,
) -> str:
    response = await client.post(
        f"/api/v1/feeds/{feed_id}/comments",
        headers=headers,
        json={"content": content},
    )
    assert response.status_code == 201
    return response.json()["data"]["id"]


async def user_id(client: AsyncClient, headers: dict[str, str]) -> UUID:
    response = await client.get("/api/v1/users/me", headers=headers)
    assert response.status_code == 200
    return UUID(response.json()["data"]["id"])


async def test_comment_chat_room_creates_and_reuses_one_direct_room_for_pair(
    client: AsyncClient,
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    alice = await register(client, "comment-chat-alice@example.com", "Alice")
    bob = await register(client, "comment-chat-bob@example.com", "Bob")
    alice_id = await user_id(client, alice)
    bob_id = await user_id(client, bob)
    feed_id = await create_feed(client, alice)
    bob_comment_id = await create_comment(client, feed_id, bob, "hello from Bob")
    alice_comment_id = await create_comment(client, feed_id, alice, "hello from Alice")

    created = await client.post(
        f"/api/v1/comments/{bob_comment_id}/chat-room",
        headers=alice,
    )
    assert created.status_code == 200
    room_id = created.json()["data"]["id"]
    assert created.json()["data"] == {
        "id": room_id,
        "type": "DIRECT",
        "name": None,
    }

    replayed = await client.post(
        f"/api/v1/comments/{bob_comment_id}/chat-room",
        headers=alice,
    )
    assert replayed.status_code == 200
    assert replayed.json()["data"]["id"] == room_id

    reversed_pair = await client.post(
        f"/api/v1/comments/{alice_comment_id}/chat-room",
        headers=bob,
    )
    assert reversed_pair.status_code == 200
    assert reversed_pair.json()["data"]["id"] == room_id

    async with session_factory() as session:
        participant_ids = set(
            (
                await session.scalars(
                    select(ChatParticipant.user_id).where(
                        ChatParticipant.room_id == UUID(room_id)
                    )
                )
            ).all()
        )
    assert participant_ids == {alice_id, bob_id}
    assert len(participant_ids) == 2


async def test_comment_chat_room_rejects_own_comment(client: AsyncClient) -> None:
    alice = await register(client, "own-comment-chat@example.com", "Alice")
    feed_id = await create_feed(client, alice)
    comment_id = await create_comment(client, feed_id, alice, "my comment")

    response = await client.post(
        f"/api/v1/comments/{comment_id}/chat-room",
        headers=alice,
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


async def test_comment_chat_room_hides_deleted_and_missing_comments(
    client: AsyncClient,
) -> None:
    alice = await register(client, "missing-chat-alice@example.com", "Alice")
    bob = await register(client, "missing-chat-bob@example.com", "Bob")
    feed_id = await create_feed(client, alice)
    comment_id = await create_comment(client, feed_id, bob, "temporary comment")
    deleted = await client.delete(f"/api/v1/comments/{comment_id}", headers=bob)
    assert deleted.status_code == 204

    for target_id in (comment_id, str(uuid4())):
        response = await client.post(
            f"/api/v1/comments/{target_id}/chat-room",
            headers=alice,
        )
        assert response.status_code == 404
        assert response.json()["error"]["code"] == "RESOURCE_NOT_FOUND"


async def test_comment_chat_room_requires_authentication(client: AsyncClient) -> None:
    response = await client.post(f"/api/v1/comments/{uuid4()}/chat-room")

    assert response.status_code == 401
