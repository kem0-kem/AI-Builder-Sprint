import math
from typing import Protocol

from pydantic import BaseModel, field_validator


class EmbeddingProviderUnavailable(RuntimeError):
    """Raised when the embedding provider cannot return a trustworthy response."""


class EmbeddingDimensionMismatch(RuntimeError):
    def __init__(self, *, expected: int, actual: int) -> None:
        super().__init__(f"embedding dimension mismatch: expected {expected}, got {actual}")
        self.expected = expected
        self.actual = actual


class EmbeddingVector(BaseModel):
    values: list[float]

    @field_validator("values")
    @classmethod
    def validate_finite_values(cls, values: list[float]) -> list[float]:
        if not values or any(not math.isfinite(value) for value in values):
            raise ValueError("embedding must contain finite values")
        return values


class EmbeddingGateway(Protocol):
    async def embed_query(self, text: str) -> EmbeddingVector:
        raise NotImplementedError

    async def embed_passages(self, texts: list[str]) -> list[EmbeddingVector]:
        raise NotImplementedError
