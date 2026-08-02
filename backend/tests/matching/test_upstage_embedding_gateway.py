import json
from unittest.mock import AsyncMock

import httpx
import pytest
import respx
from pydantic import SecretStr

from app.matching.gateway import (
    EmbeddingDimensionMismatch,
    EmbeddingProviderUnavailable,
)
from app.matching.upstage_gateway import UpstageEmbeddingGateway


def build_gateway(client: httpx.AsyncClient, *, dimensions: int = 3) -> UpstageEmbeddingGateway:
    return UpstageEmbeddingGateway(
        client=client,
        api_key=SecretStr("test-api-key"),
        model="solar-embedding-2",
        expected_dimensions=dimensions,
    )


def embedding_response(vectors: list[list[float]]) -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "data": [
                {"index": index, "embedding": vector}
                for index, vector in enumerate(vectors)
            ]
        },
    )


@pytest.mark.asyncio
@respx.mock
async def test_query_uses_query_alias_and_bearer_auth() -> None:
    route = respx.post("https://api.upstage.ai/v1/embeddings").mock(
        return_value=embedding_response([[0.1, 0.2, 0.3]])
    )
    async with httpx.AsyncClient(base_url="https://api.upstage.ai/v1") as client:
        vector = await build_gateway(client).embed_query("오늘의 편지")

    request = json.loads(route.calls[0].request.content)
    assert request == {"model": "embedding-query", "input": "오늘의 편지"}
    assert route.calls[0].request.headers["Authorization"] == "Bearer test-api-key"
    assert vector.values == [0.1, 0.2, 0.3]


@pytest.mark.asyncio
@respx.mock
async def test_passage_batch_uses_passage_alias_and_preserves_index_order() -> None:
    route = respx.post("https://api.upstage.ai/v1/embeddings").mock(
        return_value=httpx.Response(
            200,
            json={
                "data": [
                    {"index": 1, "embedding": [0.4, 0.5, 0.6]},
                    {"index": 0, "embedding": [0.1, 0.2, 0.3]},
                ]
            },
        )
    )
    async with httpx.AsyncClient(base_url="https://api.upstage.ai/v1") as client:
        vectors = await build_gateway(client).embed_passages(["첫 편지", "둘째 편지"])

    request = json.loads(route.calls[0].request.content)
    assert request == {
        "model": "embedding-passage",
        "input": ["첫 편지", "둘째 편지"],
    }
    assert [vector.values for vector in vectors] == [
        [0.1, 0.2, 0.3],
        [0.4, 0.5, 0.6],
    ]


@pytest.mark.asyncio
async def test_timeout_maps_to_provider_unavailable_without_private_input() -> None:
    client = AsyncMock(spec=httpx.AsyncClient)
    client.post.side_effect = httpx.ReadTimeout("private-input-marker")

    with pytest.raises(EmbeddingProviderUnavailable) as caught:
        await build_gateway(client).embed_query("private-input-marker")

    assert str(caught.value) == "embedding provider unavailable"
    assert "private-input-marker" not in str(caught.value)
    assert caught.value.__cause__ is None


@pytest.mark.asyncio
@respx.mock
async def test_dimension_mismatch_is_distinct_from_provider_failure() -> None:
    respx.post("https://api.upstage.ai/v1/embeddings").mock(
        return_value=embedding_response([[0.1, 0.2]])
    )
    async with httpx.AsyncClient(base_url="https://api.upstage.ai/v1") as client:
        with pytest.raises(EmbeddingDimensionMismatch) as caught:
            await build_gateway(client).embed_query("probe")

    assert caught.value.expected == 3
    assert caught.value.actual == 2


@pytest.mark.asyncio
@respx.mock
async def test_non_finite_vector_is_rejected() -> None:
    respx.post("https://api.upstage.ai/v1/embeddings").mock(
        return_value=httpx.Response(
            200,
            content=b'{"data":[{"index":0,"embedding":[0.1,NaN,0.3]}]}',
        )
    )
    async with httpx.AsyncClient(base_url="https://api.upstage.ai/v1") as client:
        with pytest.raises(EmbeddingProviderUnavailable):
            await build_gateway(client).embed_query("probe")


@pytest.mark.asyncio
@respx.mock
async def test_missing_or_duplicate_batch_indexes_are_rejected() -> None:
    respx.post("https://api.upstage.ai/v1/embeddings").mock(
        return_value=httpx.Response(
            200,
            json={
                "data": [
                    {"index": 0, "embedding": [0.1, 0.2, 0.3]},
                    {"index": 0, "embedding": [0.4, 0.5, 0.6]},
                ]
            },
        )
    )
    async with httpx.AsyncClient(base_url="https://api.upstage.ai/v1") as client:
        with pytest.raises(EmbeddingProviderUnavailable):
            await build_gateway(client).embed_passages(["one", "two"])
