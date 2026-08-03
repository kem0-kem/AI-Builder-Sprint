from uuid import uuid4

from httpx import AsyncClient


async def signup(client: AsyncClient) -> dict[str, str]:
    response = await client.post(
        "/api/v1/auth/signup",
        json={"email": "profile@example.com", "password": "strong-pass", "nickname": "초기"},
    )
    return {"Authorization": f"Bearer {response.json()['data']['accessToken']}"}


async def signup_as(client: AsyncClient, email: str, nickname: str) -> dict[str, str]:
    response = await client.post(
        "/api/v1/auth/signup",
        json={"email": email, "password": "strong-pass", "nickname": nickname},
    )
    return {"Authorization": f"Bearer {response.json()['data']['accessToken']}"}


async def test_profile_interests_and_region(client: AsyncClient) -> None:
    headers = await signup(client)
    interests = await client.get("/api/v1/interests")
    ids = [item["id"] for item in interests.json()["data"][:3]]

    replaced = await client.put(
        "/api/v1/users/me/interests", headers=headers, json={"interestIds": ids}
    )
    assert replaced.status_code == 200
    assert len(replaced.json()["data"]["interests"]) == 3

    too_many = await client.put(
        "/api/v1/users/me/interests", headers=headers, json={"interestIds": ids + [ids[0]]}
    )
    assert too_many.status_code == 400
    assert too_many.json()["error"]["code"] == "VALIDATION_ERROR"

    updated = await client.patch(
        "/api/v1/users/me",
        headers=headers,
        json={
            "nickname": "새별명",
            "region": {
                "provinceCode": "11",
                "districtCode": "11440",
                "subDistrictCode": "1144066000",
            },
        },
    )
    assert updated.status_code == 200
    assert updated.json()["data"]["region"]["district"]["name"] == "마포구"


async def test_region_hierarchy(client: AsyncClient) -> None:
    provinces = await client.get("/api/v1/regions/provinces")
    districts = await client.get("/api/v1/regions/provinces/11/districts")
    sub_districts = await client.get("/api/v1/regions/districts/11440/sub-districts")
    assert provinces.status_code == districts.status_code == sub_districts.status_code == 200


async def test_profile_statistics_count_only_delivered_letters_and_user_matches(
    client: AsyncClient,
) -> None:
    alice = await signup_as(client, "statistics-alice@example.com", "Alice")
    bob = await signup_as(client, "statistics-bob@example.com", "Bob")

    empty = await client.get("/api/v1/users/me", headers=alice)
    assert empty.status_code == 200
    assert empty.json()["data"]["statistics"] == {
        "sentLetters": 0,
        "receivedLetters": 0,
        "matchCount": 0,
    }

    personal = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": str(uuid4())},
        json={"content": "private note", "match": False},
    )
    assert personal.status_code == 201

    delivered = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": str(uuid4())},
        json={"content": "hello", "match": True},
    )
    assert delivered.status_code == 201

    # Register the unrelated user after matching so Bob is the only candidate.
    carol = await signup_as(client, "statistics-carol@example.com", "Carol")

    alice_profile = await client.get("/api/v1/users/me", headers=alice)
    bob_profile = await client.get("/api/v1/users/me", headers=bob)
    carol_profile = await client.get("/api/v1/users/me", headers=carol)

    assert alice_profile.json()["data"]["statistics"] == {
        "sentLetters": 1,
        "receivedLetters": 0,
        "matchCount": 1,
    }
    assert bob_profile.json()["data"]["statistics"] == {
        "sentLetters": 0,
        "receivedLetters": 1,
        "matchCount": 1,
    }
    assert carol_profile.json()["data"]["statistics"] == {
        "sentLetters": 0,
        "receivedLetters": 0,
        "matchCount": 0,
    }
