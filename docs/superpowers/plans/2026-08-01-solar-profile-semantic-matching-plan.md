# Solar Profile and Semantic Matching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 첫 성공 매칭은 지역·관심사 프로필 정책으로 수행하고, 이후 매칭은 Solar Embed 2와 pgvector를 이용해 후보자의 최근 발신 편지 5개 성향으로 추천한다.

**Architecture:** `MatchingEligibilityPolicy`가 모든 후보 제외 규칙을 공유하고 `ProfileMatchingPolicy`와 `SemanticMatchingPolicy`가 후보 순위만 계산한다. 검열을 통과해 전달된 편지는 outbox worker가 passage 임베딩하고 사용자 대표 벡터를 갱신하며, `LetterDeliveryService`가 최종 잠금과 전달 트랜잭션을 소유한다.

**Tech Stack:** Python 3.11, FastAPI, SQLAlchemy 2 async, PostgreSQL 16, pgvector, Alembic, HTTPX, Upstage Embed 2, pytest, testcontainers-postgres.

## Global Constraints

- 이 계획은 `2026-08-01-solar-moderation-plan.md` 완료 후 실행한다.
- 첫 매칭은 같은 동, 같은 구, 같은 시·도, 전국 순서로 후보군을 확장한다.
- 같은 지역 단계에서 관심사 Jaccard 유사도 내림차순, 최근 30일 매칭 수 오름차순, 마지막 매칭 시각 오름차순으로 선택한다.
- 두 번째 성공 매칭부터 현재 편지를 query로 임베딩한다.
- 후보 대표 벡터는 후보자가 직접 작성해 전달했고 검열을 통과한 최근 편지 최대 5개만 사용한다.
- `match: false` 개인 편지와 후보자가 받은 편지는 임베딩 대표 벡터에서 제외한다.
- 자기 자신, 차단 관계, 비활성 사용자, 기존 매칭 상대는 항상 제외한다.
- 의미 후보가 없거나 `MATCH_MIN_SIMILARITY` 미만이면 프로필 정책을 한 번 실행하고 `PROFILE_FALLBACK`으로 기록한다.
- 유사도와 임베딩 배열은 사용자 응답이나 로그에 노출하지 않는다.
- 정확한 모델명과 차원은 `UPSTAGE_EMBEDDING_MODEL`과 `EMBEDDING_DIMENSIONS`로 설정하고 시작 시 응답 차원을 검증한다.

---

### Task 1: pgvector foundation and embedding configuration

**Files:**
- Modify: `backend/pyproject.toml`
- Modify: `backend/.env.example`
- Modify: `backend/app/core/config.py`
- Modify: `backend/docker-compose.yml`
- Create: `backend/app/matching/__init__.py`
- Create: `backend/app/matching/models.py`
- Create: `backend/migrations/versions/0003_matching_vectors.py`
- Modify: `backend/migrations/env.py`
- Test: `backend/tests/matching/test_vector_models.py`

**Interfaces:**
- Produces models: `MatchHistory`, `LetterEmbedding`, `UserMatchVector`.
- Configuration: `UPSTAGE_EMBEDDING_MODEL`, `EMBEDDING_DIMENSIONS`, `MATCH_MIN_SIMILARITY`, `MATCHING_MODE`.
- Adds PostgreSQL extension `vector` and HNSW cosine index on active vector columns.

- [ ] **Step 1: Add pgvector and PostgreSQL integration-test dependencies**

Add `pgvector>=0.4,<1` to project dependencies and `testcontainers[postgres]>=4.12,<5` to dev dependencies. Change the Compose image to `pgvector/pgvector:pg16` so local migrations can create the extension.

- [ ] **Step 2: Write failing model and migration tests**

```python
def test_matching_models_use_configured_vector_dimension(settings):
    assert LetterEmbedding.__table__.c.embedding.type.dim == settings.embedding_dimensions
    assert UserMatchVector.__table__.c.embedding.type.dim == settings.embedding_dimensions

async def test_match_pair_is_unique(postgres_session, alice_id, bob_id):
    postgres_session.add(MatchHistory.create(alice_id, bob_id, MatchStrategy.PROFILE))
    await postgres_session.commit()
    postgres_session.add(MatchHistory.create(bob_id, alice_id, MatchStrategy.SEMANTIC))
    with pytest.raises(IntegrityError):
        await postgres_session.commit()
```

- [ ] **Step 3: Run tests and confirm missing pgvector model failures**

Run: `cd backend && python -m pytest tests/matching/test_vector_models.py -q`

Expected: collection fails because `app.matching.models` does not exist.

- [ ] **Step 4: Implement canonical user pairs and vector models**

```python
@classmethod
def create(
    cls, first: UUID, second: UUID, strategy: MatchStrategy, **values: object
) -> "MatchHistory":
    user_a_id, user_b_id = sorted((first, second), key=str)
    return cls(user_a_id=user_a_id, user_b_id=user_b_id, strategy=strategy, **values)

class UserMatchVector(Base):
    __tablename__ = "user_match_vectors"
    user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id"), primary_key=True)
    model_version: Mapped[str] = mapped_column(String(80), primary_key=True)
    embedding: Mapped[list[float]] = mapped_column(Vector(settings.embedding_dimensions))
    source_letter_ids: Mapped[list[str]] = mapped_column(JSON)
    source_count: Mapped[int]
```

- [ ] **Step 5: Create extension, tables, constraints, and HNSW index migration**

```python
op.execute("CREATE EXTENSION IF NOT EXISTS vector")
op.create_index(
    "ix_user_match_vectors_embedding_hnsw",
    "user_match_vectors",
    ["embedding"],
    postgresql_using="hnsw",
    postgresql_ops={"embedding": "vector_cosine_ops"},
)
```

The migration must add a unique canonical pair constraint to `match_history` and cascade vector deletion when a user or source letter is deleted.

- [ ] **Step 6: Verify SQL and model tests**

Run: `cd backend && python -m alembic upgrade head --sql`

Run: `cd backend && python -m pytest tests/matching/test_vector_models.py -q`

Expected: migration SQL prints `CREATE EXTENSION`, the configured vector dimension, the canonical-pair constraint, and the HNSW index; tests pass.

- [ ] **Step 7: Commit vector persistence**

```bash
git add backend/pyproject.toml backend/.env.example backend/app/core/config.py backend/docker-compose.yml backend/app/matching backend/migrations backend/tests/matching
git commit -m "feat(matching): add pgvector matching models"
```

### Task 2: Upstage embedding gateway and dimension readiness

**Files:**
- Create: `backend/app/matching/gateway.py`
- Create: `backend/app/matching/upstage_gateway.py`
- Create: `backend/app/matching/dependencies.py`
- Test: `backend/tests/matching/test_upstage_embedding_gateway.py`
- Test: `backend/tests/matching/test_embedding_readiness.py`

**Interfaces:**
- Produces: `EmbeddingGateway.embed_query(text: str) -> EmbeddingVector`.
- Produces: `EmbeddingGateway.embed_passages(texts: list[str]) -> list[EmbeddingVector]`.
- `EmbeddingVector` validates finite float values and exact configured dimensions.

- [ ] **Step 1: Write failing query, passage, batch, timeout, and dimension tests**

```python
async def test_query_uses_query_model_alias(respx_mock, gateway):
    route = mock_embedding_response(respx_mock, vectors=[[0.1, 0.2, 0.3]])
    vector = await gateway.embed_query("오늘의 편지")
    request = json.loads(route.calls[0].request.content)
    assert request == {"model": "embedding-query", "input": "오늘의 편지"}
    assert vector.values == [0.1, 0.2, 0.3]

async def test_dimension_mismatch_fails_readiness(gateway):
    gateway.expected_dimensions = 4
    with pytest.raises(EmbeddingDimensionMismatch):
        await gateway.embed_query("probe")
```

- [ ] **Step 2: Run tests and confirm missing gateway failure**

Run: `cd backend && python -m pytest tests/matching/test_upstage_embedding_gateway.py tests/matching/test_embedding_readiness.py -q`

Expected: import failure for the missing matching gateway.

- [ ] **Step 3: Implement provider-independent protocol and validated vector type**

```python
class EmbeddingVector(BaseModel):
    values: list[float]

    @field_validator("values")
    @classmethod
    def finite_values(cls, values: list[float]) -> list[float]:
        if not values or any(not math.isfinite(value) for value in values):
            raise ValueError("embedding must contain finite values")
        return values

class EmbeddingGateway(Protocol):
    async def embed_query(self, text: str) -> EmbeddingVector:
        raise NotImplementedError

    async def embed_passages(self, texts: list[str]) -> list[EmbeddingVector]:
        raise NotImplementedError
```

- [ ] **Step 4: Implement the HTTPX Upstage embeddings adapter**

Use `/embeddings`, bearer authorization, `embedding-query` for current letters, and `embedding-passage` for delivered source letters. Batch passage inputs in one request and preserve response index ordering.

```python
response = await self.client.post(
    "/embeddings",
    headers={"Authorization": f"Bearer {self.api_key}"},
    json={"model": model_alias, "input": inputs},
)
response.raise_for_status()
ordered = sorted(response.json()["data"], key=itemgetter("index"))
return [self.validate_vector(item["embedding"]) for item in ordered]
```

- [ ] **Step 5: Add startup readiness probe without logging the probe vector**

In matching `shadow` or `enforce` mode, embed a fixed Korean probe string once at startup, verify the dimension, discard the vector, and expose only model name, expected dimensions, and success boolean in readiness data.

- [ ] **Step 6: Run gateway and readiness tests**

Run: `cd backend && python -m pytest tests/matching/test_upstage_embedding_gateway.py tests/matching/test_embedding_readiness.py -q`

Expected: query/passage aliases, ordering, timeout mapping, dimension mismatch, and redacted logs pass.

- [ ] **Step 7: Commit the embedding boundary**

```bash
git add backend/app/matching backend/tests/matching
git commit -m "feat(matching): add Upstage embedding gateway"
```

### Task 3: Shared eligibility and permanent rematch exclusion

최종 후보 잠금은 PostgreSQL의 `FOR UPDATE SKIP LOCKED`를 사용한다. 후보 조회만으로 자격을 확정하지 않고 잠금 획득 후 동일 제외 조건을 다시 검사한다.

**Files:**
- Create: `backend/app/matching/eligibility.py`
- Create: `backend/app/matching/repository.py`
- Test: `backend/tests/matching/test_eligibility.py`

**Interfaces:**
- Produces: `MatchingEligibilityPolicy.base_candidate_query(sender_id: UUID) -> Select[tuple[User]]`.
- Produces: `MatchingRepository.has_successful_match(user_id: UUID) -> bool`, `lock_candidate(sender_id: UUID, candidate_id: UUID) -> User | None`, `record_match(history: MatchHistory) -> None`.

- [ ] **Step 1: Write failing exclusion tests**

```python
@pytest.mark.parametrize("excluded", ["self", "inactive", "blocked_by_sender", "blocks_sender", "matched_before"])
async def test_candidate_exclusions(excluded, candidate_scenario, policy):
    scenario = await candidate_scenario(excluded)
    ids = await policy.eligible_candidate_ids(scenario.sender_id)
    assert scenario.excluded_user_id not in ids
```

- [ ] **Step 2: Run tests and confirm missing policy failure**

Run: `cd backend && python -m pytest tests/matching/test_eligibility.py -q`

Expected: import failure for `MatchingEligibilityPolicy`.

- [ ] **Step 3: Implement one shared SQL exclusion query**

```python
matched_users = union_all(
    select(MatchHistory.user_b_id.label("user_id")).where(MatchHistory.user_a_id == sender_id),
    select(MatchHistory.user_a_id.label("user_id")).where(MatchHistory.user_b_id == sender_id),
).subquery()
return select(User).where(
    User.id != sender_id,
    User.is_active.is_(True),
    User.id.not_in(select(blocked_users.c.user_id)),
    User.id.not_in(select(matched_users.c.user_id)),
)
```

- [ ] **Step 4: Implement final lock-and-recheck**

```python
candidate = await session.scalar(
    policy.base_candidate_query(sender_id)
    .where(User.id == candidate_id)
    .with_for_update(skip_locked=True)
)
if candidate is None:
    raise CandidateLostRace(candidate_id)
```

- [ ] **Step 5: Run exclusions and concurrency-oriented repository tests**

Run: `cd backend && python -m pytest tests/matching/test_eligibility.py -q`

Expected: every exclusion direction and lost-lock case passes.

- [ ] **Step 6: Commit shared eligibility**

```bash
git add backend/app/matching backend/tests/matching
git commit -m "feat(matching): centralize candidate eligibility"
```

### Task 4: First-match profile policy with geographic expansion and fairness

**Files:**
- Create: `backend/app/matching/profile_policy.py`
- Test: `backend/tests/matching/test_profile_policy.py`

**Interfaces:**
- Consumes: `MatchingEligibilityPolicy.base_candidate_query`.
- Produces: `ProfileMatchingPolicy.select(session, sender_id) -> MatchCandidate | None`.
- `MatchCandidate` contains `user_id`, `strategy`, internal `score`, and optional `fallback_reason`.

- [ ] **Step 1: Write failing tests for every geographic stage and tie-break**

```python
async def test_expands_from_empty_subdistrict_to_district(profile_policy, users):
    sender = await users.sender(region=("11", "11440", "1144066000"), interests={"walk"})
    same_province = await users.candidate(region=("11", "11680", None), interests={"walk"})
    same_district = await users.candidate(region=("11", "11440", "1144067000"), interests={"walk"})
    selected = await profile_policy.select(sender.id)
    assert selected.user_id == same_district.id
    assert selected.user_id != same_province.id

async def test_interest_score_precedes_match_load_within_stage(profile_policy, users):
    sender, one_interest, two_interests = await users.interest_scenario()
    selected = await profile_policy.select(sender.id)
    assert selected.user_id == two_interests.id
```

- [ ] **Step 2: Run tests and confirm the policy is absent**

Run: `cd backend && python -m pytest tests/matching/test_profile_policy.py -q`

Expected: missing `ProfileMatchingPolicy` failure.

- [ ] **Step 3: Implement explicit region stages**

```python
stages = [
    RegionStage("SUB_DISTRICT", User.sub_district_code == sender.sub_district_code),
    RegionStage("DISTRICT", User.district_code == sender.district_code),
    RegionStage("PROVINCE", User.province_code == sender.province_code),
    RegionStage("NATIONAL", true()),
]
for stage in stages:
    candidates = await repository.profile_candidates(sender.id, stage.predicate)
    if candidates:
        return rank_profile_candidates(sender, candidates)[0]
return None
```

Skip a region stage when the sender lacks the corresponding code. Do not combine candidates from a wider stage once a narrower stage has candidates.

- [ ] **Step 4: Implement Jaccard score and deterministic fairness ordering**

```python
def jaccard(left: set[UUID], right: set[UUID]) -> float:
    union = left | right
    return len(left & right) / len(union) if union else 0.0

ranked = sorted(
    candidates,
    key=lambda item: (
        -jaccard(sender.interest_ids, item.interest_ids),
        item.matches_last_30_days,
        item.last_matched_at or datetime.min.replace(tzinfo=UTC),
        str(item.user_id),
    ),
)
```

- [ ] **Step 5: Run profile policy tests**

Run: `cd backend && python -m pytest tests/matching/test_profile_policy.py -q`

Expected: geographic expansion, interests, empty interests, missing region fields, load, and stable final tie-break tests pass.

- [ ] **Step 6: Commit first-match policy**

```bash
git add backend/app/matching backend/tests/matching
git commit -m "feat(matching): add profile-first matching policy"
```

### Task 5: Passage embedding worker and recent-five user vectors

**Files:**
- Create: `backend/app/matching/embedding_worker.py`
- Modify: `backend/app/letters/service.py`
- Modify: `backend/app/events/outbox.py`
- Test: `backend/tests/matching/test_embedding_worker.py`

**Interfaces:**
- Consumes: `EmbeddingGateway.embed_passages`, delivered `Letter`, moderation `ALLOWED` result.
- Produces: `LetterEmbeddingWorker.process(letter_id: UUID) -> None`.
- Emits/consumes outbox topic `letter.embedding.requested`.

- [ ] **Step 1: Write failing source-selection, idempotency, and average-vector tests**

```python
async def test_rebuild_uses_latest_five_authored_matched_letters(worker, letters, gateway):
    authored = await letters.create_authored_matched(count=6)
    await letters.create_personal()
    await letters.create_received()
    await worker.process(authored[-1].id)
    profile = await worker.repository.get_user_vector(authored[-1].sender_id)
    assert profile.source_letter_ids == [str(item.id) for item in authored[-5:]]
    assert profile.source_count == 5

async def test_duplicate_event_does_not_call_provider_twice(worker, gateway, embedded_letter):
    await worker.process(embedded_letter.id)
    await worker.process(embedded_letter.id)
    assert gateway.embed_passages.call_count == 1
```

- [ ] **Step 2: Run tests and confirm missing worker failure**

Run: `cd backend && python -m pytest tests/matching/test_embedding_worker.py -q`

Expected: import failure for the embedding worker.

- [ ] **Step 3: Emit embedding requests only after committed matched delivery**

```python
await outbox.add(
    topic="letter.embedding.requested",
    aggregate_id=letter.id,
    payload={"letterId": str(letter.id), "senderId": str(letter.sender_id)},
)
```

Personal letters and blocked or pending submissions must not emit this topic.

- [ ] **Step 4: Implement hash/model idempotency and representative vector rebuild**

```python
recent = await repository.latest_authored_matched_letters(user_id, limit=5)
missing = [item for item in recent if not await repository.has_embedding(item.id, model)]
vectors = await gateway.embed_passages([item.content for item in missing]) if missing else []
await repository.store_missing_embeddings(missing, vectors, model)
all_vectors = await repository.vectors_for_letters([item.id for item in recent], model)
representative = normalize(mean_vector([item.values for item in all_vectors]))
await repository.upsert_user_vector(user_id, representative, [item.id for item in recent], model)
```

- [ ] **Step 5: Run worker and outbox tests**

Run: `cd backend && python -m pytest tests/matching/test_embedding_worker.py tests/events/test_outbox.py -q`

Expected: latest-five selection, authored-only rule, idempotency, rollback safety, and normalized average tests pass.

- [ ] **Step 6: Commit embedding projections**

```bash
git add backend/app/matching backend/app/letters backend/app/events backend/tests
git commit -m "feat(matching): build recent-letter match vectors"
```

### Task 6: Semantic policy, threshold, and profile fallback

**Files:**
- Create: `backend/app/matching/semantic_policy.py`
- Modify: `backend/app/matching/repository.py`
- Test: `backend/tests/matching/test_semantic_policy.py`
- Test: `backend/tests/matching/test_pgvector_search.py`

**Interfaces:**
- Consumes: `EmbeddingGateway.embed_query`, `MatchingEligibilityPolicy`, `ProfileMatchingPolicy`.
- Produces: `SemanticMatchingPolicy.select(session, sender_id, content) -> MatchCandidate | None`.
- Returns `SEMANTIC` or `PROFILE_FALLBACK`; never exposes the internal score in public schemas.

- [ ] **Step 1: Write failing unit tests for ranking, threshold, and fallback**

```python
async def test_selects_highest_eligible_similarity(policy, gateway, candidates):
    gateway.embed_query.return_value = vector([1.0, 0.0, 0.0])
    await candidates.add("near", [0.99, 0.01, 0.0])
    await candidates.add("far", [0.0, 1.0, 0.0])
    result = await policy.select(SENDER_ID, "현재 편지")
    assert result.user_id == candidates["near"].id
    assert result.strategy is MatchStrategy.SEMANTIC

async def test_below_threshold_uses_profile_fallback(policy, gateway, profile_policy):
    policy.minimum_similarity = 0.8
    policy.repository.search.return_value = [candidate(score=0.4)]
    result = await policy.select(SENDER_ID, "현재 편지")
    assert result.strategy is MatchStrategy.PROFILE_FALLBACK
    assert result.fallback_reason == "INSUFFICIENT_EMBEDDINGS"
```

- [ ] **Step 2: Run tests and confirm missing semantic policy failure**

Run: `cd backend && python -m pytest tests/matching/test_semantic_policy.py -q`

Expected: import failure for `SemanticMatchingPolicy`.

- [ ] **Step 3: Implement pgvector cosine search with top-20 cap**

```python
distance = UserMatchVector.embedding.cosine_distance(query_vector.values)
statement = (
    eligibility.base_candidate_query(sender_id)
    .join(UserMatchVector, UserMatchVector.user_id == User.id)
    .where(UserMatchVector.model_version == active_model)
    .add_columns((1 - distance).label("similarity"))
    .order_by(distance)
    .limit(20)
)
```

- [ ] **Step 4: Implement fallback only for missing or below-threshold semantic candidates**

Provider failure uses profile fallback when it returns an eligible candidate. If both semantic and profile policies fail, return `None`; the caller maps it to `409 MATCH_NOT_FOUND`. Record provider failures in metrics without content or vector values.

- [ ] **Step 5: Run real PostgreSQL pgvector integration tests**

Run: `cd backend && python -m pytest tests/matching/test_semantic_policy.py tests/matching/test_pgvector_search.py -q`

Expected: exact cosine ordering, model-version filtering, top-20 bound, eligibility exclusion, threshold, and fallback tests pass against pgvector-enabled PostgreSQL.

- [ ] **Step 6: Commit semantic selection**

```bash
git add backend/app/matching backend/tests/matching
git commit -m "feat(matching): add Solar semantic candidate ranking"
```

### Task 7: Letter delivery strategy selection and transactional match history

**Files:**
- Modify: `backend/app/letters/service.py`
- Modify: `backend/app/letters/router.py`
- Modify: `backend/app/letters/schemas.py`
- Create: `backend/app/matching/service.py`
- Test: `backend/tests/matching/test_matching_service.py`
- Modify: `backend/tests/letters/test_letter_delivery.py`

**Interfaces:**
- Produces: `MatchingService.select(sender_id, content) -> MatchCandidate | None`.
- `MatchingService` uses profile policy when no successful `match_history` row exists for the sender and semantic policy otherwise.
- `LetterDeliveryService.deliver` writes `MatchHistory` in the same transaction as letter, mailboxes, room, first message, idempotency response, and outbox events.

- [ ] **Step 1: Write failing first-versus-later and rollback tests**

```python
async def test_first_match_uses_profile_and_second_uses_semantic(service, history):
    first = await service.select(ALICE_ID, "첫 편지")
    assert first.strategy is MatchStrategy.PROFILE
    await history.record(ALICE_ID, BOB_ID, MatchStrategy.PROFILE)
    second = await service.select(ALICE_ID, "두 번째 편지")
    assert second.strategy is MatchStrategy.SEMANTIC

async def test_match_history_rolls_back_with_delivery(delivery, session):
    delivery.fail_after = "match_history"
    with pytest.raises(InjectedFailure):
        await delivery.deliver(command())
    assert await scalar_count(session, MatchHistory) == 0
    assert await scalar_count(session, Letter) == 0
```

- [ ] **Step 2: Run tests and confirm current router has no strategy service**

Run: `cd backend && python -m pytest tests/matching/test_matching_service.py tests/letters/test_letter_delivery.py -q`

Expected: strategy and transaction tests fail.

- [ ] **Step 3: Implement strategy choice using either-side successful history**

```python
async def select(self, sender_id: UUID, content: str) -> MatchCandidate | None:
    if not await self.repository.has_successful_match(sender_id):
        return await self.profile.select(sender_id, strategy=MatchStrategy.PROFILE)
    return await self.semantic.select(sender_id, content)
```

- [ ] **Step 4: Record final candidate only after lock-and-recheck**

If the selected candidate loses eligibility or lock, retry selection at most twice with that candidate excluded. After two lost races, return `409 RESOURCE_CONFLICT`; do not persist a letter.

```python
locked = await matching.lock_and_recheck(sender_id, candidate.user_id)
history = MatchHistory.create(
    sender_id,
    locked.id,
    candidate.strategy,
    similarity_score=candidate.internal_score,
    model_version=candidate.model_version,
)
session.add(history)
```

- [ ] **Step 5: Extend only the public strategy fields**

```python
"matching": {
    "matched": True,
    "strategy": candidate.strategy,
    "fallbackReason": candidate.fallback_reason,
}
```

Do not serialize `internal_score`, model version, source letter IDs, or vector values.

- [ ] **Step 6: Run matching and letter transaction tests**

Run: `cd backend && python -m pytest tests/matching tests/letters -q`

Expected: first/profile, later/semantic, fallback, permanent exclusion, two lost races, idempotency, and full rollback tests pass.

- [ ] **Step 7: Commit the delivery integration**

```bash
git add backend/app/matching backend/app/letters backend/tests
git commit -m "feat(matching): route letter delivery by match strategy"
```

### Task 8: Backfill, shadow comparison, privacy, and final contract

**Files:**
- Create: `backend/scripts/backfill_match_embeddings.py`
- Create: `backend/app/matching/metrics.py`
- Modify: `backend/app/core/redaction.py`
- Modify: `backend/app/main.py`
- Modify: `backend/tests/contract/test_openapi.py`
- Create: `backend/tests/security/test_matching_privacy.py`
- Create: `backend/tests/integration/test_matching_journey.py`
- Modify: `backend/openapi/slowtalk-v1.json`
- Modify: `backend/README.md`

**Interfaces:**
- Backfill processes eligible authored matched letters newest-first, resumes from a UUID cursor, and is idempotent by `(letter_id, model_version)`.
- `MATCHING_MODE=shadow` computes semantic recommendations without changing the selected profile candidate.

- [ ] **Step 1: Write failing backfill resume, shadow, privacy, and contract tests**

```python
async def test_backfill_resumes_without_duplicate_provider_calls(backfill, checkpoint, gateway):
    await backfill.run(after=checkpoint, limit=100)
    first_count = gateway.embed_passages.call_count
    await backfill.run(after=checkpoint, limit=100)
    assert gateway.embed_passages.call_count == first_count

def test_matching_response_and_logs_exclude_vectors(response_json, captured_logs):
    serialized = json.dumps(response_json) + captured_logs.text
    assert "embedding" not in serialized.lower()
    assert "similarity_score" not in serialized
```

- [ ] **Step 2: Run new integration tests and confirm missing rollout features**

Run: `cd backend && python -m pytest tests/security/test_matching_privacy.py tests/integration/test_matching_journey.py -q`

Expected: failures for backfill, shadow metrics, and response strategy contract.

- [ ] **Step 3: Implement an idempotent bounded backfill command**

```python
async def run(self, after: UUID | None, limit: int) -> BackfillResult:
    letters = await self.repository.eligible_letters(after=after, limit=min(limit, 500))
    for batch in batched(letters, 32):
        await self.worker.process_batch([item.id for item in batch])
    next_cursor = letters[-1].id if len(letters) == limit else None
    return BackfillResult(processed=len(letters), next_cursor=next_cursor)
```

- [ ] **Step 4: Add privacy-safe strategy and rollout metrics**

Record strategy counts, profile region stage, semantic threshold pass rate, fallback rate, provider latency, and candidate-race retries. Do not label metrics with user, letter, room, or content identifiers.

- [ ] **Step 5: Regenerate OpenAPI and run the full user journey**

Run: `cd backend && python scripts/export_openapi.py openapi/slowtalk-v1.json`

Run: `cd backend && python -m pytest tests/contract/test_openapi.py tests/integration/test_matching_journey.py -q`

The journey must prove: two users get a profile first match, later eligible users are ranked semantically, prior partners never reappear, pending moderation never enters vectors, and profile fallback is reported without a score.

- [ ] **Step 6: Run the final quality gate**

Run: `cd backend && python -m ruff check . && python -m mypy app && python -m pytest -q`

Expected: all commands exit 0, including real PostgreSQL pgvector integration tests when Docker is available. If Docker is unavailable, the pgvector test must report an explicit skip while unit, API, and contract tests still pass.

- [ ] **Step 7: Commit rollout and documentation**

```bash
git add backend
git commit -m "test(matching): add backfill rollout and contract coverage"
```
