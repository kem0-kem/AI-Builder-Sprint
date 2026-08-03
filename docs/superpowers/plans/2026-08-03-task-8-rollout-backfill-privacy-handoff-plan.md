# Task 8 Matching Rollout, Backfill, and Privacy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make semantic matching operable in production with an idempotent bounded backfill, safe disabled/shadow/enforce rollout modes, bounded metrics, privacy enforcement, and an end-to-end contract gate.

**Architecture:** A resumable command feeds already-allowed matched letters into the Task 5 projection worker without bypassing its idempotency or owner lock. `MatchingService` implements the rollout switch: disabled selects profile, shadow computes semantic observations but locks and persists profile, and enforce uses Task 7 strategy behavior. Small in-memory counters mirror the existing moderation metrics pattern and accept only enums or fixed buckets. Security, OpenAPI, and PostgreSQL journey tests form the release gate.

**Tech Stack:** Python 3.11, FastAPI, SQLAlchemy 2 async, PostgreSQL 16 + pgvector, pytest, Ruff, mypy

## Ownership and release invariants

- Start only after Tasks 1 through 7 are complete and the full suite passes.
- Backfill only `Letter` rows with a non-null recipient. Pending or blocked moderation submissions are not letters and must never be embedded.
- Resume newest-first with a stable `(created_at, id)` keyset. The CLI may accept the last letter UUID, but the repository must resolve that UUID to its timestamp and use both values as the boundary.
- Clamp each invocation to `1..500` rows and each provider passage request to at most 32 texts. Never load all historical letters in memory.
- Rerunning the same page must not create duplicate `(letter_id, model_version)` rows or repeat provider calls for embeddings that already exist.
- Advance `nextCursor` only through rows successfully processed or conclusively skipped
  because their active-model projection already exists. Stop at the first failed batch;
  never return or persist a cursor beyond a failed row.
- `disabled`: choose and persist profile matching; do not call the embedding provider.
- `shadow`: for users with prior history, compute a semantic recommendation for observation, then independently choose, lock, expose, and persist the profile candidate. Never lock or record the shadow candidate.
- `enforce`: retain Task 7 behavior—first match profile, later match semantic with profile fallback.
- Shadow/provider failures must not fail a delivery that has a valid profile candidate. Unexpected programming or database errors still propagate.
- Never hold a candidate `FOR UPDATE` lock during a shadow or enforce provider call. The
  provider phase completes before Task 7's authoritative final lock.
- Metrics labels come only from bounded enums and fixed buckets. IDs, content, raw similarity, vector values, model responses, and arbitrary exception text are forbidden.
- HTTP responses and OpenAPI never expose score, model metadata, source letters, candidate IDs, or vectors.
- Do not modify or stage `.codex-remote-attachments/`.

## Prerequisite gate

- [ ] Run:

```powershell
Test-Path backend/app/matching/service.py
Test-Path backend/app/matching/semantic_policy.py
Test-Path backend/app/matching/embedding_worker.py
cd backend
python -m pytest tests/matching tests/letters tests/moderation -q
python -m ruff check app tests
python -m mypy app
```

Expected: every path prints `True` and all commands exit 0. Resolve any Task 7 failure before adding rollout behavior.

---

### Task 1: Add a stable, bounded, idempotent backfill

**Files:**
- Create: `backend/scripts/backfill_match_embeddings.py`
- Create: `backend/app/matching/backfill.py`
- Modify: `backend/app/matching/embedding_worker.py`
- Create: `backend/tests/matching/test_embedding_backfill.py`
- Modify: `backend/README.md`

**Produces:**

```python
@dataclass(frozen=True, slots=True)
class BackfillPage:
    processed: int
    next_cursor: UUID | None
    exhausted: bool


class MatchEmbeddingBackfill:
    async def run(self, *, after: UUID | None, limit: int) -> BackfillPage: ...
```

- [ ] Write failing tests for all of these cases:

  - selects only matched letters (`recipient_id IS NOT NULL`), newest first;
  - excludes pending/blocked moderation submissions because no allowed `Letter` exists;
  - equal timestamps are ordered by descending UUID and resume without gaps or duplicates;
  - a UUID cursor is resolved to `(created_at, id)` and an unknown cursor raises a bounded command error;
  - limit 0 becomes 1, limit above 500 becomes 500;
  - passage requests contain at most 32 texts;
  - rerunning a page whose `(letter_id, model_version)` rows already exist makes zero provider calls;
  - a partial failure can rerun the same cursor and finish missing projections without duplication;
  - a failed middle batch emits no cursor beyond that batch; retrying the original input
    cursor skips committed projections and reaches the failed row again;
  - output and captured logs contain no letter content, vector, content hash, owner ID, or provider body.

- [ ] Implement keyset selection with the cursor row resolved first:

```python
boundary = await self._repository.resolve_letter_cursor(after) if after else None
statement = (
    select(Letter.id)
    .where(Letter.recipient_id.is_not(None))
    .order_by(Letter.created_at.desc(), Letter.id.desc())
    .limit(bounded_limit)
)
if boundary is not None:
    statement = statement.where(
        tuple_(Letter.created_at, Letter.id)
        < tuple_(boundary.created_at, boundary.id)
    )
```

PostgreSQL tuple ordering is the source of truth. Do not use `Letter.id < cursor` by itself.

- [ ] Before calling the worker, query the active model-version projection rows and skip letters already embedded. Feed remaining IDs in chunks of 32; use the existing `LetterEmbeddingWorker` rather than copying provider, hashing, normalization, or upsert logic.

- [ ] The CLI accepts only `--after UUID` and `--limit INTEGER`, reads settings through `get_settings`, opens one database session and one gateway client, commits each successful batch, and prints a single JSON result containing `processed`, `nextCursor`, and `exhausted`. A successful page sets `nextCursor` to its last processed-or-existing row. On failure it rolls back the current batch, stops immediately, emits no advanced success cursor, and exits nonzero; the operator retries the same input cursor. Earlier committed batches are safely recognized as existing projections on that retry.

- [ ] Document these exact examples in `backend/README.md`:

```powershell
python scripts/backfill_match_embeddings.py --limit 100
python scripts/backfill_match_embeddings.py --after 00000000-0000-0000-0000-000000000000 --limit 100
```

Clarify that operators pass the previous `nextCursor`, stop when `exhausted` is true, and may safely retry a failed page.

- [ ] Run:

```powershell
cd backend
python -m pytest tests/matching/test_embedding_backfill.py tests/matching/test_embedding_worker.py -q
python -m ruff check app/matching scripts/backfill_match_embeddings.py tests/matching
python -m mypy app/matching scripts/backfill_match_embeddings.py
```

Expected: all commands exit 0.

---

### Task 2: Implement disabled, shadow, and enforce behavior

**Files:**
- Modify: `backend/app/matching/service.py`
- Modify: `backend/app/matching/dependencies.py`
- Create: `backend/tests/matching/test_rollout_modes.py`

- [ ] Write failing parameterized tests for this matrix:

| Mode | Prior history | Semantic computation | Persisted/returned strategy |
| --- | --- | --- | --- |
| disabled | no or yes | never | PROFILE |
| shadow | no | never | PROFILE |
| shadow | yes | yes, observation only | PROFILE |
| enforce | no | never | PROFILE |
| enforce | yes | yes | SEMANTIC or PROFILE_FALLBACK |

Also prove that a known provider failure in shadow still selects profile, the shadow candidate is never passed to `lock_candidate` or `record_match`, and unexpected programming/database failures propagate.

- [ ] Add an internal immutable observation value:

```python
@dataclass(frozen=True, slots=True)
class ShadowComparison:
    semantic_outcome: str
    same_candidate: bool | None
```

Allowed `semantic_outcome` values are only `SEMANTIC`, `PROFILE_FALLBACK`, `NO_CANDIDATE`, and `PROVIDER_UNAVAILABLE`. This value is sent to metrics and never returned by HTTP.

- [ ] In shadow mode, call semantic selection without a final lock, retain only its bounded outcome and candidate equality in memory, then call profile selection for the authoritative candidate. Candidate equality is converted immediately to `same`, `different`, or `unavailable`; never put either UUID into metrics or logs.

The semantic provider call and profile computation both finish before the authoritative
candidate is passed to `lock_candidate`. No provider call is allowed inside the final-lock
section.

- [ ] Keep Task 7's final two-attempt lock loop around only the authoritative candidate. Retry selection in the same mode with accumulated exclusions.

- [ ] Run:

```powershell
cd backend
python -m pytest tests/matching/test_rollout_modes.py tests/matching/test_matching_service.py -q
```

Expected: the matrix and failure boundaries pass.

---

### Task 3: Add bounded matching metrics and observers

**Files:**
- Create: `backend/app/matching/metrics.py`
- Modify: `backend/app/matching/profile_policy.py`
- Modify: `backend/app/matching/semantic_policy.py`
- Modify: `backend/app/matching/service.py`
- Create: `backend/tests/matching/test_matching_metrics.py`

Follow `app.moderation.metrics.ModerationMetrics`: use `Counter` fields and fixed latency buckets; do not introduce a new monitoring dependency in this task.

- [ ] Define only these bounded dimensions:

  - authoritative strategies: `PROFILE`, `SEMANTIC`, `PROFILE_FALLBACK`, `NO_MATCH`;
  - profile region stages: `SUB_DISTRICT`, `DISTRICT`, `PROVINCE`, `NATIONAL`, `NO_CANDIDATE`;
  - semantic threshold outcomes: `PASS`, `BELOW`, `NO_VECTOR`;
  - fallback reasons: `INSUFFICIENT_EMBEDDINGS`, `PROVIDER_UNAVAILABLE`;
  - provider latency buckets: `le_25ms`, `le_50ms`, `le_100ms`, `le_250ms`, `le_500ms`, `le_1000ms`, `le_2500ms`, `gt_2500ms`;
  - final-lock attempts: `first`, `second`, `exhausted`;
  - shadow comparison: `same`, `different`, `unavailable`.

- [ ] Make metric method signatures accept enums/bools/durations, not free-form dictionaries. Validate or map every label before incrementing. Do not store similarity itself.

- [ ] Adapt Task 6's `SemanticSelectionObserver` and add a profile observer so policies report threshold/fallback/stage without knowing the global metrics singleton. Dependencies inject `matching_metrics`; unit tests can inject fakes.

- [ ] Test that invalid arbitrary labels are rejected, `repr(metrics)` contains no submitted content or UUIDs, and provider exception text is never stored.

- [ ] Run:

```powershell
cd backend
python -m pytest tests/matching/test_matching_metrics.py tests/matching/test_rollout_modes.py -q
```

Expected: every metric key is bounded exactly as listed.

---

### Task 4: Enforce privacy at response, log, and model boundaries

**Files:**
- Modify: `backend/app/core/redaction.py`
- Create: `backend/tests/security/test_matching_privacy.py`
- Modify: `backend/tests/letters/test_letter_delivery.py`

- [ ] Extend normalized sensitive keys with matching-specific forms: `candidateid`, `senderid`, `recipientid`, `sourceletterids`, `similarity`, `similarityscore`, `score`, `vector`, and `modelresponse`. Existing `content`, `embedding`, and `payload` redaction must remain.

- [ ] Build a recursive privacy test using a distinctive content marker, UUIDs, vector component, score, and provider error text. Assert none appear in:

  - direct and moderation-replay response JSON;
  - captured application logs;
  - `repr` of metrics and shadow observations;
  - serialized idempotency response;
  - OpenAPI matching schemas and examples.

Database-internal `MatchHistory.similarity_score`, model name/version, embeddings, hashes, and source IDs are allowed only in their intended tables.

- [ ] Assert the public matching object has exactly:

```json
{
  "matched": true,
  "strategy": "PROFILE_FALLBACK",
  "fallbackReason": "PROVIDER_UNAVAILABLE"
}
```

No unknown provider exception text may replace a bounded fallback reason.

- [ ] Run:

```powershell
cd backend
python -m pytest tests/security/test_matching_privacy.py tests/letters/test_letter_delivery.py -q
```

Expected: privacy assertions pass for all three rollout modes.

---

### Task 5: Prove the full journey and freeze the contract

**Files:**
- Create: `backend/tests/integration/test_matching_journey.py`
- Modify: `backend/tests/contract/test_openapi.py`
- Modify: `backend/openapi/slowtalk-v1.json`
- Modify: `backend/README.md`

- [ ] Add a PostgreSQL/pgvector journey that proves in order:

  1. two users' first matched letters choose profile and create canonical history;
  2. allowed matched letters create projection outbox events, while pending and blocked moderation do not;
  3. the worker creates active-space projections from at most five authored matched letters;
  4. a later enforce request ranks eligible users by cosine similarity;
  5. exact threshold equality selects `SEMANTIC`, while below threshold selects `PROFILE_FALLBACK`;
  6. self, inactive, either-direction blocks, and every prior partner never reappear;
  7. one candidate race retries once and persists only the final locked candidate;
  8. shadow computes a comparison but persists and returns profile;
  9. idempotent replay creates no duplicate history, letter, room, message, projection event, or provider request.

- [ ] Regenerate and verify OpenAPI:

```powershell
cd backend
python scripts/export_openapi.py openapi/slowtalk-v1.json
python -m pytest tests/contract/test_openapi.py tests/integration/test_matching_journey.py -q
```

- [ ] Document configuration and rollout order in `backend/README.md`: validate readiness, run backfill pages, deploy `shadow`, inspect only bounded metrics, then move to `enforce`. State that rollback to `disabled` keeps profile matching operational and preserves projection data for a later retry.

- [ ] Run the release gate:

```powershell
cd backend
python -m ruff check .
python -m mypy app scripts/backfill_match_embeddings.py
python -m pytest -q
git diff --check
```

Expected: every command exits 0. If Docker is unavailable, the pgvector journey must explicitly skip while unit, API, security, and contract suites still pass.

- [ ] Review the final diff against the release invariants at the top of this document. Verify no backfill, policy, observer, or repository commits independently of its documented transaction owner.

- [ ] Commit only Task 8 files:

```powershell
git add -- backend/scripts/backfill_match_embeddings.py backend/app/matching backend/app/core/redaction.py backend/tests backend/openapi/slowtalk-v1.json backend/README.md
git commit -m "feat(matching): add safe semantic rollout and backfill"
```
