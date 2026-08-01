from httpx import AsyncClient


async def signup(client: AsyncClient) -> dict[str, str]:
    response = await client.post(
        "/api/v1/auth/signup",
        json={"email": "profile@example.com", "password": "strong-pass", "nickname": "초기"},
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
