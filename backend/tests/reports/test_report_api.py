from httpx import AsyncClient

from tests.letters.test_letter_delivery import register


async def test_report_analysis_is_source_bound_and_single_use(client: AsyncClient) -> None:
    headers = await register(client, "report@example.com", "회고작성자")
    content = "오늘은 작은 일도 끝까지 해냈다."
    analysis = await client.post(
        "/api/v1/reports/feedback", headers=headers, json={"content": content}
    )
    assert analysis.status_code == 201
    analysis_id = analysis.json()["data"]["analysisId"]

    tampered = await client.post(
        "/api/v1/reports",
        headers=headers,
        json={"content": content + " 변경", "analysisId": analysis_id},
    )
    assert tampered.status_code == 409

    created = await client.post(
        "/api/v1/reports",
        headers=headers,
        json={"content": content, "analysisId": analysis_id},
    )
    assert created.status_code == 201
    report_id = created.json()["data"]["id"]

    replay = await client.post(
        "/api/v1/reports",
        headers=headers,
        json={"content": content, "analysisId": analysis_id},
    )
    assert replay.status_code == 409
    assert (await client.get(f"/api/v1/reports/{report_id}", headers=headers)).status_code == 200
