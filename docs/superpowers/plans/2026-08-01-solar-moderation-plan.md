# Solar Content Moderation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검열 대상 콘텐츠를 저장·전달·공개하기 전에 Solar로 `ALLOW`, `REVIEW`, `BLOCK` 판정하고, 장애나 불확실한 판정은 암호화 격리 후 안전하게 재처리한다.

**Architecture:** `ModerationGateway`가 Upstage HTTP 계약을 캡슐화하고 `ModerationOrchestrator`가 로컬 규칙, 신뢰도 정규화, 격리 및 상태 전이를 소유한다. 도메인 라우터는 원문을 직접 저장하지 않고 `ModeratedCommandService`를 거치며, 보류 명령은 transactional outbox worker가 멱등하게 재실행한다.

**Tech Stack:** Python 3.11, FastAPI, Pydantic 2, SQLAlchemy 2 async, PostgreSQL 16, Alembic, HTTPX, cryptography AES-GCM, pytest, respx.

## Global Constraints

- 대상은 편지, 채팅, 피드, 댓글, 회고, 편지·회고 OCR 결과다. `/feeds/ocr`는 생성하지 않는다.
- 상태는 `ALLOW`, `REVIEW`, `BLOCK` 판정과 `PENDING_REVIEW`, `ALLOWED`, `BLOCKED`, `MANUAL_REVIEW` 제출 상태를 사용한다.
- `REVIEW`와 Upstage 장애는 `202 Accepted`로 격리하고 1분, 5분, 30분 간격으로 최대 3회 재검사한다.
- `BLOCK` 원문은 저장하지 않고 서비스 pepper 기반 SHA-256 해시와 판정 메타데이터만 저장한다.
- 격리 명령은 AES-GCM으로 암호화하며 로그, 오류 응답, 메트릭에 원문을 포함하지 않는다.
- Upstage에는 검열 텍스트와 콘텐츠 유형만 전달하고 사용자 ID, 이메일, 지역, 토큰을 전달하지 않는다.
- 검열 활성 모드에서는 API 키, 암호화 키, 허용·차단 신뢰도 설정이 모두 있어야 readiness가 성공한다.

---

### Task 1: Moderation contracts, configuration, and Upstage gateway

**Files:**
- Modify: `backend/pyproject.toml`
- Modify: `backend/.env.example`
- Modify: `backend/app/core/config.py`
- Create: `backend/app/moderation/__init__.py`
- Create: `backend/app/moderation/schemas.py`
- Create: `backend/app/moderation/gateway.py`
- Create: `backend/app/moderation/upstage_gateway.py`
- Test: `backend/tests/moderation/test_upstage_gateway.py`

**Interfaces:**
- Produces: `ModerationGateway.classify(content_type: ContentType, text: str) -> ModerationAssessment`.
- Produces: `ContentType`, `ModerationDecision`, `ModerationCategory`, `ModerationAssessment`.
- Configuration: `UPSTAGE_API_KEY`, `UPSTAGE_BASE_URL`, `UPSTAGE_CHAT_MODEL`, `MODERATION_MODE`, `MODERATION_ALLOW_CONFIDENCE`, `MODERATION_BLOCK_CONFIDENCE`, `MODERATION_ENCRYPTION_KEY`, `CONTENT_HASH_PEPPER`, `INTERNAL_MODERATION_TOKEN`.

- [ ] **Step 1: Add gateway test dependencies and write failing response-contract tests**

Add `cryptography>=45,<46` to project dependencies and `respx>=0.22,<1` to dev dependencies. Then create tests covering valid JSON, malformed JSON, timeout, and payload redaction.

```python
async def test_gateway_sends_only_type_and_text(respx_mock, settings):
    route = respx_mock.post("https://api.upstage.ai/v1/chat/completions").mock(
        return_value=httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": json.dumps({
                    "decision": "ALLOW",
                    "categories": [],
                    "severity": "NONE",
                    "confidence": 0.97,
                    "reason": "safe",
                })}}]
            },
        )
    )
    assessment = await gateway.classify(ContentType.LETTER, "오늘 산책했어요")
    sent = json.loads(route.calls[0].request.content)
    serialized = json.dumps(sent, ensure_ascii=False)
    assert assessment.decision is ModerationDecision.ALLOW
    assert "user@example.com" not in serialized
    assert "오늘 산책했어요" in serialized
```

- [ ] **Step 2: Run the gateway tests and confirm the missing-module failure**

Run: `cd backend && python -m pytest tests/moderation/test_upstage_gateway.py -q`

Expected: collection fails because `app.moderation.gateway` does not exist.

- [ ] **Step 3: Define exact enums, assessment schema, and protocol**

```python
class ContentType(StrEnum):
    LETTER = "LETTER"
    CHAT_MESSAGE = "CHAT_MESSAGE"
    FEED = "FEED"
    COMMENT = "COMMENT"
    REPORT = "REPORT"
    OCR_TEXT = "OCR_TEXT"

class ModerationDecision(StrEnum):
    ALLOW = "ALLOW"
    REVIEW = "REVIEW"
    BLOCK = "BLOCK"

class ModerationAssessment(BaseModel):
    decision: ModerationDecision
    categories: set[ModerationCategory]
    severity: Severity
    confidence: float = Field(ge=0, le=1)
    reason: str = Field(max_length=300)
    provider_request_id: str | None = None

class ModerationGateway(Protocol):
    async def classify(self, content_type: ContentType, text: str) -> ModerationAssessment:
        raise NotImplementedError
```

- [ ] **Step 4: Implement the HTTPX Upstage adapter with strict parsing**

```python
response = await self.client.post(
    "/chat/completions",
    headers={"Authorization": f"Bearer {self.api_key}"},
    json={
        "model": self.model,
        "temperature": 0,
        "messages": [
            {"role": "system", "content": MODERATION_SYSTEM_PROMPT},
            {"role": "user", "content": json.dumps({"type": content_type, "text": text})},
        ],
    },
)
response.raise_for_status()
content = response.json()["choices"][0]["message"]["content"]
assessment = ModerationAssessment.model_validate_json(content)
return assessment.model_copy(update={"provider_request_id": response.headers.get("x-request-id")})
```

Map `httpx.TimeoutException`, transport errors, non-2xx responses, and schema failures to `ModerationProviderUnavailable`; never attach request content to the exception.

- [ ] **Step 5: Add settings validation and run quality checks**

```python
@model_validator(mode="after")
def validate_moderation(self) -> "Settings":
    if self.moderation_mode == "enforce":
        required = [
            self.upstage_api_key,
            self.moderation_encryption_key,
            self.content_hash_pepper,
        ]
        if any(value is None for value in required):
            raise ValueError("enforce moderation requires Upstage and encryption secrets")
    if self.moderation_allow_confidence >= self.moderation_block_confidence:
        raise ValueError("allow confidence must be lower than block confidence")
    return self
```

Run: `cd backend && python -m ruff check . && python -m mypy app && python -m pytest tests/moderation/test_upstage_gateway.py -q`

Expected: all commands exit 0.

- [ ] **Step 6: Commit the gateway boundary**

```bash
git add backend/pyproject.toml backend/.env.example backend/app/core/config.py backend/app/moderation backend/tests/moderation/test_upstage_gateway.py
git commit -m "feat(moderation): add Solar moderation gateway"
```

### Task 2: Encrypted submissions and moderation decision persistence

**Files:**
- Create: `backend/app/moderation/models.py`
- Create: `backend/app/moderation/crypto.py`
- Create: `backend/app/moderation/repository.py`
- Create: `backend/migrations/versions/0002_moderation.py`
- Modify: `backend/migrations/env.py`
- Test: `backend/tests/moderation/test_submission_repository.py`
- Test: `backend/tests/moderation/test_crypto.py`

**Interfaces:**
- Consumes: Task 1 moderation enums.
- Produces: `CommandCipher.encrypt(payload: dict[str, object]) -> EncryptedPayload`, `CommandCipher.decrypt(payload: EncryptedPayload) -> dict[str, object]`.
- Produces: `ModerationRepository.create_pending(command: ModerationCommand, assessment: ModerationAssessment | None) -> ContentSubmission`.
- Produces: `ModerationRepository.record_decision(submission_id: UUID, assessment: ModerationAssessment) -> ModerationDecisionRecord`.
- Produces: `ModerationRepository.mark_allowed(submission_id: UUID, resource_id: UUID | None) -> None`, `mark_blocked(submission_id: UUID) -> None`, `schedule_retry(submission_id: UUID, attempt_count: int, next_attempt_at: datetime) -> None`.

- [ ] **Step 1: Write failing encryption and repository tests**

```python
def test_cipher_rejects_modified_ciphertext(cipher):
    encrypted = cipher.encrypt({"content": "격리할 글", "match": True})
    modified = encrypted.model_copy(update={"ciphertext": encrypted.ciphertext[:-2] + "AA"})
    with pytest.raises(InvalidTag):
        cipher.decrypt(modified)

async def test_pending_submission_stores_no_plaintext(session, repository):
    submission = await repository.create_pending(
        owner_id=USER_ID,
        content_type=ContentType.LETTER,
        operation="CREATE_LETTER",
        command={"content": "검열할 원문", "match": True},
        idempotency_key="moderation-key-01",
    )
    await session.flush()
    assert "검열할 원문" not in submission.ciphertext
    assert submission.status == "PENDING_REVIEW"
```

- [ ] **Step 2: Run tests and confirm missing model and cipher failures**

Run: `cd backend && python -m pytest tests/moderation/test_crypto.py tests/moderation/test_submission_repository.py -q`

Expected: tests fail because models and cipher are absent.

- [ ] **Step 3: Implement AES-GCM command encryption and peppered hashing**

```python
def encrypt(self, payload: dict[str, object]) -> EncryptedPayload:
    nonce = os.urandom(12)
    plaintext = json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    ciphertext = self.aesgcm.encrypt(nonce, plaintext, b"slowtalk-moderation-v1")
    return EncryptedPayload(
        ciphertext=base64.b64encode(ciphertext).decode("ascii"),
        nonce=base64.b64encode(nonce).decode("ascii"),
    )

def content_hash(text: str, pepper: str) -> str:
    normalized = unicodedata.normalize("NFC", text).strip().replace("\r\n", "\n")
    return hashlib.sha256(f"{pepper}:{normalized}".encode("utf-8")).hexdigest()
```

- [ ] **Step 4: Add submissions and immutable decisions tables**

`ContentSubmission` must include owner, content type, operation, optional target, ciphertext, nonce, hash, idempotency key, status, attempt count, next attempt, resolved resource ID, and timestamps. `ModerationDecisionRecord` must include submission ID, decision, JSON categories, severity, confidence, reason, provider/model/prompt version, and timestamp. Add unique `(owner_id, idempotency_key)` and indexes on `(status, next_attempt_at)`.

```python
class SubmissionStatus(StrEnum):
    PENDING_REVIEW = "PENDING_REVIEW"
    ALLOWED = "ALLOWED"
    BLOCKED = "BLOCKED"
    MANUAL_REVIEW = "MANUAL_REVIEW"
```

- [ ] **Step 5: Implement repository transitions with compare-and-set guards**

```python
statement = (
    update(ContentSubmission)
    .where(
        ContentSubmission.id == submission_id,
        ContentSubmission.status == SubmissionStatus.PENDING_REVIEW,
    )
    .values(status=SubmissionStatus.ALLOWED, resolved_at=datetime.now(UTC))
)
result = await self.session.execute(statement)
if result.rowcount != 1:
    raise ApiError("RESOURCE_CONFLICT", "검열 제출 상태가 이미 변경되었습니다.", 409)
```

- [ ] **Step 6: Run migration and persistence checks**

Run: `cd backend && python -m alembic upgrade head --sql`

Run: `cd backend && python -m pytest tests/moderation/test_crypto.py tests/moderation/test_submission_repository.py -q`

Expected: SQL generation prints both moderation tables, the submission status index, and the unique owner/idempotency constraint; all tests pass.

- [ ] **Step 7: Commit encrypted persistence**

```bash
git add backend/app/moderation backend/migrations
git commit -m "feat(moderation): persist encrypted review submissions"
```

### Task 3: Local rules and orchestration state machine

**Files:**
- Create: `backend/app/moderation/local_rules.py`
- Create: `backend/app/moderation/service.py`
- Test: `backend/tests/moderation/test_local_rules.py`
- Test: `backend/tests/moderation/test_orchestrator.py`

**Interfaces:**
- Consumes: `ModerationGateway`, `ModerationRepository`, `CommandCipher`.
- Produces: `ModerationOrchestrator.evaluate(command: ModerationCommand) -> ModerationOutcome`.
- Produces: `ModerationOutcome.immediate`, `.pending`, `.blocked` constructors.

- [ ] **Step 1: Write failing state-machine tests for ALLOW, REVIEW, BLOCK, and provider failure**

```python
async def test_provider_timeout_quarantines_without_domain_write(orchestrator, domain_writer):
    orchestrator.gateway.classify.side_effect = ModerationProviderUnavailable()
    outcome = await orchestrator.evaluate(letter_command("안녕하세요"))
    assert outcome.status is SubmissionStatus.PENDING_REVIEW
    assert outcome.http_status == 202
    domain_writer.assert_not_called()

async def test_high_confidence_violation_is_blocked(orchestrator):
    orchestrator.gateway.classify.return_value = assessment(
        decision="BLOCK", severity="HIGH", confidence=0.96, categories={"HARASSMENT"}
    )
    outcome = await orchestrator.evaluate(letter_command("차단 대상"))
    assert outcome.error_code == "CONTENT_POLICY_VIOLATION"
```

- [ ] **Step 2: Run orchestration tests and confirm they fail**

Run: `cd backend && python -m pytest tests/moderation/test_local_rules.py tests/moderation/test_orchestrator.py -q`

Expected: tests fail because the orchestrator is absent.

- [ ] **Step 3: Implement deterministic local rules**

Implement normalized repeated-message spam detection, URL count limit, email/phone/resident-number patterns, and maximum repeated-character runs. Local rules return `BLOCK` only for exact high-precision cases; uncertain patterns return `REVIEW`.

```python
def inspect(self, text: str) -> LocalRuleResult:
    normalized = normalize_text(text)
    if self.resident_number.search(normalized):
        return LocalRuleResult.review({ModerationCategory.PERSONAL_DATA}, "resident-id pattern")
    if len(self.url_pattern.findall(normalized)) > 3:
        return LocalRuleResult.block({ModerationCategory.SPAM}, "excessive urls")
    return LocalRuleResult.allow()
```

- [ ] **Step 4: Implement confidence normalization and quarantine behavior**

```python
if assessment.decision is ModerationDecision.BLOCK and (
    assessment.confidence >= settings.moderation_block_confidence
):
    await repository.record_blocked_hash(command, assessment)
    return ModerationOutcome.blocked(assessment.categories)
if assessment.decision is ModerationDecision.ALLOW and (
    assessment.confidence >= settings.moderation_allow_confidence
):
    return ModerationOutcome.immediate(assessment)
submission = await repository.create_pending_from_command(command, assessment)
await outbox.add("moderation.retry", submission.id, {"submissionId": str(submission.id)})
return ModerationOutcome.pending(submission.id)
```

- [ ] **Step 5: Verify the complete state matrix**

Run: `cd backend && python -m pytest tests/moderation/test_local_rules.py tests/moderation/test_orchestrator.py -q`

Expected: tests cover local block, provider allow, low-confidence review, provider block, timeout quarantine, and no plaintext logs; all pass.

- [ ] **Step 6: Commit the policy engine**

```bash
git add backend/app/moderation backend/tests/moderation
git commit -m "feat(moderation): add moderation policy state machine"
```

### Task 4: Pending status, retries, and manual decisions

**Files:**
- Create: `backend/app/moderation/router.py`
- Create: `backend/app/moderation/worker.py`
- Create: `backend/app/moderation/command_handlers.py`
- Create: `backend/app/events/__init__.py`
- Create: `backend/app/events/outbox.py`
- Modify: `backend/app/main.py`
- Create: `backend/tests/events/__init__.py`
- Create: `backend/tests/events/test_outbox.py`
- Test: `backend/tests/moderation/test_submission_api.py`
- Test: `backend/tests/moderation/test_retry_worker.py`

**Interfaces:**
- Produces: `GET /api/v1/moderation-submissions/{submissionId}`.
- Produces: `POST /api/v1/internal/moderation-submissions/{submissionId}/decision` protected by `X-Internal-Token`.
- Produces: `ModerationRetryWorker.process(submission_id: UUID) -> None`.
- Produces: `ModeratedCommandRegistry.execute(operation: str, command: dict[str, object]) -> UUID`.

- [ ] **Step 1: Write failing ownership, retry schedule, and idempotent replay tests**

```python
async def test_owner_can_read_pending_but_other_user_gets_404(client, alice, bob, pending):
    own = await client.get(f"/api/v1/moderation-submissions/{pending.id}", headers=alice)
    other = await client.get(f"/api/v1/moderation-submissions/{pending.id}", headers=bob)
    assert own.status_code == 200
    assert own.json()["data"]["status"] == "PENDING_REVIEW"
    assert other.status_code == 404

async def test_third_provider_failure_moves_to_manual_review(worker, submission):
    submission.attempt_count = 2
    worker.gateway.classify.side_effect = ModerationProviderUnavailable()
    await worker.process(submission.id)
    assert submission.status == "MANUAL_REVIEW"
```

- [ ] **Step 2: Run tests and confirm route and worker failures**

Run: `cd backend && python -m pytest tests/moderation/test_submission_api.py tests/moderation/test_retry_worker.py -q`

Expected: 404 route absence and missing worker failures.

- [ ] **Step 3: Implement author-visible status without ciphertext or confidence**

```python
ocr_result = None
if (
    submission.status is SubmissionStatus.ALLOWED
    and submission.content_type is ContentType.OCR_TEXT
    and submission.ciphertext is not None
    and submission.nonce is not None
):
    ocr_result = cipher.decrypt(
        EncryptedPayload(ciphertext=submission.ciphertext, nonce=submission.nonce)
    )
return success({
    "submissionId": str(submission.id),
    "status": submission.status,
    "resourceId": str(submission.resolved_resource_id) if submission.resolved_resource_id else None,
    "result": ocr_result,
    "categories": decision.public_categories if submission.status == "BLOCKED" else [],
})
```

Only an allowed OCR submission may populate `result`, and it contains exactly `{"text": extracted_text}`. Add `result_expires_at` set to 24 hours after allow and a cleanup worker that clears ciphertext and nonce after expiration.

- [ ] **Step 4: Implement exact retry schedule and domain replay**

```python
RETRY_DELAYS = (timedelta(minutes=1), timedelta(minutes=5), timedelta(minutes=30))

if submission.attempt_count >= len(RETRY_DELAYS) - 1:
    await repository.mark_manual_review(submission.id)
    return
await repository.schedule_retry(
    submission.id,
    attempt_count=submission.attempt_count + 1,
    next_attempt_at=datetime.now(UTC) + RETRY_DELAYS[submission.attempt_count + 1],
)
```

On `ALLOW`, decrypt the normalized command and execute it through the operation registry using the original idempotency key. Store the created resource ID before marking the submission allowed.

- [ ] **Step 5: Implement internal decision authentication and audit fields**

Reject missing or non-constant-time-matching `X-Internal-Token` with 404. An allow decision calls the same command registry; a block decision deletes ciphertext and nonce and preserves only the peppered hash and public categories.

- [ ] **Step 6: Run status and worker tests**

Run: `cd backend && python -m pytest tests/moderation/test_submission_api.py tests/moderation/test_retry_worker.py tests/events/test_outbox.py -q`

Expected: ownership, 1/5/30 minute schedules, third-failure manual review, internal auth, duplicate worker delivery, allowed OCR result retrieval, and 24-hour ciphertext cleanup tests pass.

- [ ] **Step 7: Commit the review lifecycle**

```bash
git add backend/app/moderation backend/app/events backend/app/main.py backend/tests/moderation
git commit -m "feat(moderation): add review status and retry lifecycle"
```

### Task 5: Integrate letters and chat without exposing quarantined content

**Files:**
- Create: `backend/app/letters/service.py`
- Modify: `backend/app/letters/router.py`
- Create: `backend/app/chat/service.py`
- Modify: `backend/app/chat/router.py`
- Modify: `backend/app/letters/schemas.py`
- Modify: `backend/app/chat/schemas.py`
- Test: `backend/tests/moderation/test_letter_chat_moderation.py`

**Interfaces:**
- Consumes: `ModerationOrchestrator.evaluate`, `ModeratedCommandRegistry`.
- Produces: `LetterCommandHandler.execute(owner_id, payload, idempotency_key) -> LetterResult`.
- Produces: `ChatCommandHandler.execute(owner_id, room_id, payload) -> MessageResult`.

- [ ] **Step 1: Write failing API tests for immediate, pending, and blocked letter/chat requests**

```python
async def test_pending_letter_creates_no_letter_room_or_message(client, alice, pending_gateway, session):
    response = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": "pending-letter-01"},
        json={"content": "검토 대상", "match": True},
    )
    assert response.status_code == 202
    assert response.json()["data"]["moderationStatus"] == "PENDING_REVIEW"
    assert await scalar_count(session, Letter) == 0
    assert await scalar_count(session, ChatMessage) == 0
```

- [ ] **Step 2: Run tests and confirm current routes persist before moderation**

Run: `cd backend && python -m pytest tests/moderation/test_letter_chat_moderation.py -q`

Expected: pending and blocked cases fail because current routers write directly.

- [ ] **Step 3: Extract existing writes into idempotent command handlers**

Move letter delivery from the router into `LetterCommandHandler`; move chat message creation into `ChatCommandHandler`. Routers must only authenticate, validate, call moderation, and serialize outcomes.

```python
outcome = await moderation.evaluate(
    ModerationCommand(
        owner_id=user_id,
        content_type=ContentType.LETTER,
        operation="CREATE_LETTER",
        text=request.content,
        payload=request.model_dump(),
        idempotency_key=idempotency_key,
    )
)
if outcome.is_pending:
    return JSONResponse(status_code=202, content=success(outcome.public_data))
if outcome.is_blocked:
    raise ApiError("CONTENT_POLICY_VIOLATION", "콘텐츠 정책을 확인해 주세요.", 422)
return success((await handler.execute(user_id, request, idempotency_key)).to_dict())
```

- [ ] **Step 4: Register replay handlers and preserve message idempotency**

Register `CREATE_LETTER` and `CREATE_CHAT_MESSAGE` with typed Pydantic reconstruction. Duplicate retry delivery must return the original domain resource and never create another outbox event.

- [ ] **Step 5: Run letter, chat, and moderation regression tests**

Run: `cd backend && python -m pytest tests/letters tests/chat tests/moderation/test_letter_chat_moderation.py -q`

Expected: existing success behavior plus `201/202/422` moderation behavior all pass.

- [ ] **Step 6: Commit letter and chat integration**

```bash
git add backend/app/letters backend/app/chat backend/tests
git commit -m "feat(moderation): gate letters and chat before persistence"
```

### Task 6: Integrate feeds, comments, reports, and OCR

**Files:**
- Create: `backend/app/feeds/service.py`
- Modify: `backend/app/feeds/router.py`
- Create: `backend/app/reports/service.py`
- Modify: `backend/app/reports/router.py`
- Modify: `backend/app/ai/router.py`
- Modify: `backend/app/moderation/command_handlers.py`
- Test: `backend/tests/moderation/test_feed_comment_report_moderation.py`
- Test: `backend/tests/moderation/test_ocr_moderation.py`

**Interfaces:**
- Consumes: the same `ModerationOrchestrator` and command registry as Task 5.
- Produces replay operations: `CREATE_FEED`, `PATCH_FEED`, `CREATE_COMMENT`, `PATCH_COMMENT`, `CREATE_REPORT`.
- OCR uses `ModerationOrchestrator.evaluate_ocr(owner_id, text) -> ModerationOutcome` and never stores the image.

- [ ] **Step 1: Write failing tests for every content surface and edit behavior**

```python
@pytest.mark.parametrize("case", ["feed", "comment", "report"])
async def test_blocked_content_is_not_persisted(case, moderated_clients, session):
    response = await moderated_clients[case].send_blocked()
    assert response.status_code == 422
    assert await moderated_clients[case].domain_count(session) == 0

async def test_pending_feed_edit_keeps_published_version(client, owner, feed, pending_gateway):
    response = await client.patch(
        f"/api/v1/feeds/{feed.id}", headers=owner, json={"content": "검토 중인 수정"}
    )
    assert response.status_code == 202
    visible = await client.get(f"/api/v1/feeds/{feed.id}", headers=owner)
    assert visible.json()["data"]["content"] == feed.content
```

- [ ] **Step 2: Run tests and confirm direct-write failures**

Run: `cd backend && python -m pytest tests/moderation/test_feed_comment_report_moderation.py tests/moderation/test_ocr_moderation.py -q`

Expected: tests fail because current routes bypass moderation.

- [ ] **Step 3: Extract feed/comment/report handlers and gate routers**

Each handler must enforce ownership and domain validation again during replay. A pending update stores the requested patch only in encrypted submission data; it must not mutate the currently visible row.

- [ ] **Step 4: Moderate OCR text before returning it**

```python
text = await assistant.ocr(content, mime)
outcome = await moderation.evaluate_ocr(owner_id=user_id, text=text)
if outcome.is_pending:
    return JSONResponse(status_code=202, content=success(outcome.public_data))
if outcome.is_blocked:
    raise ApiError("CONTENT_POLICY_VIOLATION", "OCR 결과가 콘텐츠 정책을 위반합니다.", 422)
return success({"text": text})
```

Assert the OpenAPI schema contains `/letters/ocr` and `/reports/ocr` and does not contain `/feeds/ocr`.

- [ ] **Step 5: Run all affected domain tests**

Run: `cd backend && python -m pytest tests/feeds tests/reports tests/ai tests/moderation -q`

Expected: original behavior and moderation cases pass.

- [ ] **Step 6: Commit remaining content integrations**

```bash
git add backend/app/feeds backend/app/reports backend/app/ai backend/app/moderation backend/tests
git commit -m "feat(moderation): gate social reports and OCR content"
```

### Task 7: Privacy, shadow rollout, OpenAPI, and final moderation gate

**Files:**
- Modify: `backend/app/core/redaction.py`
- Modify: `backend/app/main.py`
- Create: `backend/app/moderation/metrics.py`
- Create: `backend/tests/security/test_moderation_privacy.py`
- Modify: `backend/tests/contract/test_openapi.py`
- Modify: `backend/openapi/slowtalk-v1.json`
- Modify: `backend/README.md`

**Interfaces:**
- Produces metrics without user identifiers: decisions, categories, latency buckets, retry counts, manual-review counts.
- `MODERATION_MODE=shadow` records decisions without changing domain behavior; `enforce` applies the full state machine.

- [ ] **Step 1: Write failing redaction, shadow-mode, readiness, and OpenAPI tests**

```python
def test_moderation_sensitive_fields_are_redacted():
    payload = {"ciphertext": "secret", "nonce": "nonce", "embedding": [0.1], "content": "raw"}
    assert redact_log_fields(payload) == {
        "ciphertext": "[REDACTED]",
        "nonce": "[REDACTED]",
        "embedding": "[REDACTED]",
        "content": "[REDACTED]",
    }

async def test_shadow_block_does_not_change_success_response(shadow_client, blocking_gateway):
    response = await shadow_client.post_feed("관찰 전용 텍스트")
    assert response.status_code == 201
    assert blocking_gateway.calls == 1
```

- [ ] **Step 2: Run security and contract tests and confirm failures**

Run: `cd backend && python -m pytest tests/security/test_moderation_privacy.py tests/contract/test_openapi.py -q`

Expected: new sensitive keys and moderation paths are not covered.

- [ ] **Step 3: Add privacy-safe metrics and readiness checks**

Metrics may use only content type, decision, category, model version, and bounded latency buckets. Readiness fails in `enforce` mode if required secrets or confidence values are missing; liveness remains independent of Upstage availability.

- [ ] **Step 4: Regenerate and freeze OpenAPI**

Run: `cd backend && python scripts/export_openapi.py openapi/slowtalk-v1.json`

Run: `cd backend && python -m pytest tests/contract/test_openapi.py -q`

Expected: the generated contract equals the committed snapshot and includes `202` and `422` responses.

- [ ] **Step 5: Run the complete moderation quality gate**

Run: `cd backend && python -m ruff check . && python -m mypy app && python -m pytest -q`

Expected: all checks exit 0. The suite proves that pending or blocked content is absent from public domain tables and responses.

- [ ] **Step 6: Commit rollout and contract changes**

```bash
git add backend
git commit -m "test(moderation): harden privacy rollout and API contract"
```
