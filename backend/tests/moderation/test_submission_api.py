import base64
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.security import create_access_token
from app.core.config import get_settings
from app.db.session import get_session
from app.main import create_app
from app.moderation.command_handlers import ModeratedCommandRegistry
from app.moderation.crypto import CommandCipher
from app.moderation.models import ContentSubmission, ModerationDecisionRecord, SubmissionStatus
from app.moderation.repository import ModerationCommand, ModerationRepository
from app.moderation.router import get_command_registry
from app.moderation.schemas import ContentType


@pytest_asyncio.fixture
async def moderation_client(
    monkeypatch, session_factory
) -> AsyncIterator[tuple[AsyncClient, object]]:
    key = base64.b64encode(b"a" * 32).decode()
    monkeypatch.setenv("MODERATION_ENCRYPTION_KEY", key)
    monkeypatch.setenv("CONTENT_HASH_PEPPER", "api-pepper")
    monkeypatch.setenv("INTERNAL_MODERATION_TOKEN", "i" * 32)
    get_settings.cache_clear()
    application = create_app()

    async def override_session() -> AsyncIterator[AsyncSession]:
        async with session_factory() as session:
            yield session

    application.dependency_overrides[get_session] = override_session
    async with AsyncClient(
        transport=ASGITransport(app=application), base_url="http://test"
    ) as client:
        yield client, session_factory
    get_settings.cache_clear()


async def create_submission(session_factory, owner_id, *, content_type=ContentType.LETTER):
    async with session_factory() as session:
        repo = ModerationRepository(
            session,
            CommandCipher(base64.b64encode(b"a" * 32).decode()),
            "api-pepper",
        )
        payload = {"text": "recognized text"} if content_type is ContentType.OCR_TEXT else {
            "content": "private marker"
        }
        submission = await repo.create_pending(
            ModerationCommand(
                owner_id=owner_id,
                content_type=content_type,
                operation="CREATE_LETTER",
                text=str(next(iter(payload.values()))),
                payload=payload,
                idempotency_key=str(uuid4()),
            )
        )
        await session.commit()
        return submission.id


async def test_owner_can_read_pending_but_other_and_missing_are_404(
    moderation_client,
) -> None:
    client, factory = moderation_client
    alice, bob = uuid4(), uuid4()
    submission_id = await create_submission(factory, alice)

    own = await client.get(
        f"/api/v1/moderation-submissions/{submission_id}",
        headers={"Authorization": f"Bearer {create_access_token(alice)}"},
    )
    other = await client.get(
        f"/api/v1/moderation-submissions/{submission_id}",
        headers={"Authorization": f"Bearer {create_access_token(bob)}"},
    )
    missing = await client.get(
        f"/api/v1/moderation-submissions/{uuid4()}",
        headers={"Authorization": f"Bearer {create_access_token(alice)}"},
    )

    assert own.status_code == 200
    assert own.json()["data"]["status"] == "PENDING_REVIEW"
    serialized = own.text
    for forbidden in ("ciphertext", "nonce", "confidence", "reason", "private marker"):
        assert forbidden not in serialized
    assert other.status_code == missing.status_code == 404
    assert other.json()["error"]["code"] == missing.json()["error"]["code"]


async def test_internal_token_failure_is_always_404(moderation_client) -> None:
    client, factory = moderation_client
    submission_id = await create_submission(factory, uuid4())
    body = {"decision": "BLOCK", "categories": ["SPAM"]}
    missing = await client.post(
        f"/api/v1/internal/moderation-submissions/{submission_id}/decision", json=body
    )
    wrong = await client.post(
        f"/api/v1/internal/moderation-submissions/{submission_id}/decision",
        json=body,
        headers={"X-Internal-Token": "wrong"},
    )
    assert missing.status_code == wrong.status_code == 404


async def test_manual_block_clears_payload_and_exposes_only_public_categories(
    moderation_client,
) -> None:
    client, factory = moderation_client
    owner = uuid4()
    submission_id = await create_submission(factory, owner)
    decided = await client.post(
        f"/api/v1/internal/moderation-submissions/{submission_id}/decision",
        json={"decision": "BLOCK", "categories": ["SPAM"]},
        headers={"X-Internal-Token": "i" * 32},
    )
    assert decided.status_code == 200

    status = await client.get(
        f"/api/v1/moderation-submissions/{submission_id}",
        headers={"Authorization": f"Bearer {create_access_token(owner)}"},
    )
    assert status.json()["data"]["categories"] == ["SPAM"]
    async with factory() as session:
        submission = await session.get(ContentSubmission, submission_id)
        assert submission.status is SubmissionStatus.BLOCKED
        assert submission.ciphertext is None and submission.nonce is None


async def test_allowed_ocr_returns_exact_text_shape_only_before_expiry(
    moderation_client,
) -> None:
    client, factory = moderation_client
    owner = uuid4()
    submission_id = await create_submission(factory, owner, content_type=ContentType.OCR_TEXT)
    async with factory() as session:
        repo = ModerationRepository(
            session,
            CommandCipher(base64.b64encode(b"a" * 32).decode()),
            "api-pepper",
        )
        await repo.schedule_retry(submission_id, 1, datetime.now(UTC))
        await repo.schedule_retry(submission_id, 2, datetime.now(UTC))
        await repo.mark_manual_review(submission_id, attempt_count=3)
        await session.commit()
    decided = await client.post(
        f"/api/v1/internal/moderation-submissions/{submission_id}/decision",
        json={"decision": "ALLOW"},
        headers={"X-Internal-Token": "i" * 32},
    )
    assert decided.status_code == 200
    status = await client.get(
        f"/api/v1/moderation-submissions/{submission_id}",
        headers={"Authorization": f"Bearer {create_access_token(owner)}"},
    )
    assert status.json()["data"]["result"] == {"text": "recognized text"}

    async with factory() as session:
        submission = await session.get(ContentSubmission, submission_id)
        submission.result_expires_at = datetime.now(UTC) - timedelta(seconds=1)
        await session.commit()
    expired = await client.get(
        f"/api/v1/moderation-submissions/{submission_id}",
        headers={"Authorization": f"Bearer {create_access_token(owner)}"},
    )
    assert expired.json()["data"]["result"] is None


async def test_manual_review_can_be_allowed_idempotently_with_original_key(
    moderation_client,
) -> None:
    client, factory = moderation_client
    owner = uuid4()
    submission_id = await create_submission(factory, owner)
    async with factory() as session:
        repo = ModerationRepository(
            session,
            CommandCipher(base64.b64encode(b"a" * 32).decode()),
            "api-pepper",
        )
        await repo.schedule_retry(submission_id, 1, datetime.now(UTC))
        await repo.schedule_retry(submission_id, 2, datetime.now(UTC))
        await repo.mark_manual_review(submission_id, attempt_count=3)
        current = await repo.get(submission_id)
        assert current is not None
        original_key = current.idempotency_key
        await session.commit()

    registry = ModeratedCommandRegistry()
    calls: list[str] = []
    resource_id = uuid4()

    async def handler(_command: dict[str, object], key: str):
        calls.append(key)
        return resource_id

    registry.register("CREATE_LETTER", handler)
    client._transport.app.dependency_overrides[get_command_registry] = lambda: registry
    request = {"decision": "ALLOW"}
    headers = {"X-Internal-Token": "i" * 32}
    first = await client.post(
        f"/api/v1/internal/moderation-submissions/{submission_id}/decision",
        json=request,
        headers=headers,
    )
    duplicate = await client.post(
        f"/api/v1/internal/moderation-submissions/{submission_id}/decision",
        json=request,
        headers=headers,
    )
    assert first.status_code == duplicate.status_code == 200
    assert first.json()["data"]["resourceId"] == str(resource_id)
    assert calls == [original_key]
    async with factory() as session:
        submission = await session.get(ContentSubmission, submission_id)
        decisions = list(
            await session.scalars(
                select(ModerationDecisionRecord).where(
                    ModerationDecisionRecord.submission_id == submission_id
                )
            )
        )
        assert submission.status is SubmissionStatus.ALLOWED
        assert submission.ciphertext is None and submission.nonce is None
        assert len(decisions) == 1
        assert decisions[0].provider == "internal"
        assert decisions[0].model == "manual"
        assert decisions[0].prompt_version == "v1"
        assert decisions[0].reason == "MANUAL_ALLOW"


async def test_manual_review_can_be_blocked_idempotently_and_retains_audit(
    moderation_client,
) -> None:
    client, factory = moderation_client
    owner = uuid4()
    submission_id = await create_submission(factory, owner)
    async with factory() as session:
        repo = ModerationRepository(
            session,
            CommandCipher(base64.b64encode(b"a" * 32).decode()),
            "api-pepper",
        )
        await repo.schedule_retry(submission_id, 1, datetime.now(UTC))
        await repo.schedule_retry(submission_id, 2, datetime.now(UTC))
        await repo.mark_manual_review(submission_id, attempt_count=3)
        await session.commit()

    request = {"decision": "BLOCK", "categories": ["HARASSMENT"]}
    headers = {"X-Internal-Token": "i" * 32}
    first = await client.post(
        f"/api/v1/internal/moderation-submissions/{submission_id}/decision",
        json=request,
        headers=headers,
    )
    duplicate = await client.post(
        f"/api/v1/internal/moderation-submissions/{submission_id}/decision",
        json=request,
        headers=headers,
    )
    assert first.status_code == duplicate.status_code == 200
    async with factory() as session:
        submission = await session.get(ContentSubmission, submission_id)
        decisions = list(
            await session.scalars(
                select(ModerationDecisionRecord).where(
                    ModerationDecisionRecord.submission_id == submission_id
                )
            )
        )
        assert submission.status is SubmissionStatus.BLOCKED
        assert submission.ciphertext is None and submission.nonce is None
        assert submission.content_hash
        assert len(decisions) == 1
        assert decisions[0].categories == ["HARASSMENT"]
        assert decisions[0].provider == "internal"
        assert decisions[0].reason == "MANUAL_BLOCK"


async def test_manual_decision_rejects_free_reason_and_actor_without_echo(
    moderation_client,
) -> None:
    client, factory = moderation_client
    submission_id = await create_submission(factory, uuid4())
    marker = "RAW-MANUAL-MARKER"
    response = await client.post(
        f"/api/v1/internal/moderation-submissions/{submission_id}/decision",
        json={"decision": "ALLOW", "reason": marker, "actorId": marker},
        headers={"X-Internal-Token": "i" * 32},
    )
    assert response.status_code == 400
    assert marker not in response.text
