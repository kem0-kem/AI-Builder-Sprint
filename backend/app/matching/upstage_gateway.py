import json
from operator import attrgetter

import httpx
from pydantic import BaseModel, ConfigDict, Field, SecretStr, ValidationError

from app.matching.gateway import (
    EmbeddingDimensionMismatch,
    EmbeddingProviderUnavailable,
    EmbeddingVector,
)

QUERY_MODEL_ALIAS = "embedding-query"
PASSAGE_MODEL_ALIAS = "embedding-passage"


class _EmbeddingItem(BaseModel):
    model_config = ConfigDict(extra="ignore")

    index: int = Field(ge=0)
    embedding: list[float]


class _EmbeddingResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    data: list[_EmbeddingItem] = Field(min_length=1)


class UpstageEmbeddingGateway:
    def __init__(
        self,
        *,
        client: httpx.AsyncClient,
        api_key: SecretStr,
        model: str,
        expected_dimensions: int,
    ) -> None:
        self.client = client
        self.api_key = api_key
        self.model = model
        self.expected_dimensions = expected_dimensions

    async def embed_query(self, text: str) -> EmbeddingVector:
        vectors = await self._embed(QUERY_MODEL_ALIAS, text, expected_count=1)
        return vectors[0]

    async def embed_passages(self, texts: list[str]) -> list[EmbeddingVector]:
        if not texts:
            return []
        return await self._embed(PASSAGE_MODEL_ALIAS, texts, expected_count=len(texts))

    async def _embed(
        self,
        model_alias: str,
        inputs: str | list[str],
        *,
        expected_count: int,
    ) -> list[EmbeddingVector]:
        try:
            response = await self.client.post(
                "/embeddings",
                headers={
                    "Authorization": f"Bearer {self.api_key.get_secret_value()}"
                },
                json={"model": model_alias, "input": inputs},
            )
            response.raise_for_status()
        except (httpx.HTTPError, UnicodeEncodeError):
            raise EmbeddingProviderUnavailable("embedding provider unavailable") from None

        try:
            envelope = _EmbeddingResponse.model_validate(response.json())
        except (json.JSONDecodeError, UnicodeDecodeError, ValidationError):
            raise EmbeddingProviderUnavailable("embedding provider unavailable") from None

        ordered = sorted(envelope.data, key=attrgetter("index"))
        if len(ordered) != expected_count or [item.index for item in ordered] != list(
            range(expected_count)
        ):
            raise EmbeddingProviderUnavailable("embedding provider unavailable")

        vectors: list[EmbeddingVector] = []
        for item in ordered:
            if len(item.embedding) != self.expected_dimensions:
                raise EmbeddingDimensionMismatch(
                    expected=self.expected_dimensions,
                    actual=len(item.embedding),
                )
            try:
                vectors.append(EmbeddingVector(values=item.embedding))
            except ValidationError:
                raise EmbeddingProviderUnavailable("embedding provider unavailable") from None
        return vectors
