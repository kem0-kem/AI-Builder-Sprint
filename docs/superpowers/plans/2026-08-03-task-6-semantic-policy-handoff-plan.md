# Task 6 Solar Semantic Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rank eligible SlowTalk users with Solar query embeddings, enforce the configured similarity threshold, and fall back to the profile policy without exposing scores, content, or vectors.

**Architecture:** `SemanticMatchingPolicy` orchestrates the existing embedding gateway, a pgvector search method on `MatchingRepository`, and the profile fallback policy. The repository owns SQL ordering and model-version filtering; the policy owns threshold and failure decisions; Task 7 remains responsible for final locking, persistence, and public response serialization.

**Tech Stack:** Python 3.11, FastAPI settings, SQLAlchemy 2 async, PostgreSQL 16, pgvector HNSW/cosine distance, pytest, testcontainers, Ruff, mypy

## Global Constraints

- This handoff owns semantic ranking only. Do not modify letter delivery, `MatchHistory` writes, OpenAPI responses, shadow rollout, backfill, or metrics implementation.
- Start only after Task 4 and Task 5 are present on the branch and their focused tests pass.
- Rebase onto a branch containing Task 3 commit `5a6dfc0` or a descendant; do not copy eligibility SQL into another module.
- Consume `MatchingEligibilityPolicy.base_candidate_query(sender_id)` so self, inactive, blocked, and prior-match exclusions remain centralized.
- Query embeddings use `EmbeddingGateway.embed_query`; passage aliases are outside this task.
- Treat one comparable embedding space as `(provider="UPSTAGE", model_name, model_version,
  dimensions=1024)`. Search only `UserMatchVector` rows whose `model_name` and
  `model_version` both match the active space; the database vector type fixes dimension
  at 1024.
- PostgreSQL cosine search returns at most 20 candidates and orders deterministically by distance then `User.id`.
- Cosine similarity is calculated as `1 - cosine_distance` and has range `[-1, 1]`;
  configured `match_min_similarity` remains constrained to `[0, 1]`.
- A semantic result is accepted when `similarity >= match_min_similarity`. Equality at
  the threshold is `SEMANTIC`; only `similarity < match_min_similarity` falls back.
- Missing vectors, no semantic candidate, or a below-threshold best candidate use profile fallback reason `INSUFFICIENT_EMBEDDINGS`.
- `EmbeddingProviderUnavailable` and `EmbeddingDimensionMismatch` use profile fallback reason `PROVIDER_UNAVAILABLE`; unexpected programming errors propagate.
- If profile fallback also finds no eligible candidate, return `None`; Task 7 maps that to `409 MATCH_NOT_FOUND`.
- Never log or label metrics with sender ID, candidate ID, letter ID, content, vector values, or similarity values.
- `MatchCandidate.score`, model metadata, and vectors remain internal and must not enter public schemas.
- Do not modify or stage `.codex-remote-attachments/`.

## Prerequisite Contract Gate

Before changing code, confirm all commands succeed:

```powershell
git merge-base --is-ancestor 5a6dfc0 HEAD
Test-Path backend/app/matching/profile_policy.py
Test-Path backend/app/matching/embedding_worker.py
cd backend
python -m pytest tests/matching/test_profile_policy.py tests/matching/test_embedding_worker.py -q
```

Expected: the Git command exits 0, both `Test-Path` commands print `True`, and both focused suites pass.

Task 4 currently exposes this contract from `app.matching.profile_policy`; Task 6 extends
it with optional model metadata and keyword-only fallback metadata instead of defining a
second candidate type:

```python
@dataclass(frozen=True, slots=True)
class MatchCandidate:
    user_id: UUID
    strategy: MatchStrategy
    score: float
    fallback_reason: str | None = None
    model_name: str | None = None
    model_version: str | None = None


class ProfileMatchingPolicy:
    async def select(
        self,
        session: AsyncSession,
        sender_id: UUID,
        *,
        strategy: MatchStrategy = MatchStrategy.PROFILE,
        fallback_reason: str | None = None,
    ) -> MatchCandidate | None: ...
```

The Task 4 implementation already has the required `session` argument and numeric `score`;
preserve both. Add the two optional model fields and the two keyword-only override
arguments, then use those overrides when constructing its existing return value. Task 5
must persist normalized `UserMatchVector.embedding` rows with `model_name`,
`model_version`, `source_letter_ids`, and `source_count`. Do not duplicate
`MatchCandidate` or the profile ranking logic inside Task 6.

---

### Task 1: Semantic candidate query contract

**Files:**
- Modify: `backend/app/matching/repository.py`
- Create: `backend/tests/matching/test_semantic_repository.py`

**Interfaces:**
- Consumes: `MatchingEligibilityPolicy.base_candidate_query(sender_id)` and `UserMatchVector`
- Produces: `SemanticCandidate(user_id: UUID, similarity: float)` and
  `MatchingRepository.search_semantic_candidates(sender_id, query_vector, model_name,
  model_version, limit=20)`

- [ ] **Step 1: Write failing repository tests**

Create `backend/tests/matching/test_semantic_repository.py` with a SQL contract test:

```python
from unittest.mock import AsyncMock, Mock
from uuid import UUID

from sqlalchemy.dialects import postgresql
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.models import User
from app.matching.gateway import EmbeddingVector
from app.matching.repository import MatchingRepository, SemanticCandidate

SENDER_ID = UUID("00000000-0000-0000-0000-000000000001")
CANDIDATE_ID = UUID("00000000-0000-0000-0000-000000000002")


async def test_semantic_search_uses_cosine_model_filter_and_top_twenty() -> None:
    session = AsyncMock(spec=AsyncSession)
    result = Mock()
    result.all.return_value = []
    session.execute.return_value = result
    repository = MatchingRepository(session)

    candidates = await repository.search_semantic_candidates(
        SENDER_ID,
        EmbeddingVector(values=[1.0] * 1024),
        model_name="solar-embedding-2",
        model_version="solar-embedding-2",
        limit=100,
    )
    statement = session.execute.await_args.args[0]
    sql = str(
        statement.compile(
            dialect=postgresql.dialect(),
            compile_kwargs={"literal_binds": True},
        )
    )

    assert candidates == []
    assert "user_match_vectors.model_name = 'solar-embedding-2'" in sql
    assert "user_match_vectors.model_version = 'solar-embedding-2'" in sql
    assert "<=>" in sql
    assert "LIMIT 20" in sql
    assert "NOT (EXISTS" in sql
    assert "ORDER BY" in sql
```

Add a mapping test using a lightweight fake result rather than a database:

```python
async def test_semantic_search_maps_rows_without_exposing_vectors() -> None:
    session = AsyncMock(spec=AsyncSession)
    result = Mock()
    candidate = User(
        id=CANDIDATE_ID,
        email="candidate@example.com",
        password_hash="hash",
        nickname="candidate",
    )
    result.all.return_value = [(candidate, 0.91)]
    session.execute.return_value = result

    candidates = await MatchingRepository(session).search_semantic_candidates(
        SENDER_ID,
        EmbeddingVector(values=[0.5] * 1024),
        model_name="solar-embedding-2",
        model_version="solar-embedding-2",
    )

    assert candidates == [SemanticCandidate(user_id=CANDIDATE_ID, similarity=0.91)]
    assert not hasattr(candidates[0], "embedding")
```

- [ ] **Step 2: Run the repository tests and verify red state**

Run:

```powershell
cd backend
python -m pytest tests/matching/test_semantic_repository.py -q
```

Expected: import or attribute failure because `SemanticCandidate` and
`search_semantic_candidates` do not exist.

- [ ] **Step 3: Add the bounded pgvector search**

Add to `backend/app/matching/repository.py`:

```python
from dataclasses import dataclass

from app.matching.gateway import EmbeddingVector
from app.matching.models import UserMatchVector


@dataclass(frozen=True, slots=True)
class SemanticCandidate:
    user_id: UUID
    similarity: float


async def search_semantic_candidates(
    self,
    sender_id: UUID,
    query_vector: EmbeddingVector,
    model_name: str,
    model_version: str,
    *,
    limit: int = 20,
) -> list[SemanticCandidate]:
    bounded_limit = max(1, min(limit, 20))
    distance = UserMatchVector.embedding.cosine_distance(query_vector.values)
    statement = (
        self._eligibility.base_candidate_query(sender_id)
        .join(UserMatchVector, UserMatchVector.user_id == User.id)
        .where(
            UserMatchVector.model_name == model_name,
            UserMatchVector.model_version == model_version,
        )
        .add_columns((1 - distance).label("similarity"))
        .order_by(distance, User.id)
        .limit(bounded_limit)
    )
    rows = (await self._session.execute(statement)).all()
    return [
        SemanticCandidate(user_id=user.id, similarity=float(similarity))
        for user, similarity in rows
    ]
```

Keep this as a method of the existing `MatchingRepository`; do not create a second
repository class or duplicate Task 3 exclusion clauses.

- [ ] **Step 4: Verify repository green state**

Run:

```powershell
cd backend
python -m pytest tests/matching/test_semantic_repository.py tests/matching/test_eligibility.py -q
python -m ruff check app/matching/repository.py tests/matching/test_semantic_repository.py
python -m mypy app/matching/repository.py
```

Expected: every command exits 0.

- [ ] **Step 5: Commit the repository boundary**

```powershell
git add -- backend/app/matching/repository.py backend/tests/matching/test_semantic_repository.py
git commit -m "feat(matching): add bounded semantic search"
```

---

### Task 2: Threshold and profile fallback policy

**Files:**
- Modify: `backend/app/matching/profile_policy.py`
- Modify: `backend/tests/matching/test_profile_policy.py`
- Create: `backend/app/matching/semantic_policy.py`
- Create: `backend/tests/matching/test_semantic_policy.py`

**Interfaces:**
- Consumes: `EmbeddingGateway`, `MatchingRepository.search_semantic_candidates`, `ProfileMatchingPolicy`, and `MatchCandidate`
- Produces: `SemanticMatchingPolicy.select(session, sender_id: UUID, content: str) -> MatchCandidate | None`
- Produces: `SemanticSelectionObserver` protocol for bounded Task 8 instrumentation without identifiers or content

- [ ] **Step 1: Write failing policy tests with fakes**

Create the reusable dependency harness first:

```python
from dataclasses import dataclass
from unittest.mock import AsyncMock, Mock
from uuid import UUID

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.matching.gateway import EmbeddingGateway, EmbeddingVector
from app.matching.models import MatchStrategy
from app.matching.profile_policy import MatchCandidate, ProfileMatchingPolicy
from app.matching.repository import MatchingRepository, SemanticCandidate
from app.matching.semantic_policy import SemanticMatchingPolicy, SemanticSelectionObserver

SENDER_ID = UUID("00000000-0000-0000-0000-000000000001")
CANDIDATE_ID = UUID("00000000-0000-0000-0000-000000000002")
OTHER_ID = UUID("00000000-0000-0000-0000-000000000003")


@dataclass
class PolicyDependencies:
    session: AsyncMock
    gateway: AsyncMock
    repository: AsyncMock
    profile: AsyncMock
    observer: Mock

    def build(self, minimum_similarity: float = 0.7) -> SemanticMatchingPolicy:
        return SemanticMatchingPolicy(
            self.gateway,
            self.repository,
            self.profile,
            active_model_name="solar-embedding-2",
            active_model_version="solar-embedding-2",
            minimum_similarity=minimum_similarity,
            observer=self.observer,
        )


@pytest.fixture
def dependencies() -> PolicyDependencies:
    session = AsyncMock(spec=AsyncSession)
    gateway = AsyncMock(spec=EmbeddingGateway)
    gateway.embed_query.return_value = EmbeddingVector(values=[1.0] * 1024)
    repository = AsyncMock(spec=MatchingRepository)
    profile = AsyncMock(spec=ProfileMatchingPolicy)

    async def profile_fallback(
        session: AsyncSession,
        sender_id: UUID,
        *,
        strategy: MatchStrategy,
        fallback_reason: str | None,
    ) -> MatchCandidate:
        return MatchCandidate(
            user_id=OTHER_ID,
            strategy=strategy,
            score=0.42,
            fallback_reason=fallback_reason,
        )

    profile.select.side_effect = profile_fallback
    return PolicyDependencies(
        session=session,
        gateway=gateway,
        repository=repository,
        profile=profile,
        observer=Mock(spec=SemanticSelectionObserver),
    )
```

The tests must cover the following exact cases:

```python
async def test_selects_highest_candidate_at_or_above_threshold(
    dependencies: PolicyDependencies,
) -> None:
    dependencies.repository.search_semantic_candidates.return_value = [
        SemanticCandidate(CANDIDATE_ID, 0.91),
        SemanticCandidate(OTHER_ID, 0.82),
    ]
    result = await dependencies.build(minimum_similarity=0.9).select(
        dependencies.session, SENDER_ID, "오늘의 편지"
    )
    assert result == MatchCandidate(
        user_id=CANDIDATE_ID,
        strategy=MatchStrategy.SEMANTIC,
        score=0.91,
        model_name="solar-embedding-2",
        model_version="solar-embedding-2",
    )
    dependencies.profile.select.assert_not_awaited()


@pytest.mark.parametrize("candidates", [[], [SemanticCandidate(CANDIDATE_ID, 0.69)]])
async def test_missing_or_below_threshold_uses_profile_fallback(
    dependencies: PolicyDependencies,
    candidates: list[SemanticCandidate],
) -> None:
    dependencies.repository.search_semantic_candidates.return_value = candidates
    result = await dependencies.build(minimum_similarity=0.7).select(
        dependencies.session, SENDER_ID, "오늘의 편지"
    )
    dependencies.profile.select.assert_awaited_once_with(
        dependencies.session,
        SENDER_ID,
        strategy=MatchStrategy.PROFILE_FALLBACK,
        fallback_reason="INSUFFICIENT_EMBEDDINGS",
    )
    assert result is not None
    assert result.strategy is MatchStrategy.PROFILE_FALLBACK
    assert result.fallback_reason == "INSUFFICIENT_EMBEDDINGS"


@pytest.mark.parametrize(
    "failure",
    [
        EmbeddingProviderUnavailable("embedding provider unavailable"),
        EmbeddingDimensionMismatch(expected=1024, actual=3),
    ],
)
async def test_known_provider_failure_uses_redacted_profile_fallback(
    dependencies: PolicyDependencies,
    failure: Exception,
    caplog: pytest.LogCaptureFixture,
) -> None:
    dependencies.gateway.embed_query.side_effect = failure
    content = "절대 로그에 남지 않을 본문"
    result = await dependencies.build().select(dependencies.session, SENDER_ID, content)
    dependencies.observer.provider_failure.assert_called_once_with()
    dependencies.observer.profile_fallback.assert_called_once_with("PROVIDER_UNAVAILABLE")
    dependencies.profile.select.assert_awaited_once_with(
        dependencies.session,
        SENDER_ID,
        strategy=MatchStrategy.PROFILE_FALLBACK,
        fallback_reason="PROVIDER_UNAVAILABLE",
    )
    assert result is not None
    assert result.fallback_reason == "PROVIDER_UNAVAILABLE"
    assert content not in caplog.text
    assert str(SENDER_ID) not in caplog.text
    assert "[1.0" not in caplog.text


async def test_unexpected_programming_error_propagates(
    dependencies: PolicyDependencies,
) -> None:
    dependencies.gateway.embed_query.side_effect = TypeError("programmer error")
    with pytest.raises(TypeError, match="programmer error"):
        await dependencies.build().select(dependencies.session, SENDER_ID, "본문")


async def test_returns_none_when_profile_fallback_has_no_candidate(
    dependencies: PolicyDependencies,
) -> None:
    dependencies.repository.search_semantic_candidates.return_value = []
    dependencies.profile.select.return_value = None
    assert await dependencies.build().select(dependencies.session, SENDER_ID, "본문") is None
```

Also assert the observer receives only bounded no-argument provider/fallback calls and
that captured logs contain none of the content, vector components, sender ID, candidate
ID, or similarity value.

- [ ] **Step 2: Run policy tests and verify red state**

Run:

```powershell
cd backend
python -m pytest tests/matching/test_semantic_policy.py -q
```

Expected: import failure because `SemanticMatchingPolicy` does not exist.

- [ ] **Step 3: Implement the policy and observer boundary**

Create `backend/app/matching/semantic_policy.py` with these public contracts:

```python
from typing import Protocol
from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession

from app.matching.gateway import (
    EmbeddingDimensionMismatch,
    EmbeddingGateway,
    EmbeddingProviderUnavailable,
)
from app.matching.models import MatchStrategy
from app.matching.profile_policy import MatchCandidate, ProfileMatchingPolicy
from app.matching.repository import MatchingRepository

INSUFFICIENT_EMBEDDINGS = "INSUFFICIENT_EMBEDDINGS"
PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"


class SemanticSelectionObserver(Protocol):
    def provider_failure(self) -> None: ...
    def profile_fallback(self, reason: str) -> None: ...


class NoopSemanticSelectionObserver:
    def provider_failure(self) -> None:
        return None

    def profile_fallback(self, reason: str) -> None:
        return None
```

Implement `SemanticMatchingPolicy` with injected dependencies and no internally created
sessions or HTTP clients:

```python
class SemanticMatchingPolicy:
    def __init__(
        self,
        gateway: EmbeddingGateway,
        repository: MatchingRepository,
        profile_policy: ProfileMatchingPolicy,
        *,
        active_model_name: str,
        active_model_version: str,
        minimum_similarity: float,
        observer: SemanticSelectionObserver | None = None,
    ) -> None:
        if not active_model_name.strip() or not active_model_version.strip():
            raise ValueError("active embedding model name and version are required")
        if not 0 <= minimum_similarity <= 1:
            raise ValueError("minimum similarity must be between 0 and 1")
        self._gateway = gateway
        self._repository = repository
        self._profile = profile_policy
        self._active_model_name = active_model_name
        self._active_model_version = active_model_version
        self._minimum_similarity = minimum_similarity
        self._observer = observer or NoopSemanticSelectionObserver()

    async def select(
        self,
        session: AsyncSession,
        sender_id: UUID,
        content: str,
    ) -> MatchCandidate | None:
        try:
            query_vector = await self._gateway.embed_query(content)
        except (EmbeddingProviderUnavailable, EmbeddingDimensionMismatch):
            self._observer.provider_failure()
            return await self._fallback(session, sender_id, PROVIDER_UNAVAILABLE)

        candidates = await self._repository.search_semantic_candidates(
            sender_id,
            query_vector,
            model_name=self._active_model_name,
            model_version=self._active_model_version,
            limit=20,
        )
        if candidates and candidates[0].similarity >= self._minimum_similarity:
            best = candidates[0]
            return MatchCandidate(
                user_id=best.user_id,
                strategy=MatchStrategy.SEMANTIC,
                score=best.similarity,
                model_name=self._active_model_name,
                model_version=self._active_model_version,
            )
        return await self._fallback(session, sender_id, INSUFFICIENT_EMBEDDINGS)

    async def _fallback(
        self,
        session: AsyncSession,
        sender_id: UUID,
        reason: str,
    ) -> MatchCandidate | None:
        self._observer.profile_fallback(reason)
        return await self._profile.select(
            session,
            sender_id,
            strategy=MatchStrategy.PROFILE_FALLBACK,
            fallback_reason=reason,
        )
```

Do not catch `TypeError`, `ValueError`, SQLAlchemy errors, cancellation, or arbitrary
exceptions. Task 7/8 owns caller behavior and operational reporting beyond the two known
provider contract failures.

- [ ] **Step 4: Verify policy green state**

Run:

```powershell
cd backend
python -m pytest tests/matching/test_semantic_policy.py -q
python -m ruff check app/matching/semantic_policy.py tests/matching/test_semantic_policy.py
python -m mypy app/matching/semantic_policy.py
```

Expected: threshold boundary, fallback reasons, known provider failures, unexpected error
propagation, and redaction assertions all pass.

- [ ] **Step 5: Commit semantic policy behavior**

```powershell
git add -- backend/app/matching/semantic_policy.py backend/tests/matching/test_semantic_policy.py
git commit -m "feat(matching): add semantic threshold policy"
```

---

### Task 3: Real PostgreSQL pgvector ranking contract

**Files:**
- Create: `backend/tests/matching/test_pgvector_search.py`
- Modify only if required by a reproduced defect: `backend/app/matching/repository.py`

**Interfaces:**
- Consumes: migrations through `0005_usernames`, Task 3 eligibility, Task 5 vectors, and Task 1 search
- Produces: integration evidence for exact cosine order, version filtering, eligibility filtering, and the 20-row cap

- [ ] **Step 1: Create the Docker-gated integration test**

Follow `backend/tests/matching/test_pgvector_migration.py` and use
`PostgresContainer("pgvector/pgvector:pg16", driver="psycopg")`. Mark the module:

```python
pytestmark = pytest.mark.skipif(
    os.getenv("RUN_POSTGRES_INTEGRATION") != "1",
    reason="set RUN_POSTGRES_INTEGRATION=1 when Docker is available",
)
```

The test must:

1. Upgrade a fresh database to `head`.
2. Insert one sender, 23 active candidates, one inactive near candidate, one blocked near
   candidate, and one previously matched near candidate.
3. Insert `UserMatchVector` rows for the active embedding space, a closer row with a
   different model name, and another closer row with a different model version.
4. Query with a 1024-dimensional non-zero vector through
   `MatchingRepository.search_semantic_candidates`.
5. Assert inactive, blocked, prior-match, self, and wrong-version users are absent.
6. Assert similarities are descending, equal-distance ties use ascending `User.id`, and
   exactly 20 results are returned.
7. Assert the first result is the mathematically nearest eligible active-model vector.

Use small non-zero variations in the first two vector dimensions and zero-fill the
remaining 1022 dimensions. Do not use zero vectors because cosine HNSW excludes them.

- [ ] **Step 2: Run the real database test**

Run with Docker available:

```powershell
cd backend
$env:RUN_POSTGRES_INTEGRATION='1'
python -m pytest tests/matching/test_pgvector_search.py -q
Remove-Item Env:RUN_POSTGRES_INTEGRATION
```

Expected: the pgvector integration test passes. If Docker is unavailable, do not claim
Task 6 completion; record the environment blocker and leave the test skipped in the
ordinary suite.

- [ ] **Step 3: Run the complete matching regression suite**

```powershell
cd backend
python -m pytest tests/matching -q
python -m ruff check app tests migrations
python -m mypy app
python -m pytest -q
git diff --check
```

Expected: all commands exit 0. The ordinary suite may retain only documented
environment-gated skips; the explicit Docker run from Step 2 must pass.

- [ ] **Step 4: Perform privacy and contract review**

Inspect the diff and confirm:

- No public schema or OpenAPI file changed.
- No log includes content, vector values, similarity, or user identifiers.
- `score` stays internal for every strategy; only `SEMANTIC` candidates carry model
  metadata.
- Fallback candidates use `PROFILE_FALLBACK` and one of the two bounded reasons.
- No repository or policy commits the session.
- Final candidate locking and `MatchHistory` persistence remain absent because Task 7
  owns them.

- [ ] **Step 5: Commit integration evidence**

```powershell
git add -- backend/tests/matching/test_pgvector_search.py
git commit -m "test(matching): verify pgvector semantic ranking"
```

## Handoff Completion Criteria

The Task 6 assignee may mark the work complete only when all of these are true:

- Prerequisite Task 4 and Task 5 contracts are present and passing.
- Unit tests prove threshold equality, ranking, both fallback reasons, no-profile `None`,
  known provider failure handling, and unexpected exception propagation.
- Repository SQL is bounded to 20, filters both active model name and version, relies on
  the schema-fixed 1024 dimensions, uses centralized eligibility, and has a deterministic
  tie-break.
- The Docker-backed pgvector test passes with `RUN_POSTGRES_INTEGRATION=1`.
- Ruff, mypy, the complete matching suite, and the full backend suite pass.
- A reviewer confirms no content, vector, similarity, or user identifier leakage.
- The branch contains only Task 6 files and explicitly coordinated prerequisite changes.
