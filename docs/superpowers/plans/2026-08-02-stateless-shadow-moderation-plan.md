# Stateless Shadow Moderation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `MODERATION_MODE=shadow` classify content and emit bounded, anonymous metrics without persisting submissions, decisions, encrypted commands, retries, or replayable state.

**Architecture:** Extract the existing normalization and local/provider classification into a storage-free helper shared by enforce and shadow orchestrators. The enforce orchestrator keeps all current repository transitions; the new shadow orchestrator records only bounded metrics and always returns an immediate outcome. Dependency construction selects the implementation by mode and does not construct a repository or cipher in shadow mode.

**Tech Stack:** Python 3.11+, FastAPI dependency injection, Pydantic v2, SQLAlchemy async, pytest/pytest-asyncio, Ruff, strict mypy.

## Global Constraints

- Shadow must never create `ContentSubmission`, moderation decision rows, retry outbox events, encrypted commands, or replayable state.
- Shadow allow, review, block, and provider failure must preserve the existing successful domain API behavior.
- Shadow metrics may contain only content type, `ALLOW`/`REVIEW`/`BLOCK`/`PROVIDER_FAILURE`, public category, configured model, and bounded latency bucket.
- Shadow provider failure is fail-open and schedules no retry or manual review.
- Enforce mode behavior and its `202`, `422`, retry, manual review, OCR TTL, and replay contracts must remain unchanged.
- Shadow must not require the moderation encryption key or content-hash pepper.

---

### Task 1: Extract Storage-Free Classification

**Files:**
- Modify: `backend/app/moderation/service.py`
- Test: `backend/tests/moderation/test_service.py`

**Interfaces:**
- Consumes: `ModerationGateway.classify(content_type, text)`, `LocalRuleEngine.inspect(text)`, and `ModerationCommand`.
- Produces: `async classify_normalized(gateway: ModerationGateway, local_rules: LocalRuleEngine, command: ModerationCommand) -> ModerationAssessment` and `ModerationClassificationUnavailable`.

- [ ] **Step 1: Add failing classification-helper tests**

Add tests proving local hard blocks do not call the provider, valid provider results are combined with local review categories, provider exceptions become the typed storage-free failure, and malformed provider assessments do not echo submitted content.

```python
async def test_storage_free_classifier_local_block_skips_provider() -> None:
    gateway = CountingGateway(allow())
    command = moderation_command("https://a.example https://b.example https://c.example https://d.example")

    assessment = await classify_normalized(gateway, LocalRuleEngine(), command)

    assert assessment.decision is ModerationDecision.BLOCK
    assert gateway.calls == 0


async def test_storage_free_classifier_normalizes_provider_failure() -> None:
    gateway = StubGateway(ModerationProviderUnavailable())

    with pytest.raises(ModerationClassificationUnavailable):
        await classify_normalized(gateway, LocalRuleEngine(), moderation_command("hello"))
```

- [ ] **Step 2: Run the new tests and confirm import failures**

Run: `cd backend && python -m pytest tests/moderation/test_service.py -q`

Expected: FAIL because `classify_normalized` and `ModerationClassificationUnavailable` do not exist.

- [ ] **Step 3: Implement the shared storage-free classifier**

In `service.py`, move the existing local hard-block assessment creation, provider call, Pydantic re-validation, provider request ID stripping, and `combine_assessments` call into:

```python
class ModerationClassificationUnavailable(RuntimeError):
    pass


async def classify_normalized(
    gateway: ModerationGateway,
    local_rules: LocalRuleEngine,
    command: ModerationCommand,
) -> ModerationAssessment:
    local = local_rules.inspect(command.text)
    if local.decision is ModerationDecision.BLOCK:
        return _local_block_assessment(local)
    try:
        untrusted = await gateway.classify(command.content_type, command.text)
        provider = ModerationAssessment.model_validate(untrusted.model_dump())
    except (AttributeError, ModerationProviderUnavailable, TypeError, ValidationError):
        raise ModerationClassificationUnavailable() from None
    if provider.provider_request_id is not None and provider.provider_request_id in command.text:
        provider = provider.model_copy(update={"provider_request_id": None})
    return combine_assessments(local, provider)
```

`ModerationOrchestrator.evaluate` must normalize the command once, call this helper, create pending only when the typed failure is raised, and otherwise keep the existing confidence threshold and repository behavior.

- [ ] **Step 4: Run service and repository regression tests**

Run: `cd backend && python -m pytest tests/moderation/test_service.py tests/moderation/test_repository.py -q`

Expected: PASS; enforce storage behavior remains unchanged.

- [ ] **Step 5: Commit the classifier extraction**

```bash
git add backend/app/moderation/service.py backend/tests/moderation/test_service.py
git commit -m "refactor(moderation): separate classification from storage"
```

---

### Task 2: Add the Stateless Shadow Orchestrator and Bounded Failure Metric

**Files:**
- Modify: `backend/app/moderation/service.py`
- Modify: `backend/app/moderation/metrics.py`
- Modify: `backend/tests/security/test_moderation_privacy.py`

**Interfaces:**
- Consumes: `classify_normalized(...)`, `_normalize_command(...)`, and `ModerationMetrics`.
- Produces: `ShadowModerationOrchestrator.evaluate(command) -> ModerationOutcome`, `ShadowModerationOrchestrator.evaluate_ocr(owner_id, text) -> ModerationOutcome`, and `ModerationMetrics.record_provider_failure(content_type, duration_ms) -> None`.

- [ ] **Step 1: Replace the permissive wrapper test with failing stateless-shadow tests**

Delete the current `RecordingModerationOrchestrator(..., shadow=True)` test. Add four parameterized tests for allow, review, block, and provider failure using only a gateway, local rules, and metrics.

```python
@pytest.mark.parametrize("assessment", [allow(), review(), block()])
async def test_shadow_records_decision_and_always_returns_immediate(assessment) -> None:
    metrics = ModerationMetrics()
    shadow = ShadowModerationOrchestrator(
        StubGateway(assessment), metrics, local_rules=LocalRuleEngine()
    )

    outcome = await shadow.evaluate(moderation_command("ordinary text"))

    assert outcome.is_immediate
    assert sum(metrics.decisions.values()) == 1


async def test_shadow_provider_failure_is_immediate_and_bounded() -> None:
    metrics = ModerationMetrics()
    shadow = ShadowModerationOrchestrator(
        StubGateway(ModerationProviderUnavailable()), metrics
    )

    outcome = await shadow.evaluate(moderation_command("private marker"))

    assert outcome.is_immediate
    assert metrics.decisions == {("FEED", "PROVIDER_FAILURE"): 1}
    assert "private marker" not in repr(metrics)
```

- [ ] **Step 2: Run privacy tests and confirm the shadow class is missing**

Run: `cd backend && python -m pytest tests/security/test_moderation_privacy.py -q`

Expected: FAIL because `ShadowModerationOrchestrator` and `record_provider_failure` do not exist.

- [ ] **Step 3: Implement bounded failure metrics and stateless shadow**

Add the metrics method:

```python
def record_provider_failure(self, content_type: ContentType, duration_ms: float) -> None:
    content = content_type.value
    self.decisions[(content, "PROVIDER_FAILURE")] += 1
    self.latencies[(content, latency_bucket(duration_ms))] += 1
```

Implement `ShadowModerationOrchestrator` without a repository field. It normalizes once, measures elapsed time, calls `classify_normalized`, records either the assessment or provider failure, and returns `ModerationOutcome(http_status=200)`. Its OCR method creates the same `OCR_TEXT` command as enforce but never retains it after the call.

- [ ] **Step 4: Run the focused privacy and service tests**

Run: `cd backend && python -m pytest tests/security/test_moderation_privacy.py tests/moderation/test_service.py -q`

Expected: PASS; all shadow outcomes are immediate and metrics contain only bounded labels.

- [ ] **Step 5: Commit the stateless orchestrator**

```bash
git add backend/app/moderation/service.py backend/app/moderation/metrics.py backend/tests/security/test_moderation_privacy.py
git commit -m "feat(moderation): add stateless shadow classifier"
```

---

### Task 3: Wire Shadow Mode Without Repository or Cipher

**Files:**
- Modify: `backend/app/moderation/dependencies.py`
- Test: `backend/tests/moderation/test_shadow_mode.py`
- Test: `backend/tests/moderation/test_feed_comment_report_moderation.py`
- Test: `backend/tests/moderation/test_ocr_moderation.py`

**Interfaces:**
- Consumes: `ShadowModerationOrchestrator`, `ModerationOrchestrator`, `UpstageModerationGateway`, `moderation_metrics`, and `Settings.moderation_mode`.
- Produces: mode-specific `get_moderation_orchestrator` construction where shadow never instantiates `ModerationRepository` or `CommandCipher`.

- [ ] **Step 1: Write failing dependency and API persistence tests**

Create `test_shadow_mode.py` with configured shadow settings and a stub gateway/client. Prove that encryption key and pepper are absent, blocked and provider-failure feed requests still return `201`, and no moderation tables or outbox rows are created.

```python
async def test_shadow_block_persists_domain_only(client, session_factory) -> None:
    response = await post_shadow_feed(client, blocking_gateway)

    assert response.status_code == 201
    async with session_factory() as session:
        assert await count(session, Feed) == 1
        assert await count(session, ContentSubmission) == 0
        assert await count(session, ModerationDecisionRecord) == 0


async def test_shadow_provider_failure_creates_no_retry_or_review_state(
    client, session_factory
) -> None:
    response = await post_shadow_feed(client, unavailable_gateway)

    assert response.status_code == 201
    async with session_factory() as session:
        assert await count(session, ContentSubmission) == 0
        assert await count(session, OutboxEvent) == 0
```

Add OCR coverage asserting shadow OCR returns extracted text and stores no submission.

- [ ] **Step 2: Run shadow tests and confirm current encrypted persistence**

Run: `cd backend && python -m pytest tests/moderation/test_shadow_mode.py -q`

Expected: FAIL because dependency construction currently wraps the repository-backed orchestrator in shadow mode.

- [ ] **Step 3: Split dependency construction by mode**

Change `get_moderation_orchestrator` so the branches are explicit:

```python
gateway = UpstageModerationGateway(...)
if settings.moderation_mode == "shadow":
    yield ShadowModerationOrchestrator(gateway, moderation_metrics)
    return

assert settings.moderation_encryption_key is not None
assert settings.content_hash_pepper is not None
repository = ModerationRepository(...)
yield ModerationOrchestrator(gateway, repository, allow_confidence, block_confidence)
```

The initial completeness check for shadow includes only provider key, model, allow threshold, and block threshold. Enforce continues to require encryption, pepper, and internal token through settings validation.

- [ ] **Step 4: Run all affected content-surface tests**

Run: `cd backend && python -m pytest tests/moderation/test_shadow_mode.py tests/moderation/test_feed_comment_report_moderation.py tests/moderation/test_ocr_moderation.py tests/moderation/test_letter_chat_moderation.py -q`

Expected: PASS; shadow persists only domain rows, enforce moderation behavior is unchanged.

- [ ] **Step 5: Commit mode-specific dependency construction**

```bash
git add backend/app/moderation/dependencies.py backend/tests/moderation/test_shadow_mode.py backend/tests/moderation/test_feed_comment_report_moderation.py backend/tests/moderation/test_ocr_moderation.py
git commit -m "fix(moderation): prevent shadow quarantine persistence"
```

---

### Task 4: Final Quality Gate and Contract Confirmation

**Files:**
- Modify only if regenerated output differs: `backend/openapi/slowtalk-v1.json`
- Modify only if the operational description is inaccurate: `backend/README.md`

**Interfaces:**
- Consumes: all previous task outputs.
- Produces: verified stateless shadow behavior with an unchanged public API contract.

- [ ] **Step 1: Regenerate OpenAPI**

Run: `cd backend && python scripts/export_openapi.py openapi/slowtalk-v1.json`

Expected: No semantic route changes; snapshot equality remains valid.

- [ ] **Step 2: Run formatting and type checks**

Run: `cd backend && ruff check . && mypy app`

Expected: both commands exit 0.

- [ ] **Step 3: Run the complete test suite**

Run: `cd backend && pytest -q`

Expected: all tests pass, including shadow persistence-zero and existing enforce replay suites.

- [ ] **Step 4: Inspect the final diff for privacy regressions**

Run: `git diff --check && rg -n "ContentSubmission|ModerationRepository|CommandCipher" backend/app/moderation/dependencies.py backend/app/moderation/service.py`

Expected: shadow branch has no repository/cipher construction; enforce references remain.

- [ ] **Step 5: Commit final generated or documentation changes if present**

```bash
git add backend/openapi/slowtalk-v1.json backend/README.md
git commit -m "docs(moderation): document stateless shadow rollout"
```

Skip this commit only when both files are byte-for-byte unchanged.

---

### Final Review Fixes

The whole-branch review found plan defects that must be corrected before integration. The design amendment is authoritative.

#### Task 5: Share Effective Confidence Policy

- Add a storage-free policy resolver for confidence thresholds.
- Use it in both shadow metrics and enforce disposition without changing enforce API/storage behavior.
- Pass thresholds into the shadow orchestrator from dependency construction.
- Cover high/low/boundary ALLOW and BLOCK parity, plus shadow zero-persistence behavior.

#### Task 6: Narrow Gateway Exception Conversion

- Catch `ModerationProviderUnavailable` only around the gateway await.
- Convert malformed returned assessments only at the explicit validation boundary.
- Prove gateway-internal `AttributeError`, `TypeError`, `KeyError`, and `AssertionError` are not reported as provider failures.
- Preserve typed provider/protocol failure behavior without echoing submitted content.

#### Task 7: Make Shadow Configuration Fallback Visible

- Centralize moderation configuration completeness.
- Add an explicit development/test fallback opt-in.
- Return not-ready for incomplete shadow configuration unless that opt-in is enabled.
- When explicitly enabled, return ready with `fallbackActive=true`; configured moderation returns `fallbackActive=false`.
- Keep runtime provider failure separate from configuration fallback and preserve public moderated-write behavior.
- Regenerate and verify OpenAPI.

#### Task 8: Repeat Full Quality Gate and Whole-Branch Review

- Run Ruff, strict mypy, complete pytest, OpenAPI regeneration/equality, diff checks, and the six privacy constraints fresh.
- Repeat the whole-branch senior review. Do not integrate with Critical or Important findings outstanding.
