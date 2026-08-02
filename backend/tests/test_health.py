from httpx import ASGITransport, AsyncClient

from app.main import create_app


async def test_health_uses_common_envelope() -> None:
    async with AsyncClient(
        transport=ASGITransport(app=create_app()), base_url="http://test"
    ) as client:
        response = await client.get("/api/v1/health")

    assert response.status_code == 200
    assert response.json() == {
        "ok": True,
        "data": {"status": "alive"},
        "error": None,
        "meta": None,
    }


async def test_readiness_reports_moderation_mode() -> None:
    async with AsyncClient(
        transport=ASGITransport(app=create_app()), base_url="http://test"
    ) as client:
        response = await client.get("/api/v1/ready")

    assert response.status_code == 200
    assert response.json()["data"] == {
        "status": "ready",
        "moderationMode": "shadow",
    }
