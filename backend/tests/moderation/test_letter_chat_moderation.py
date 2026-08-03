from uuid import uuid4

from httpx import AsyncClient
from sqlalchemy import func, select

from app.auth.models import User
from app.chat.models import ChatMessage
from app.events.outbox import OutboxEvent
from app.letters.models import Letter
from app.moderation.dependencies import get_moderation_orchestrator
from app.moderation.models import SubmissionStatus
from app.moderation.router import get_command_registry
from app.moderation.schemas import ModerationCategory
from app.moderation.service import ModerationOutcome
from tests.letters.test_letter_delivery import register


class StubModeration:
    def __init__(self, outcome: ModerationOutcome) -> None:
        self.outcome = outcome
        self.commands = []

    async def evaluate(self, command):
        self.commands.append(command)
        return self.outcome


def override_moderation(client: AsyncClient, outcome: ModerationOutcome) -> StubModeration:
    stub = StubModeration(outcome)
    client._transport.app.dependency_overrides[get_moderation_orchestrator] = lambda: stub
    return stub


async def counts(session_factory) -> tuple[int, int, int]:
    async with session_factory() as session:
        return (
            await session.scalar(select(func.count()).select_from(Letter)) or 0,
            await session.scalar(select(func.count()).select_from(ChatMessage)) or 0,
            await session.scalar(select(func.count()).select_from(OutboxEvent)) or 0,
        )


async def test_pending_letter_creates_no_domain_rows(client, session_factory) -> None:
    alice = await register(client, "pending-letter@example.com", "Alice")
    submission_id = uuid4()
    stub = override_moderation(client, ModerationOutcome.pending(submission_id))

    response = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": "pending-letter-01"},
        json={"content": "needs review", "match": True},
    )

    assert response.status_code == 202
    assert response.json()["data"] == {
        "moderationStatus": SubmissionStatus.PENDING_REVIEW.value,
        "submissionId": str(submission_id),
    }
    assert await counts(session_factory) == (0, 0, 0)
    assert stub.commands[0].operation == "CREATE_LETTER"


async def test_blocked_letter_creates_no_domain_rows(client, session_factory) -> None:
    alice = await register(client, "blocked-letter@example.com", "Alice")
    override_moderation(
        client, ModerationOutcome.blocked({ModerationCategory.HARASSMENT})
    )

    response = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": "blocked-letter-01"},
        json={"content": "blocked", "match": False},
    )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "CONTENT_POLICY_VIOLATION"
    assert await counts(session_factory) == (0, 0, 0)


async def test_pending_chat_message_creates_no_message_or_outbox(
    client, session_factory
) -> None:
    alice = await register(client, "pending-chat-a@example.com", "Alice")
    bob = await register(client, "pending-chat-b@example.com", "Bob")
    delivery = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": "chat-room-letter-01"},
        json={"content": "hello", "match": True},
    )
    room_id = delivery.json()["data"]["chatRoom"]["id"]
    baseline = await counts(session_factory)
    submission_id = uuid4()
    stub = override_moderation(client, ModerationOutcome.pending(submission_id))

    response = await client.post(
        f"/api/v1/chat-rooms/{room_id}/messages",
        headers=bob,
        json={"clientMessageId": str(uuid4()), "content": "needs review"},
    )

    assert response.status_code == 202
    assert response.json()["data"]["submissionId"] == str(submission_id)
    assert await counts(session_factory) == baseline
    assert stub.commands[0].operation == "CREATE_CHAT_MESSAGE"


async def test_blocked_chat_message_creates_no_message_or_outbox(
    client, session_factory
) -> None:
    alice = await register(client, "blocked-chat-a@example.com", "Alice")
    bob = await register(client, "blocked-chat-b@example.com", "Bob")
    delivery = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": "chat-room-letter-02"},
        json={"content": "hello", "match": True},
    )
    room_id = delivery.json()["data"]["chatRoom"]["id"]
    baseline = await counts(session_factory)
    override_moderation(
        client, ModerationOutcome.blocked({ModerationCategory.HARASSMENT})
    )

    response = await client.post(
        f"/api/v1/chat-rooms/{room_id}/messages",
        headers=bob,
        json={"clientMessageId": str(uuid4()), "content": "blocked"},
    )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "CONTENT_POLICY_VIOLATION"
    assert await counts(session_factory) == baseline


async def test_registered_letter_replay_uses_trusted_owner_and_is_idempotent(
    session_factory,
) -> None:
    owner_id = uuid4()
    async with session_factory() as session:
        session.add(
            User(
                id=owner_id,
                email="replay-owner@example.com",
                password_hash="unused",
                nickname="Replay",
            )
        )
        await session.commit()
        registry = get_command_registry(session)
        payload = {"content": "delayed letter", "match": False}

        first = await registry.execute(
            "CREATE_LETTER", payload, "replay-letter-01", owner_id=owner_id
        )
        duplicate = await registry.execute(
            "CREATE_LETTER", payload, "replay-letter-01", owner_id=owner_id
        )

        assert first == duplicate
        assert await session.scalar(select(func.count()).select_from(Letter)) == 1
