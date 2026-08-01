from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "SlowTalk API"
    api_prefix: str = "/api/v1"
    database_url: str = "postgresql+asyncpg://slowtalk:slowtalk@localhost:5432/slowtalk"
    jwt_secret: str = Field("development-only-secret-change-me", min_length=32)
    access_token_ttl_seconds: int = 900
    refresh_token_ttl_seconds: int = 2_592_000
    max_upload_bytes: int = 5 * 1024 * 1024


@lru_cache
def get_settings() -> Settings:
    return Settings()
