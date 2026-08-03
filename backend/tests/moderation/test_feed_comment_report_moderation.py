from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

from sqlalchemy import func, select

from app.auth.models import User
from app.auth.security import decode_access_token
from app.feeds.models import Comment, Feed, FeedCategory
from app.moderation.dependencies import get_moderation_orchestrator
from app.moderation.router import get_command_registry
from app.moderation.schemas import ModerationCategory
from app.moderation.service import ModerationOutcome
from app.reports.models import AnalysisSnapshot, ReflectionReport
from app.reports.router import source_hash
from tests.letters.test_letter_delivery import register


class StubModeration:
    def __init__(self, outcome: ModerationOutcome) -> None:
        self.outcome = outcome
        self.commands = []

    async def evaluate(self, command):
        self.commands.append(command)
        return self.outcome


def set_moderation(client, outcome: ModerationOutcome) -> StubModeration:
    stub = StubModeration(outcome)
    client._transport.app.dependency_overrides[get_moderation_orchestrator] = lambda: stub
    return stub


def owner_id(headers: dict[str, str]) -> UUID:
    return decode_access_token(headers["Authorization"].removeprefix("Bearer "))


async def category_id(client) -> str:
    response = await client.get("/api/v1/feed-categories")
    return response.json()["data"][0]["id"]


async def count_rows(session_factory, model) -> int:
    async with session_factory() as session:
        return await session.scalar(select(func.count()).select_from(model)) or 0


async def create_feed(client, headers, category: str, content: str = "published") -> str:
    response = await client.post(
        "/api/v1/feeds",
        headers=headers,
        json={"categoryId": category, "title": "title", "content": content},
    )
    assert response.status_code == 201
    return response.json()["data"]["id"]


async def test_pending_and_blocked_feed_create_persist_nothing(
    client, session_factory
) -> None:
    author = await register(client, "moderated-feed@example.com", "Author")
    category = await category_id(client)
    for outcome, expected in (
        (ModerationOutcome.pending(uuid4()), 202),
        (ModerationOutcome.blocked({ModerationCategory.HARASSMENT}), 422),
    ):
        stub = set_moderation(client, outcome)
        response = await client.post(
            "/api/v1/feeds",
            headers=author,
            json={"categoryId": category, "title": "unsafe", "content": "content"},
        )
        assert response.status_code == expected
        assert await count_rows(session_factory, Feed) == 0
        assert stub.commands[0].operation == "CREATE_FEED"


async def test_pending_feed_edit_keeps_published_version(client) -> None:
    author = await register(client, "pending-edit@example.com", "Author")
    category = await category_id(client)
    feed_id = await create_feed(client, author, category)
    set_moderation(client, ModerationOutcome.pending(uuid4()))

    response = await client.patch(
        f"/api/v1/feeds/{feed_id}", headers=author, json={"content": "pending edit"}
    )
    visible = await client.get(f"/api/v1/feeds/{feed_id}", headers=author)

    assert response.status_code == 202
    assert visible.json()["data"]["content"] == "published"


async def test_pending_and_blocked_comment_create_persist_nothing(
    client, session_factory
) -> None:
    author = await register(client, "moderated-comment@example.com", "Author")
    category = await category_id(client)
    feed_id = await create_feed(client, author, category)
    for outcome, expected in (
        (ModerationOutcome.pending(uuid4()), 202),
        (ModerationOutcome.blocked({ModerationCategory.HARASSMENT}), 422),
    ):
        stub = set_moderation(client, outcome)
        response = await client.post(
            f"/api/v1/feeds/{feed_id}/comments",
            headers=author,
            json={"content": "moderated comment"},
        )
        assert response.status_code == expected
        assert await count_rows(session_factory, Comment) == 0
        assert stub.commands[0].operation == "CREATE_COMMENT"


async def test_pending_comment_edit_keeps_published_version(client) -> None:
    author = await register(client, "comment-edit@example.com", "Author")
    category = await category_id(client)
    feed_id = await create_feed(client, author, category)
    created = await client.post(
        f"/api/v1/feeds/{feed_id}/comments",
        headers=author,
        json={"content": "published comment"},
    )
    comment_id = created.json()["data"]["id"]
    set_moderation(client, ModerationOutcome.pending(uuid4()))

    response = await client.patch(
        f"/api/v1/comments/{comment_id}",
        headers=author,
        json={"content": "pending comment"},
    )
    visible = await client.get(f"/api/v1/feeds/{feed_id}/comments", headers=author)

    assert response.status_code == 202
    assert visible.json()["data"][0]["content"] == "published comment"


async def test_pending_and_blocked_reflection_report_persist_nothing(
    client, session_factory
) -> None:
    author = await register(client, "moderated-report@example.com", "Author")
    content = "reflection content"
    async with session_factory() as session:
        snapshot = AnalysisSnapshot(
            owner_id=owner_id(author),
            source_hash=source_hash(content),
            summary="summary",
            feedback=[],
            expires_at=datetime.now(UTC) + timedelta(minutes=30),
        )
        session.add(snapshot)
        await session.commit()
        analysis_id = str(snapshot.id)

    for outcome, expected in (
        (ModerationOutcome.pending(uuid4()), 202),
        (ModerationOutcome.blocked({ModerationCategory.HARASSMENT}), 422),
    ):
        stub = set_moderation(client, outcome)
        response = await client.post(
            "/api/v1/reports",
            headers=author,
            json={"analysisId": analysis_id, "content": content},
        )
        assert response.status_code == expected
        assert await count_rows(session_factory, ReflectionReport) == 0
        assert stub.commands[0].operation == "CREATE_REPORT"


async def test_registered_social_replays_revalidate_and_apply_commands(
    session_factory,
) -> None:
    user_id = uuid4()
    category = FeedCategory(slug="replay", name="Replay")
    content = "delayed reflection"
    async with session_factory() as session:
        session.add(
            User(
                id=user_id,
                email="social-replay@example.com",
                password_hash="unused",
                nickname="Replay",
            )
        )
        session.add(category)
        await session.flush()
        snapshot = AnalysisSnapshot(
            owner_id=user_id,
            source_hash=source_hash(content),
            summary="summary",
            feedback=[],
            expires_at=datetime.now(UTC) + timedelta(minutes=30),
        )
        session.add(snapshot)
        await session.commit()
        registry = get_command_registry(session)

        feed_id = await registry.execute(
            "CREATE_FEED",
            {"categoryId": str(category.id), "title": "title", "content": "draft"},
            "replay-feed",
            owner_id=user_id,
        )
        duplicate_feed_id = await registry.execute(
            "CREATE_FEED",
            {"categoryId": str(category.id), "title": "title", "content": "draft"},
            "replay-feed",
            owner_id=user_id,
        )
        await registry.execute(
            "PATCH_FEED",
            {"feedId": str(feed_id), "content": "approved feed"},
            "replay-feed-patch",
            owner_id=user_id,
        )
        comment_id = await registry.execute(
            "CREATE_COMMENT",
            {"feedId": str(feed_id), "content": "draft comment"},
            "replay-comment",
            owner_id=user_id,
        )
        await registry.execute(
            "PATCH_COMMENT",
            {"commentId": str(comment_id), "content": "approved comment"},
            "replay-comment-patch",
            owner_id=user_id,
        )
        report_id = await registry.execute(
            "CREATE_REPORT",
            {"analysisId": str(snapshot.id), "content": content},
            "replay-report",
            owner_id=user_id,
        )
        duplicate_report_id = await registry.execute(
            "CREATE_REPORT",
            {"analysisId": str(snapshot.id), "content": content},
            "replay-report",
            owner_id=user_id,
        )

        feed = await session.get(Feed, feed_id)
        comment = await session.get(Comment, comment_id)
        assert duplicate_feed_id == feed_id
        assert duplicate_report_id == report_id
        assert feed is not None and feed.content == "approved feed"
        assert comment is not None and comment.content == "approved comment"
        assert await session.scalar(select(func.count()).select_from(Feed)) == 1
        assert await session.scalar(select(func.count()).select_from(Comment)) == 1
        assert await session.scalar(select(func.count()).select_from(ReflectionReport)) == 1
