from httpx import AsyncClient

from tests.letters.test_letter_delivery import register


async def test_anonymous_candidates_create_group_meeting(client: AsyncClient) -> None:
    alice = await register(client, "meeting-alice@example.com", "앨리스")
    await register(client, "meeting-bob@example.com", "밥")
    candidates = await client.get("/api/v1/meeting-invite-candidates", headers=alice)
    assert candidates.status_code == 200
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
