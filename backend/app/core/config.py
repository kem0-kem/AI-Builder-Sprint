from functools import lru_cache
from typing import Literal

from pydantic import AnyHttpUrl, Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        extra="ignore",
        hide_input_in_errors=True,
    )

    app_name: str = "SlowTalk API"
    api_prefix: str = "/api/v1"
    database_url: str = "postgresql+asyncpg://slowtalk:slowtalk@localhost:5432/slowtalk"
    jwt_secret: str = Field("development-only-secret-change-me", min_length=32)
    access_token_ttl_seconds: int = 900
    refresh_token_ttl_seconds: int = 2_592_000
    max_upload_bytes: int = 5 * 1024 * 1024
    upstage_api_key: SecretStr | None = None
    upstage_base_url: AnyHttpUrl = AnyHttpUrl("https://api.upstage.ai/v1")
    upstage_chat_model: str | None = None
    moderation_mode: Literal["shadow", "enforce"] = "shadow"
    moderation_allow_confidence: float | None = Field(default=None, ge=0, le=1)
    moderation_block_confidence: float | None = Field(default=None, ge=0, le=1)
    moderation_encryption_key: SecretStr | None = None
    content_hash_pepper: SecretStr | None = None
    internal_moderation_token: SecretStr | None = None

    @model_validator(mode="after")
    def validate_moderation(self) -> "Settings":
        if self.upstage_base_url.scheme != "https":
            raise ValueError("Upstage base URL must use HTTPS")
        if self.moderation_mode == "enforce":
            required = (
                self.upstage_api_key.get_secret_value() if self.upstage_api_key else None,
                self.upstage_chat_model,
                self.moderation_encryption_key.get_secret_value()
                if self.moderation_encryption_key
                else None,
                self.content_hash_pepper.get_secret_value() if self.content_hash_pepper else None,
                self.internal_moderation_token.get_secret_value()
                if self.internal_moderation_token
                else None,
                self.moderation_allow_confidence,
                self.moderation_block_confidence,
            )
            if any(
                value is None or (isinstance(value, str) and not value.strip())
                for value in required
            ):
                raise ValueError(
                    "enforce moderation requires provider, encryption, and confidence configuration"
                )
        if (
            self.moderation_allow_confidence is not None
            and self.moderation_block_confidence is not None
            and self.moderation_allow_confidence >= self.moderation_block_confidence
        ):
            raise ValueError("allow confidence must be lower than block confidence")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
