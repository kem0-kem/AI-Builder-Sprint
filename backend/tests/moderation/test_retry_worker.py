import base64
import traceback
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

from sqlalchemy import select

from app.events.outbox import OutboxEvent
from app.moderation.command_handlers import (
    ModeratedCommandExecutionFailed,
    ModeratedCommandRegistry,
)
from app.moderation.crypto import CommandCipher
from app.moderation.gateway import ModerationProviderUnavailable
from app.moderation.models import SubmissionStatus
from app.moderation.repository import ModerationCommand, ModerationRepository
from app.moderation.schemas import (
    ContentType,
    ModerationAssessment,
    ModerationCategory,
    ModerationDecision,
    Severity,
)
from app.moderation.worker import (
    RETRY_DELAYS,
    ModerationResultCleanupWorker,
    ModerationRetryWorker,
)


class StubGateway:
    def __init__(self, result: ModerationAssessment | Exception) -> None:
        self.result = result

    async def classify(self, _content_type: ContentType, _text: str) -> ModerationAssessment:
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


def allow() -> ModerationAssessment:
    return ModerationAssessment(
        decision=ModerationDecision.ALLOW,
        categories=set(),
        severity=Severity.NONE,
        confidence=0.99,
        reason="safe",
    )


def block() -> ModerationAssessment:
    return ModerationAssessment(
        decision=ModerationDecision.BLOCK,
        categories={ModerationCategory.HARASSMENT},
        severity=Severity.HIGH,
        confidence=0.99,
        reason="policy",
    )


def repository(session) -> ModerationRepository:
    return ModerationRepository(
        session,
        CommandCipher(base64.b64encode(b"w" * 32).decode()),
        "worker-pepper",
    )


async def pending(repo: ModerationRepository, *, content_type=ContentType.LETTER):
    payload = {"text": "ocr result"} if content_type is ContentType.OCR_TEXT else {
        "content": "normalized content"
    }
    return await repo.create_pending(
        ModerationCommand(
            owner_id=uuid4(),
            content_type=content_type,
            operation="CREATE_LETTER",
            text=str(next(iter(payload.values()))),
            payload=payload,
            idempotency_key="original-key",
        )
    )


def worker(gateway, repo, registry) -> ModerationRetryWorker:
    return ModerationRetryWorker(
        gateway,
        repo,
        registry,
        allow_confidence=0.8,
        block_confidence=0.9,
    )


def test_retry_contract_is_one_five_thirty_minutes() -> None:
    assert tuple(int(delay.total_seconds()) for delay in RETRY_DELAYS) == (60, 300, 1800)


async def test_initial_pending_schedules_one_minute_and_single_outbox(session_factory) -> None:
    async with session_factory() as session:
        repo = repository(session)
        before = datetime.now(UTC)
        submission = await pending(repo)
        assert submission.attempt_count == 0
        assert timedelta(seconds=59) <= submission.next_attempt_at - before <= timedelta(seconds=61)
        events = list(await session.scalars(select(OutboxEvent)))
        assert [(event.topic, event.aggregate_id) for event in events] == [
            ("moderation.retry", submission.id)
        ]
        assert events[0].available_at.replace(tzinfo=UTC) == submission.next_attempt_at
        assert submission.next_attempt_at.isoformat() in events[0].payload


async def test_early_process_does_not_classify_or_change_attempts(session_factory) -> None:
    class CountingGateway(StubGateway):
        calls = 0

        async def classify(
            self, content_type: ContentType, text: str
        ) -> ModerationAssessment:
            self.calls += 1
            return await super().classify(content_type, text)

    async with session_factory() as session:
        repo = repository(session)
        submission = await pending(repo)
        due_at = submission.next_attempt_at
        gateway = CountingGateway(ModerationProviderUnavailable())
        retry = worker(gateway, repo, ModeratedCommandRegistry())
        early = due_at - timedelta(seconds=1)
        await retry.process(submission.id, now=early)
        current = await repo.get(submission.id)
        assert gateway.calls == 0
        assert current is not None and current.attempt_count == 0
        assert len(list(await session.scalars(select(OutboxEvent)))) == 1

        await retry.process(submission.id, now=due_at)
        assert gateway.calls == 1


async def test_three_worker_failures_use_five_thirty_then_manual(session_factory) -> None:
    async with session_factory() as session:
        repo = repository(session)
        submission = await pending(repo)
        registry = ModeratedCommandRegistry()
        retry = worker(StubGateway(ModerationProviderUnavailable()), repo, registry)
        now = submission.next_attempt_at

        await retry.process(submission.id, now=now)
        current = await repo.get(submission.id)
        assert current is not None and current.attempt_count == 1
        assert current.next_attempt_at.replace(tzinfo=UTC) == now + timedelta(minutes=5)

        await retry.process(submission.id, now=now + timedelta(minutes=5))
        current = await repo.get(submission.id)
        assert current is not None and current.attempt_count == 2
        assert current.next_attempt_at.replace(tzinfo=UTC) == now + timedelta(minutes=35)

        await retry.process(submission.id, now=now + timedelta(minutes=35))
        current = await repo.get(submission.id)
        assert current is not None
        assert current.attempt_count == 3
        assert current.status is SubmissionStatus.MANUAL_REVIEW
        events = list(
            await session.scalars(
                select(OutboxEvent).where(OutboxEvent.aggregate_id == submission.id)
            )
        )
        assert len(events) == 3


async def test_automatic_worker_never_resolves_manual_review(session_factory) -> None:
    class CountingGateway(StubGateway):
        calls = 0

        async def classify(
            self, content_type: ContentType, text: str
        ) -> ModerationAssessment:
            self.calls += 1
            return await super().classify(content_type, text)

    async with session_factory() as session:
        repo = repository(session)
        submission = await pending(repo)
        await repo.schedule_retry(submission.id, 1, datetime.now(UTC))
        await repo.schedule_retry(submission.id, 2, datetime.now(UTC))
        await repo.mark_manual_review(submission.id, attempt_count=3)
        gateway = CountingGateway(allow())
        await worker(gateway, repo, ModeratedCommandRegistry()).process(
            submission.id, now=datetime.now(UTC) + timedelta(days=1)
        )
        current = await repo.get(submission.id)
        assert gateway.calls == 0
        assert current is not None and current.status is SubmissionStatus.MANUAL_REVIEW


async def test_allow_replays_original_idempotency_and_duplicate_is_noop(session_factory) -> None:
    async with session_factory() as session:
        repo = repository(session)
        submission = await pending(repo)
        registry = ModeratedCommandRegistry()
        calls: list[tuple[dict[str, object], str]] = []
        resource_id = uuid4()

        async def handler(command: dict[str, object], key: str) -> UUID:
            calls.append((command, key))
            return resource_id

        registry.register("CREATE_LETTER", handler)
        retry = worker(StubGateway(allow()), repo, registry)
        await retry.process(submission.id, now=submission.next_attempt_at)
        await retry.process(submission.id)

        current = await repo.get(submission.id)
        assert current is not None and current.status is SubmissionStatus.ALLOWED
        assert current.resolved_resource_id == resource_id
        assert current.ciphertext is None and current.nonce is None
        assert calls == [({"content": "normalized content"}, "original-key")]


async def test_block_deletes_quarantine_but_preserves_hash_and_categories(
    session_factory,
) -> None:
    async with session_factory() as session:
        repo = repository(session)
        submission = await pending(repo)
        digest = submission.content_hash
        await worker(StubGateway(block()), repo, ModeratedCommandRegistry()).process(
            submission.id, now=submission.next_attempt_at
        )
        current = await repo.get(submission.id)
        decision = await repo.latest_decision(submission.id)
        assert current is not None and current.status is SubmissionStatus.BLOCKED
        assert current.ciphertext is None and current.nonce is None
        assert current.content_hash == digest
        assert decision is not None and decision.categories == ["HARASSMENT"]


async def test_ocr_result_expires_and_cleanup_removes_ciphertext(session_factory) -> None:
    async with session_factory() as session:
        repo = repository(session)
        submission = await pending(repo, content_type=ContentType.OCR_TEXT)
        now = submission.next_attempt_at
        await worker(StubGateway(allow()), repo, ModeratedCommandRegistry()).process(
            submission.id, now=now
        )
        current = await repo.get(submission.id)
        assert current is not None
        assert current.result_expires_at.replace(tzinfo=UTC) == now + timedelta(hours=24)
        assert repo.decrypt_command(current) == {"text": "ocr result"}

        cleared = await ModerationResultCleanupWorker(repo).process(
            now=now + timedelta(hours=24)
        )
        current = await repo.get(submission.id)
        assert cleared == 1
        assert current is not None and current.ciphertext is None and current.nonce is None


async def test_local_review_overrides_provider_allow_and_retries(session_factory) -> None:
    async with session_factory() as session:
        repo = repository(session)
        submission = await repo.create_pending(
            ModerationCommand(
                owner_id=uuid4(),
                content_type=ContentType.LETTER,
                operation="CREATE_LETTER",
                text="contact me@example.com",
                payload={"content": "contact me@example.com"},
                idempotency_key="local-review",
            )
        )
        await worker(StubGateway(allow()), repo, ModeratedCommandRegistry()).process(
            submission.id, now=submission.next_attempt_at
        )
        current = await repo.get(submission.id)
        decision = await repo.latest_decision(submission.id)
        assert current is not None and current.status is SubmissionStatus.PENDING_REVIEW
        assert current.attempt_count == 1
        assert decision is not None and decision.decision is ModerationDecision.REVIEW
        assert decision.categories == ["PERSONAL_DATA"]


async def test_local_review_and_high_provider_block_becomes_hash_only_block(
    session_factory,
) -> None:
    async with session_factory() as session:
        repo = repository(session)
        submission = await repo.create_pending(
            ModerationCommand(
                owner_id=uuid4(),
                content_type=ContentType.LETTER,
                operation="CREATE_LETTER",
                text="contact me@example.com",
                payload={"content": "contact me@example.com"},
                idempotency_key="combined-block",
            )
        )
        await worker(StubGateway(block()), repo, ModeratedCommandRegistry()).process(
            submission.id, now=submission.next_attempt_at
        )
        current = await repo.get(submission.id)
        decision = await repo.latest_decision(submission.id)
        assert current is not None and current.status is SubmissionStatus.BLOCKED
        assert current.ciphertext is None and current.nonce is None
        assert decision is not None
        assert set(decision.categories) == {"HARASSMENT", "PERSONAL_DATA"}


async def test_malformed_custom_assessment_is_provider_failure_without_echo(
    session_factory,
) -> None:
    marker = "RAW-CUSTOM-ASSESSMENT-MARKER"

    class Malformed:
        def model_dump(self):
            raise RuntimeError(marker)

    async with session_factory() as session:
        repo = repository(session)
        submission = await pending(repo)
        await worker(StubGateway(Malformed()), repo, ModeratedCommandRegistry()).process(  # type: ignore[arg-type]
            submission.id, now=submission.next_attempt_at
        )
        current = await repo.get(submission.id)
        assert current is not None and current.attempt_count == 1
        assert await repo.latest_decision(submission.id) is None
        assert marker not in repr(current.__dict__)


async def test_registry_failures_are_fixed_and_drop_raw_exception_context() -> None:
    marker = "RAW-HANDLER-FAILURE-MARKER"
    registry = ModeratedCommandRegistry()

    async def broken(_command: dict[str, object], _key: str) -> UUID:
        raise RuntimeError(marker)

    registry.register("BROKEN", broken)
    for operation in ("MISSING", "BROKEN"):
        try:
            await registry.execute(operation, {"content": marker}, marker)
        except ModeratedCommandExecutionFailed as exc:
            rendered = str(exc) + repr(exc) + "".join(traceback.format_exception(exc))
            assert marker not in rendered
            assert exc.__cause__ is None and exc.__context__ is None
        else:
            raise AssertionError("registry failure was not normalized")
