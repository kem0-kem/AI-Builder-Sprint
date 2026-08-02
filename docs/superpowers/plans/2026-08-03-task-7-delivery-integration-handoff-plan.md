# Task 7 Matching Delivery Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Choose profile-first or semantic matching at letter delivery time, revalidate the final recipient under a database lock, and persist the match and every delivery artifact atomically.

**Architecture:** `MatchingService` owns strategy selection and the bounded lost-race retry loop. `MatchingRepository` owns history checks, final row locking, and match persistence. `LetterCommandHandler` remains the single transaction owner and receives an assembled matching service from both the public letter route and moderation replay route. The public response exposes only the selected strategy and bounded fallback reason.

**Tech Stack:** Python 3.11, FastAPI dependency injection, SQLAlchemy 2 async, PostgreSQL 16, pytest, Ruff, mypy

## Ownership and invariants

- Start only after Tasks 3 through 6 are on the branch and all matching tests pass.
- Preserve one SQLAlchemy `AsyncSession` from candidate selection through `MatchHistory`, `Letter`, mailboxes, room, first message, idempotency record, and outbox writes.
- `LetterCommandHandler.execute` is the only layer that commits. Matching repositories and policies never commit or create sessions.
- Acquire a PostgreSQL transaction-scoped advisory lock derived from `(owner_id,
  idempotency_key)`, then perform the idempotency lookup before matching. Concurrent
  requests for the same key serialize; the waiter reads the committed response without
  selecting, locking, recording, or emitting events again.
- The first successful match on either side of `MatchHistory` uses `PROFILE`. A later match uses semantic ranking in enforce mode and may return `SEMANTIC` or `PROFILE_FALLBACK`.
- Lock and recheck the chosen user immediately before persistence. Retry at most twice, excluding every candidate that lost the race. A third selection is not attempted.
- Never hold a candidate `FOR UPDATE` lock while calling the embedding provider. All
  profile/semantic computation precedes the final candidate lock; after a successful
  lock, perform database writes only.
- Persist only the candidate that passes the final recheck. Never persist a shadow or rejected candidate.
- Canonical-pair uniqueness remains the final database guard against repeat matches.
- A matched delivery and its `letter.embedding.requested` outbox event commit together. Unmatched letters do not create match history or embedding work.
- Public matching data is exactly `matched`, `strategy`, and `fallbackReason`. Never serialize score, model name/version, user IDs, vectors, source letter IDs, or provider errors.
- Keep both execution paths equivalent: direct `POST /letters` and an allowed moderation replay through `ModeratedCommandRegistry`.
- Direct and moderation requests each receive a fresh request-scoped session. Within one
  request, its registry, letter handler, matching service, and repositories share that
  same session object.
- Do not modify or stage `.codex-remote-attachments/`.

## Prerequisite contract gate

- [ ] Run the prerequisite checks before editing:

```powershell
git merge-base --is-ancestor 5a6dfc0 HEAD
Test-Path backend/app/matching/profile_policy.py
Test-Path backend/app/matching/embedding_worker.py
Test-Path backend/app/matching/semantic_policy.py
cd backend
python -m pytest tests/matching -q
```

Expected: the ancestor check exits 0, every path prints `True`, and the matching suite passes.

Task 6 must leave this single candidate type in `app.matching.profile_policy`:

```python
@dataclass(frozen=True, slots=True)
class MatchCandidate:
    user_id: UUID
    strategy: MatchStrategy
    score: float
    fallback_reason: str | None = None
    model_name: str | None = None
    model_version: str | None = None
```

If Task 6 has not produced that exact semantic metadata boundary, finish Task 6 first. Do not add a second response or persistence candidate type.

---

### Task 1: Propagate bounded retry exclusions through every policy

**Files:**
- Modify: `backend/app/matching/eligibility.py`
- Modify: `backend/app/matching/profile_policy.py`
- Modify: `backend/app/matching/semantic_policy.py`
- Modify: `backend/app/matching/repository.py`
- Modify: `backend/tests/matching/test_eligibility.py`
- Modify: `backend/tests/matching/test_profile_policy.py`
- Modify: `backend/tests/matching/test_semantic_policy.py`

**Contract:** every candidate query accepts `excluded_ids: Collection[UUID] = ()`; the exclusion is applied inside the centralized eligibility query.

- [ ] Write failing tests proving an excluded UUID is absent from profile and semantic results, including fallback profile selection.

- [ ] Extend the eligibility query once:

```python
def base_candidate_query(
    self,
    sender_id: UUID,
    *,
    excluded_ids: Collection[UUID] = (),
) -> Select[tuple[User]]:
    statement = self._base_query(sender_id)
    if excluded_ids:
        statement = statement.where(User.id.not_in(tuple(excluded_ids)))
    return statement
```

Pass the keyword unchanged through `ProfileMatchingPolicy.select`, semantic repository search, and `SemanticMatchingPolicy.select`. Do not append ad-hoc exclusions in service code.

- [ ] Verify the focused contract:

```powershell
cd backend
python -m pytest tests/matching/test_eligibility.py tests/matching/test_profile_policy.py tests/matching/test_semantic_policy.py -q
python -m ruff check app/matching tests/matching
python -m mypy app/matching
```

Expected: all commands exit 0.

---

### Task 2: Implement strategy selection and bounded lost-race retries

**Files:**
- Create: `backend/app/matching/service.py`
- Create: `backend/tests/matching/test_matching_service.py`

**Produces:**

```python
@dataclass(frozen=True, slots=True)
class LockedMatch:
    user: User
    candidate: MatchCandidate


class MatchingService:
    async def select_and_lock(
        self,
        session: AsyncSession,
        sender_id: UUID,
        content: str,
    ) -> LockedMatch | None: ...
```

- [ ] Write failing tests for these exact cases:

  - no history calls profile once and never calls semantic;
  - either-side history calls semantic in `enforce` mode;
  - `disabled` mode calls profile so existing matching remains available without provider configuration;
  - one `CandidateLostRace` excludes that UUID and the second candidate succeeds;
  - two lost races raise `ApiError(code="RESOURCE_CONFLICT", status_code=409)` and no history is added;
  - no selected candidate returns `None`;
  - policy exceptions other than `CandidateLostRace` propagate.

- [ ] Implement the orchestration with exactly two attempts:

```python
async def select_and_lock(
    self,
    session: AsyncSession,
    sender_id: UUID,
    content: str,
) -> LockedMatch | None:
    excluded: set[UUID] = set()
    has_history = await self._repository.has_successful_match(sender_id)
    for _attempt in range(2):
        candidate = await self._select(
            session,
            sender_id,
            content,
            has_history=has_history,
            excluded_ids=excluded,
        )
        if candidate is None:
            return None
        try:
            user = await self._repository.lock_candidate(sender_id, candidate.user_id)
        except CandidateLostRace:
            excluded.add(candidate.user_id)
            continue
        return LockedMatch(user=user, candidate=candidate)
    raise ApiError(
        "RESOURCE_CONFLICT",
        "매칭 후보 상태가 변경되었습니다. 다시 시도해 주세요.",
        409,
    )
```

`_select` calls profile when `has_history` is false or mode is `disabled`; otherwise it calls semantic. Task 8 will add the `shadow` branch without changing this lock contract.

- [ ] Run:

```powershell
cd backend
python -m pytest tests/matching/test_matching_service.py -q
```

Expected: all strategy and retry cases pass.

---

### Task 3: Persist the selected match inside the letter transaction

**Files:**
- Modify: `backend/app/letters/service.py`
- Modify: `backend/tests/letters/test_letter_delivery.py`

- [ ] Add failing tests proving:

  - `match=False` never calls matching and writes no `MatchHistory`;
  - `match=True` records canonical history before the delivery commit;
  - `SEMANTIC` history stores score, model name, and model version;
  - `PROFILE` and `PROFILE_FALLBACK` history store no semantic score or model metadata;
  - an injected failure after history, room, message, idempotency, or either outbox write rolls the entire transaction back;
  - replaying the same idempotency key returns byte-equivalent matching data and makes no matching call;
  - two independent sessions using the same owner and idempotency key serialize, create
    exactly one delivery, and both return the same stored response;
  - no candidate maps to `409 MATCH_NOT_FOUND` without persisting a letter.

- [ ] Before the existing idempotency lookup, acquire one PostgreSQL transaction-scoped
advisory lock. Derive a stable signed 64-bit integer from SHA-256 of
`f"{owner_id}:{idempotency_key}"`; never use Python's process-randomized `hash()`:

```python
await self._session.execute(
    select(func.pg_advisory_xact_lock(idempotency_lock_key(owner_id, idempotency_key)))
)
```

The advisory lock is released automatically on commit or rollback. The second request
waits, then performs the normal lookup and returns the first request's response. Keep the
database unique constraint on `(user_id, key)` as the final invariant.

- [ ] Change construction to require the injected service:

```python
class LetterCommandHandler:
    def __init__(self, session: Session, matching: MatchingService) -> None:
        self._session = session
        self._matching = matching
```

- [ ] Replace `_select_candidate` with `select_and_lock` and remove its copied block query. After a successful lock, add:

```python
candidate = locked.candidate
semantic = candidate.strategy is MatchStrategy.SEMANTIC
self._matching.repository.record_match(
    MatchHistory.create(
        owner_id,
        locked.user.id,
        candidate.strategy,
        similarity_score=candidate.score if semantic else None,
        model_name=candidate.model_name if semantic else None,
        model_version=candidate.model_version if semantic else None,
    )
)
```

Expose a narrow `record_match` method on `MatchingService` if direct repository access would be required; do not make the repository public merely for this call.

- [ ] Build the result from the persisted candidate:

```python
"matching": {
    "matched": locked is not None,
    "strategy": locked.candidate.strategy.value if locked else None,
    "fallbackReason": locked.candidate.fallback_reason if locked else None,
},
```

Keep the existing single `await session.commit()` at the end. Do not add intermediate commits. On any exception, rely on the request session rollback; tests must explicitly call rollback before inspecting with a fresh session.

- [ ] Run:

```powershell
cd backend
python -m pytest tests/letters/test_letter_delivery.py tests/matching/test_matching_service.py -q
```

Expected: all atomicity, metadata, and replay cases pass.

---

### Task 4: Assemble one service for direct and moderation-replay paths

**Files:**
- Modify: `backend/app/matching/dependencies.py`
- Modify: `backend/app/main.py`
- Modify: `backend/app/letters/router.py`
- Modify: `backend/app/moderation/router.py`
- Modify: `backend/tests/letters/test_letter_delivery.py`
- Modify: `backend/tests/moderation/test_moderation_routes.py`

- [ ] Add an application-lifespan `httpx.AsyncClient` and `UpstageEmbeddingGateway` only when matching configuration is complete and mode is not disabled. Store the gateway, not raw secrets, on `application.state`; close the client in the lifespan `finally` block.

- [ ] Add a FastAPI dependency that accepts `Request` and `Session`, then assembles `MatchingRepository`, `ProfileMatchingPolicy`, `SemanticMatchingPolicy` when a gateway exists, and `MatchingService`. In disabled mode, semantic may be `None` because it is unreachable. Fail startup/readiness for `enforce` without a valid gateway rather than silently constructing a broken semantic service.

- [ ] Inject the same request-scoped service into both paths:

```python
result = await LetterCommandHandler(session, matching).execute(...)
```

and:

```python
def get_command_registry(
    session: Session,
    matching: Matching,
) -> ModeratedCommandRegistry:
    ...
```

Do not create a second session or a second gateway in the moderation registry closure.
The direct request and a later moderation replay do not share a session across requests;
they only share their own request's session through all nested calls.

- [ ] Prove with route tests that an allowed direct request and an allowed moderation replay produce the same strategy fields, one history row, one embedding request, and one commit. Prove pending and blocked moderation create none of those artifacts.

- [ ] Run:

```powershell
cd backend
python -m pytest tests/letters tests/moderation tests/matching -q
```

Expected: all commands exit 0.

---

### Task 5: Verify public contract, concurrency, and full regression

**Files:**
- Modify only if required by existing typed schemas: `backend/app/letters/schemas.py`
- Modify: `backend/tests/contract/test_openapi.py`

- [ ] Add contract assertions that `matching` has only `matched`, `strategy`, and `fallbackReason`; accepted strategy values are `PROFILE`, `SEMANTIC`, and `PROFILE_FALLBACK`; unmatched results use `null` for strategy and fallback reason.

- [ ] Add a real PostgreSQL concurrency test with two independent sessions selecting the same final candidate. Exactly one transaction may persist the canonical pair; the other must retry to another eligible candidate or return the bounded 409. No duplicate room, history, or embedding request may remain.

- [ ] Run the final gate:

```powershell
cd backend
python scripts/export_openapi.py openapi/slowtalk-v1.json
python -m pytest tests/contract/test_openapi.py tests/matching tests/letters tests/moderation -q
python -m ruff check .
python -m mypy app
python -m pytest -q
git diff --check
```

Expected: every command exits 0. Environment-gated PostgreSQL tests must pass when Docker is available and report an explicit skip otherwise.

- [ ] Review the diff and confirm there is one commit owner, one session, one final lock, one canonical history write, and no sensitive matching fields in HTTP or logs.

- [ ] Commit only Task 7 implementation files:

```powershell
git add -- backend/app/matching backend/app/letters backend/app/moderation/router.py backend/app/main.py backend/tests
git commit -m "feat(matching): route letter delivery by match strategy"
```
