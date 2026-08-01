from uuid import uuid4

from httpx import AsyncClient


async def register(client: AsyncClient, email: str, nickname: str) -> dict[str, str]:
    response = await client.post(
        "/api/v1/auth/signup",
        json={"email": email, "password": "strong-pass", "nickname": nickname},
    )
    return {"Authorization": f"Bearer {response.json()['data']['accessToken']}"}


async def test_personal_letter_and_no_candidate_are_atomic(client: AsyncClient) -> None:
    alice = await register(client, "alice@example.com", "앨리스")
    personal = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": str(uuid4())},
        json={"content": "나만의 기록", "match": False},
    )
    assert personal.status_code == 201
    assert personal.json()["data"]["matching"] == {"matched": False}

    unmatched = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": str(uuid4())},
        json={"content": "보낼 편지", "match": True},
    )
    assert unmatched.status_code == 409
    assert unmatched.json()["error"]["code"] == "MATCH_NOT_FOUND"


async def test_match_creates_room_first_message_and_is_idempotent(client: AsyncClient) -> None:
    alice = await register(client, "alice@example.com", "앨리스")
    bob = await register(client, "bob@example.com", "밥")
    key = str(uuid4())
    first = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": key},
        json={"content": "안녕하세요", "match": True},
    )
    repeated = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": key},
        json={"content": "안녕하세요", "match": True},
    )
    assert first.status_code == repeated.status_code == 201
    assert first.json()["data"] == repeated.json()["data"]
    room_id = first.json()["data"]["chatRoom"]["id"]

    bob_rooms = await client.get("/api/v1/chat-rooms", headers=bob)
    assert room_id in {room["id"] for room in bob_rooms.json()["data"]}
    messages = await client.get(f"/api/v1/chat-rooms/{room_id}/messages", headers=bob)
    assert messages.json()["data"][0]["type"] == "LETTER"
