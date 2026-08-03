from httpx import AsyncClient

from tests.letters.test_letter_delivery import register


async def create_feed(client: AsyncClient, headers: dict[str, str]) -> str:
    categories = await client.get("/api/v1/feed-categories")
    category_id = categories.json()["data"][0]["id"]
    response = await client.post(
        "/api/v1/feeds",
        headers=headers,
        json={"categoryId": category_id, "title": "오늘", "content": "느리게 걸었다."},
    )
    assert response.status_code == 201
    return response.json()["data"]["id"]


async def test_feed_lifecycle_ownership_and_soft_delete(client: AsyncClient) -> None:
    alice = await register(client, "feed-alice@example.com", "앨리스")
    bob = await register(client, "feed-bob@example.com", "밥")
    feed_id = await create_feed(client, alice)

    forbidden = await client.patch(
        f"/api/v1/feeds/{feed_id}", headers=bob, json={"title": "가로채기"}
    )
    assert forbidden.status_code == 403

    assert (await client.put(f"/api/v1/feeds/{feed_id}/like", headers=bob)).status_code == 200
    assert (await client.put(f"/api/v1/feeds/{feed_id}/like", headers=bob)).status_code == 200
    detail = await client.get(f"/api/v1/feeds/{feed_id}", headers=bob)
    assert detail.json()["data"]["likeCount"] == 1

    deleted = await client.delete(f"/api/v1/feeds/{feed_id}", headers=alice)
    assert deleted.status_code == 204
    assert (await client.get(f"/api/v1/feeds/{feed_id}", headers=alice)).status_code == 404


async def test_one_level_comments_and_idempotent_reports(client: AsyncClient) -> None:
    alice = await register(client, "comment-alice@example.com", "앨리스")
    bob = await register(client, "comment-bob@example.com", "밥")
    feed_id = await create_feed(client, alice)
    parent = await client.post(
        f"/api/v1/feeds/{feed_id}/comments", headers=bob, json={"content": "좋아요"}
    )
    parent_id = parent.json()["data"]["id"]
    reply = await client.post(
        f"/api/v1/feeds/{feed_id}/comments",
        headers=alice,
        json={"content": "고마워요", "parentCommentId": parent_id},
    )
    nested = await client.post(
        f"/api/v1/feeds/{feed_id}/comments",
        headers=bob,
        json={"content": "중첩", "parentCommentId": reply.json()["data"]["id"]},
    )
    assert nested.status_code == 400

    for _ in range(2):
        report = await client.post(
            f"/api/v1/feeds/{feed_id}/reports",
            headers=bob,
            json={"reason": "검토 요청"},
        )
        assert report.status_code == 201
