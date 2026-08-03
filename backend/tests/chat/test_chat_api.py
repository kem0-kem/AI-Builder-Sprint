from uuid import uuid4

from httpx import AsyncClient

from tests.feeds.test_feed_api import create_feed
from tests.letters.test_letter_delivery import register


async def test_comment_author_chat_is_idempotent(client: AsyncClient) -> None:
    author = await register(client, "comment-chat-author@example.com", "작성자")
    viewer = await register(client, "comment-chat-viewer@example.com", "대화 요청자")
    feed_id = await create_feed(client, author)
    comment = await client.post(
        f"/api/v1/feeds/{feed_id}/comments",
        headers=author,
        json={"content": "대화를 시작할 수 있는 댓글"},
    )
    comment_id = comment.json()["data"]["id"]

    first = await client.post(f"/api/v1/comments/{comment_id}/chat-room", headers=viewer)
    repeated = await client.post(f"/api/v1/comments/{comment_id}/chat-room", headers=viewer)
    assert first.status_code == 200
    assert first.json()["data"]["id"] == repeated.json()["data"]["id"]

    room_id = first.json()["data"]["id"]
    author_rooms = await client.get("/api/v1/chat-rooms", headers=author)
    viewer_rooms = await client.get("/api/v1/chat-rooms", headers=viewer)
    assert room_id in {room["id"] for room in author_rooms.json()["data"]}
    assert room_id in {room["id"] for room in viewer_rooms.json()["data"]}

    own_comment = await client.post(
        f"/api/v1/comments/{comment_id}/chat-room", headers=author
    )
    assert own_comment.status_code == 400


async def test_chat_message_is_idempotent_and_updates_read_position(client: AsyncClient) -> None:
    alice = await register(client, "alice-chat@example.com", "Alice")
    bob = await register(client, "bob-chat@example.com", "Bob")
    delivery = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": str(uuid4())},
        json={"content": "hello", "match": True},
    )
    room_id = delivery.json()["data"]["chatRoom"]["id"]
    # Register the outsider after delivery so Bob is the only eligible first match.
    charlie = await register(client, "charlie-chat@example.com", "Charlie")
    client_message_id = str(uuid4())
    payload = {"clientMessageId": client_message_id, "content": "first message"}
    first = await client.post(f"/api/v1/chat-rooms/{room_id}/messages", headers=bob, json=payload)
    repeated = await client.post(
        f"/api/v1/chat-rooms/{room_id}/messages", headers=bob, json=payload
    )
    assert first.status_code == 201
    assert first.json()["data"]["id"] == repeated.json()["data"]["id"]

    second = await client.post(
        f"/api/v1/chat-rooms/{room_id}/messages",
        headers=bob,
        json={"clientMessageId": str(uuid4()), "content": "second message"},
    )
    assert second.status_code == 201

    read_first = await client.patch(
        f"/api/v1/chat-rooms/{room_id}/read",
        headers=alice,
        json={"lastReadMessageId": first.json()["data"]["id"]},
    )
    assert read_first.status_code == 200
    assert read_first.json()["data"] == {
        "lastReadMessageId": first.json()["data"]["id"],
        "unreadCount": 1,
    }

    read_latest = await client.patch(
        f"/api/v1/chat-rooms/{room_id}/read",
        headers=alice,
        json={"lastReadMessageId": second.json()["data"]["id"]},
    )
    assert read_latest.status_code == 200
    assert read_latest.json()["data"] == {
        "lastReadMessageId": second.json()["data"]["id"],
        "unreadCount": 0,
    }

    regressed = await client.patch(
        f"/api/v1/chat-rooms/{room_id}/read",
        headers=alice,
        json={"lastReadMessageId": first.json()["data"]["id"]},
    )
    assert regressed.status_code == 200
    assert regressed.json()["data"] == {
        "lastReadMessageId": second.json()["data"]["id"],
        "unreadCount": 0,
    }

    outsider = await client.patch(
        f"/api/v1/chat-rooms/{room_id}/read",
        headers=charlie,
        json={"lastReadMessageId": second.json()["data"]["id"]},
    )
    assert outsider.status_code == 404

    invalid_message = await client.patch(
        f"/api/v1/chat-rooms/{room_id}/read",
        headers=alice,
        json={"lastReadMessageId": str(uuid4())},
    )
    assert invalid_message.status_code == 400

    read = await client.put(
        f"/api/v1/chat-rooms/{room_id}/read-position",
        headers=alice,
        json={"messageId": first.json()["data"]["id"]},
    )
    assert read.status_code == 200
    assert read.json()["data"] == {"messageId": second.json()["data"]["id"]}


async def test_user_can_remove_a_room_from_their_list(client: AsyncClient) -> None:
    alice = await register(client, "alice-leave@example.com", "Alice")
    await register(client, "bob-leave@example.com", "Bob")
    delivery = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": str(uuid4())},
        json={"content": "hello", "match": True},
    )
    room_id = delivery.json()["data"]["chatRoom"]["id"]

    removed = await client.delete(f"/api/v1/chat-rooms/{room_id}", headers=alice)
    assert removed.status_code == 204

    rooms = await client.get("/api/v1/chat-rooms", headers=alice)
    assert room_id not in {room["id"] for room in rooms.json()["data"]}
