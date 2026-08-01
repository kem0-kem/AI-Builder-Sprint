from uuid import uuid4

from httpx import AsyncClient

from tests.letters.test_letter_delivery import register


async def test_chat_message_is_idempotent_and_updates_read_position(client: AsyncClient) -> None:
    alice = await register(client, "alice-chat@example.com", "앨리스")
    bob = await register(client, "bob-chat@example.com", "밥")
    delivery = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": str(uuid4())},
        json={"content": "첫 편지", "match": True},
    )
    room_id = delivery.json()["data"]["chatRoom"]["id"]
    client_message_id = str(uuid4())
    payload = {"clientMessageId": client_message_id, "content": "반가워요"}
    first = await client.post(f"/api/v1/chat-rooms/{room_id}/messages", headers=bob, json=payload)
    repeated = await client.post(
        f"/api/v1/chat-rooms/{room_id}/messages", headers=bob, json=payload
    )
    assert first.status_code == 201
    assert first.json()["data"]["id"] == repeated.json()["data"]["id"]

    read = await client.put(
        f"/api/v1/chat-rooms/{room_id}/read-position",
        headers=alice,
        json={"messageId": first.json()["data"]["id"]},
    )
    assert read.status_code == 200
