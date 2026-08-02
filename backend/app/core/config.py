from functools import lru_cache
from typing import Literal

from pydantic import AnyHttpUrl, Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.moderation.crypto import decode_moderation_encryption_key

MATCHING_EMBEDDING_DIMENSIONS = 1024


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        extra="ignore",
        hide_input_in_errors=True,
    )

    app_name: str = "SlowTalk API"
    app_environment: Literal["development", "test", "production"] = "development"
    allow_development_moderation_fallback: bool = False
    api_prefix: str = "/api/v1"
    database_url: str = "postgresql+asyncpg://slowtalk:slowtalk@localhost:5432/slowtalk"
    cors_origins: list[AnyHttpUrl] = Field(default_factory=list)
    jwt_secret: str = Field("development-only-secret-change-me", min_length=32)
    access_token_ttl_seconds: int = 900
    refresh_token_ttl_seconds: int = 2_592_000
    max_upload_bytes: int = 5 * 1024 * 1024
    upstage_api_key: SecretStr | None = None
    upstage_base_url: AnyHttpUrl = AnyHttpUrl("https://api.upstage.ai/v1")
    upstage_chat_model: str | None = None
    upstage_embedding_model: str | None = None
    embedding_dimensions: int = MATCHING_EMBEDDING_DIMENSIONS
    match_min_similarity: float | None = Field(default=None, ge=0, le=1)
    matching_mode: Literal["disabled", "shadow", "enforce"] = "disabled"
    moderation_mode: Literal["shadow", "enforce"] = "shadow"
    moderation_allow_confidence: float | None = Field(default=None, ge=0, le=1)
    moderation_block_confidence: float | None = Field(default=None, ge=0, le=1)
    moderation_encryption_key: SecretStr | None = None
    content_hash_pepper: SecretStr | None = None
    internal_moderation_token: SecretStr | None = None

    @field_validator("database_url", mode="before")
    @classmethod
    def normalize_database_url(cls, value: object) -> str:
        """Accept Railway's standard Postgres URL in addition to local async URLs."""
        if not isinstance(value, str):
            raise ValueError("database URL must be a string")
        if value.startswith("postgres://"):
            return "postgresql+asyncpg://" + value.removeprefix("postgres://")
        if value.startswith("postgresql://"):
            return "postgresql+asyncpg://" + value.removeprefix("postgresql://")
        return value

    @field_validator("embedding_dimensions")
    @classmethod
    def validate_embedding_dimensions(cls, value: int) -> int:
        if value != MATCHING_EMBEDDING_DIMENSIONS:
            raise ValueError(
                f"Solar Embedding 2 requires {MATCHING_EMBEDDING_DIMENSIONS} dimensions"
            )
        return value

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
            assert self.moderation_encryption_key is not None
            try:
                decode_moderation_encryption_key(
                    self.moderation_encryption_key.get_secret_value()
                )
            except ValueError:
                raise ValueError(
                    "enforce moderation encryption key is invalid"
                ) from None
        if (
            self.moderation_allow_confidence is not None
            and self.moderation_block_confidence is not None
            and self.moderation_allow_confidence >= self.moderation_block_confidence
        ):
            raise ValueError("allow confidence must be lower than block confidence")
        if self.moderation_mode == "enforce" and self.internal_moderation_token is not None:
            token = self.internal_moderation_token.get_secret_value()
            if not token.strip() or len(token.encode("utf-8")) < 32:
                raise ValueError("internal moderation token must be at least 32 UTF-8 bytes")
        return self


def moderation_configuration_complete(settings: Settings) -> bool:
    provider_values = (
        settings.upstage_api_key.get_secret_value()
        if settings.upstage_api_key is not None
        else None,
        settings.upstage_chat_model,
        settings.moderation_allow_confidence,
        settings.moderation_block_confidence,
    )
    if any(
        value is None or (isinstance(value, str) and not value.strip())
        for value in provider_values
    ):
        return False

    if settings.moderation_mode == "shadow":
        return True

    enforce_values = (
        settings.moderation_encryption_key.get_secret_value()
        if settings.moderation_encryption_key is not None
        else None,
        settings.content_hash_pepper.get_secret_value()
        if settings.content_hash_pepper is not None
        else None,
        settings.internal_moderation_token.get_secret_value()
        if settings.internal_moderation_token is not None
        else None,
    )
    if any(
        value is None or (isinstance(value, str) and not value.strip())
        for value in enforce_values
    ):
        return False

    assert settings.moderation_encryption_key is not None
    try:
        decode_moderation_encryption_key(
            settings.moderation_encryption_key.get_secret_value()
        )
    except ValueError:
        return False
    return True


def matching_configuration_complete(settings: Settings) -> bool:
    if settings.matching_mode == "disabled":
        return True
    provider_values = (
        settings.upstage_api_key.get_secret_value()
        if settings.upstage_api_key is not None
        else None,
        settings.upstage_embedding_model,
        settings.match_min_similarity,
    )
    return not any(
        value is None or (isinstance(value, str) and not value.strip())
        for value in provider_values
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
