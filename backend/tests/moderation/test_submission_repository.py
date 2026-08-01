import base64
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import delete, func, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.models import User
from app.core.errors import ApiError
from app.moderation.crypto import CommandCipher, content_hash
from app.moderation.models import (
    ContentSubmission,
    ModerationDecisionRecord,
    SubmissionStatus,
)
from app.moderation.repository import (
    InvalidRetrySchedule,
    ModerationCommand,
    ModerationRepository,
)
from app.moderation.schemas import (
    ContentType,
    ModerationAssessment,
    ModerationCategory,
    ModerationDecision,
    Severity,
)

USER_ID = uuid4()


@pytest_asyncio.fixture
async def session(session_factory) -> AsyncSession:
    async with session_factory() as value:
        yield value


@pytest.fixture
def repository(session: AsyncSession) -> ModerationRepository:
    cipher = CommandCipher(base64.b64encode(b"e" * 32).decode("ascii"))
    return ModerationRepository(session, cipher, "test-pepper")


def assessment(decision: ModerationDecision = ModerationDecision.REVIEW) -> ModerationAssessment:
    return ModerationAssessment(
        decision=decision,
        categories={ModerationCategory.SPAM},
        severity=Severity.MEDIUM,
        confidence=0.73,
        reason="requires review",
        provider_request_id="request-1",
    )


async def create_pending(repository: ModerationRepository) -> ContentSubmission:
    return await repository.create_pending(
        ModerationCommand(
            owner_id=USER_ID,
            content_type=ContentType.LETTER,
            operation="CREATE_LETTER",
            text="검토할 원문",
            payload={"content": "검토할 원문", "match": True},
            idempotency_key="moderation-key-01",
        ),
        assessment=assessment(),
    )


async def test_pending_submission_stores_no_plaintext(
    session: AsyncSession, repository: ModerationRepository
) -> None:
    submission = await create_pending(repository)
    await session.flush()

    assert "검토할 원문" not in submission.ciphertext
    assert submission.status is SubmissionStatus.PENDING_REVIEW
    assert submission.content_hash == content_hash("검토할 원문", "test-pepper")
    assert repository.decrypt_command(submission) == {"content": "검토할 원문", "match": True}


async def test_owner_idempotency_key_is_unique(
    session: AsyncSession, repository: ModerationRepository
) -> None:
    await create_pending(repository)
    await session.flush()
    with pytest.raises(IntegrityError):
        await repository.create_pending(
            ModerationCommand(
                owner_id=USER_ID,
                content_type=ContentType.LETTER,
                operation="CREATE_LETTER",
                text="different",
                payload={"content": "different"},
                idempotency_key="moderation-key-01",
            )
        )


async def test_decisions_are_append_only_and_contain_no_plaintext(
    session: AsyncSession, repository: ModerationRepository
) -> None:
    submission = await create_pending(repository)
    decision = await repository.record_decision(submission.id, assessment())
    await session.flush()

    assert decision.submission_id == submission.id
    assert decision.categories == ["SPAM"]
    assert not hasattr(decision, "content")

    decision.reason = "changed"
    with pytest.raises(ValueError, match="immutable"):
        await session.flush()


async def test_direct_orm_decision_delete_is_rejected(
    session: AsyncSession, repository: ModerationRepository
) -> None:
    submission = await create_pending(repository)
    decision = await repository.record_decision(submission.id, assessment())
    await session.flush()

    await session.delete(decision)
    with pytest.raises(ValueError, match="immutable"):
        await session.flush()


async def test_mark_allowed_uses_compare_and_set(
    session: AsyncSession, repository: ModerationRepository
) -> None:
    submission = await create_pending(repository)
    await session.flush()
    resource_id = uuid4()

    await repository.mark_allowed(submission.id, resource_id)
    await session.refresh(submission)
    assert submission.status is SubmissionStatus.ALLOWED
    assert submission.resolved_resource_id == resource_id
    assert submission.resolved_at is not None

    with pytest.raises(ApiError) as caught:
        await repository.mark_blocked(submission.id)
    assert caught.value.code == "RESOURCE_CONFLICT"
    assert caught.value.status_code == 409


async def test_schedule_retry_uses_compare_and_set(
    session: AsyncSession, repository: ModerationRepository
) -> None:
    submission = await create_pending(repository)
    await session.flush()
    retry_at = datetime.now(UTC) + timedelta(minutes=1)

    await repository.schedule_retry(submission.id, 1, retry_at)
    await session.refresh(submission)
    assert submission.attempt_count == 1
    assert submission.next_attempt_at.replace(tzinfo=UTC) == retry_at

    with pytest.raises(ApiError, match="상태"):
        await repository.schedule_retry(submission.id, 1, retry_at)

    await repository.mark_blocked(submission.id)
    with pytest.raises(ApiError, match="상태"):
        await repository.schedule_retry(submission.id, 2, retry_at)


@pytest.mark.parametrize("attempt_count", [True, False, -1, 0, 1.5, "1"])
async def test_schedule_retry_rejects_invalid_attempt_count(
    repository: ModerationRepository, attempt_count: object
) -> None:
    with pytest.raises(InvalidRetrySchedule) as caught:
        await repository.schedule_retry(
            uuid4(), attempt_count, datetime.now(UTC)  # type: ignore[arg-type]
        )

    assert str(caught.value) == "invalid moderation retry schedule"


async def test_schedule_retry_rejects_timezone_naive_datetime(
    repository: ModerationRepository,
) -> None:
    with pytest.raises(InvalidRetrySchedule) as caught:
        await repository.schedule_retry(uuid4(), 1, datetime.now())

    assert str(caught.value) == "invalid moderation retry schedule"


def test_moderation_command_repr_hides_text_and_payload() -> None:
    command = ModerationCommand(
        owner_id=uuid4(),
        content_type=ContentType.LETTER,
        operation="CREATE_LETTER",
        text="TEXT_MARKER",
        payload={"content": "PAYLOAD_MARKER"},
        idempotency_key="key",
    )

    rendered = repr(command)
    assert "TEXT_MARKER" not in rendered
    assert "PAYLOAD_MARKER" not in rendered


async def test_model_metadata_contains_required_constraints_and_index() -> None:
    constraints = {constraint.name for constraint in ContentSubmission.__table__.constraints}
    indexes = {index.name for index in ContentSubmission.__table__.indexes}

    assert "uq_content_submissions_owner_id_idempotency_key" in constraints
    assert "ix_content_submissions_status_next_attempt_at" in indexes
    assert ModerationDecisionRecord.__table__.name == "moderation_decisions"


async def test_record_decision_rejects_missing_submission(
    repository: ModerationRepository,
) -> None:
    with pytest.raises(ApiError) as caught:
        await repository.record_decision(uuid4(), assessment())
    assert caught.value.code == "RESOURCE_NOT_FOUND"


async def test_submission_and_user_deletes_cascade_decisions(session_factory) -> None:
    async with session_factory() as cascade_session:
        await cascade_session.execute(text("PRAGMA foreign_keys=ON"))
        owner = User(
            id=uuid4(),
            email="cascade@example.com",
            password_hash="hash",
            nickname="cascade",
        )
        cascade_session.add(owner)
        await cascade_session.flush()
        cascade_repository = ModerationRepository(
            cascade_session,
            CommandCipher(base64.b64encode(b"c" * 32).decode("ascii")),
            "pepper",
        )

        first = await cascade_repository.create_pending(
            ModerationCommand(
                owner_id=owner.id,
                content_type=ContentType.LETTER,
                operation="CREATE_LETTER",
                text="first",
                payload={"content": "first"},
                idempotency_key="first",
            ),
            assessment(),
        )
        await cascade_session.delete(first)
        await cascade_session.flush()
        assert await cascade_session.scalar(
            select(func.count()).select_from(ModerationDecisionRecord)
        ) == 0

        await cascade_repository.create_pending(
            ModerationCommand(
                owner_id=owner.id,
                content_type=ContentType.LETTER,
                operation="CREATE_LETTER",
                text="second",
                payload={"content": "second"},
                idempotency_key="second",
            ),
            assessment(),
        )
        await cascade_session.execute(delete(User).where(User.id == owner.id))
        await cascade_session.flush()
        assert await cascade_session.scalar(
            select(func.count()).select_from(ContentSubmission)
        ) == 0
        assert await cascade_session.scalar(
            select(func.count()).select_from(ModerationDecisionRecord)
        ) == 0
