from app.core.config import Settings


def test_railway_postgres_url_uses_async_driver() -> None:
    settings = Settings(
        _env_file=None,
        database_url="postgresql://postgres:secret@postgres.railway.internal:5432/railway",
    )

    assert settings.database_url == (
        "postgresql+asyncpg://postgres:secret@postgres.railway.internal:5432/railway"
    )


def test_railway_cors_origins_are_optional_and_explicit() -> None:
    settings = Settings(
        _env_file=None,
        cors_origins=["https://frontend.up.railway.app"],
    )

    assert [str(origin).rstrip("/") for origin in settings.cors_origins] == [
        "https://frontend.up.railway.app"
    ]
