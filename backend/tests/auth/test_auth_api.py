import pytest
from httpx import AsyncClient


async def test_signup_login_refresh_rotation_and_logout(client: AsyncClient) -> None:
    payload = {"email": "hello@example.com", "password": "strong-pass", "nickname": "느린이"}
    signup = await client.post("/api/v1/auth/signup", json=payload)
    assert signup.status_code == 201
    assert signup.json()["data"]["accessToken"]

    duplicate = await client.post("/api/v1/auth/signup", json=payload)
    assert duplicate.status_code == 409
    assert duplicate.json()["error"]["code"] == "EMAIL_ALREADY_EXISTS"

    login = await client.post("/api/v1/auth/login", json=payload)
    assert login.status_code == 200
    old_refresh = login.json()["data"]["refreshToken"]

    rotated = await client.post("/api/v1/auth/token/refresh", json={"refreshToken": old_refresh})
    assert rotated.status_code == 200
    assert rotated.json()["data"]["refreshToken"] != old_refresh

    replay = await client.post("/api/v1/auth/token/refresh", json={"refreshToken": old_refresh})
    assert replay.status_code == 401

    access_token = rotated.json()["data"]["accessToken"]
    new_refresh = rotated.json()["data"]["refreshToken"]
    logout = await client.post(
        "/api/v1/auth/logout",
        headers={"Authorization": f"Bearer {access_token}"},
        json={"refreshToken": new_refresh},
    )
    assert logout.status_code == 204


async def test_email_availability(client: AsyncClient) -> None:
    available = await client.get(
        "/api/v1/auth/email-availability", params={"email": "new@example.com"}
    )
    assert available.json()["data"] == {"available": True}


async def test_username_availability_and_case_normalization(client: AsyncClient) -> None:
    available = await client.get(
        "/api/v1/auth/check-username", params={"username": "Test_User"}
    )
    assert available.status_code == 200
    assert available.json()["data"] == {"available": True}

    created = await client.post(
        "/api/v1/auth/signup",
        json={
            "email": "username@example.com",
            "password": "strong-pass",
            "nickname": "display name",
            "username": "Test_User",
        },
    )
    assert created.status_code == 201

    unavailable = await client.get(
        "/api/v1/auth/check-username", params={"username": "TEST_USER"}
    )
    assert unavailable.status_code == 200
    assert unavailable.json()["data"] == {"available": False}


async def test_duplicate_username_signup_is_rejected(client: AsyncClient) -> None:
    first = {
        "email": "first-username@example.com",
        "password": "strong-pass",
        "nickname": "first",
        "username": "same_user",
    }
    second = {**first, "email": "second-username@example.com", "nickname": "second"}

    assert (await client.post("/api/v1/auth/signup", json=first)).status_code == 201
    duplicate = await client.post("/api/v1/auth/signup", json=second)

    assert duplicate.status_code == 409
    assert duplicate.json()["error"]["code"] == "USERNAME_ALREADY_EXISTS"


@pytest.mark.parametrize("username", ["ab", "has-hyphen", "한글id"])
async def test_username_availability_rejects_invalid_values(
    client: AsyncClient, username: str
) -> None:
    response = await client.get(
        "/api/v1/auth/check-username", params={"username": username}
    )
    assert response.status_code == 422


async def test_legacy_signup_without_username_still_works(client: AsyncClient) -> None:
    response = await client.post(
        "/api/v1/auth/signup",
        json={
            "email": "legacy@example.com",
            "password": "strong-pass",
            "nickname": "legacy user",
        },
    )
    assert response.status_code == 201
