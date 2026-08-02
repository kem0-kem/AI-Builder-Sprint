from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID

from sqlalchemy import and_, select

from app.auth.dependencies import Session
from app.core.errors import ApiError
from app.feeds.models import Comment, Feed, FeedCategory
from app.feeds.schemas import CommentCreate, CommentPatch, FeedCreate, FeedPatch
from app.letters.models import IdempotencyRecord


@dataclass(frozen=True, slots=True)
class FeedResult:
    resource_id: UUID
    feed: Feed


@dataclass(frozen=True, slots=True)
class CommentResult:
    resource_id: UUID
    comment: Comment


class FeedCommandHandler:
    def __init__(self, session: Session) -> None:
        self._session = session

    async def create_feed(
        self, owner_id: UUID, payload: dict[str, object], key: str
    ) -> FeedResult:
        prior_id = await self._prior_id(owner_id, key, "CREATE_FEED")
        if prior_id is not None:
            feed = await self._session.get(Feed, prior_id)
            if feed is None or feed.author_id != owner_id:
                raise ApiError("RESOURCE_CONFLICT", "저장된 피드 결과가 없습니다.", 409)
            return FeedResult(feed.id, feed)
        request = FeedCreate.model_validate(payload)
        if await self._session.get(FeedCategory, request.category_id) is None:
            raise ApiError("VALIDATION_ERROR", "존재하지 않는 피드 카테고리입니다.", 400)
        feed = Feed(author_id=owner_id, **request.model_dump())
        self._session.add(feed)
        await self._session.flush()
        self._remember(owner_id, key, "CREATE_FEED", feed.id)
        await self._session.commit()
        return FeedResult(feed.id, feed)

    async def patch_feed(
        self, owner_id: UUID, payload: dict[str, object], _key: str
    ) -> FeedResult:
        feed_id = _required_uuid(payload, "feedId")
        feed = await self._owned_feed(owner_id, feed_id)
        request = FeedPatch.model_validate(payload)
        changes = request.model_dump(exclude_unset=True)
        category_id = changes.get("category_id")
        if category_id is not None and await self._session.get(FeedCategory, category_id) is None:
            raise ApiError("VALIDATION_ERROR", "존재하지 않는 피드 카테고리입니다.", 400)
        for field, value in changes.items():
            setattr(feed, field, value)
        feed.updated_at = datetime.now(UTC)
        await self._session.commit()
        return FeedResult(feed.id, feed)

    async def create_comment(
        self, owner_id: UUID, payload: dict[str, object], key: str
    ) -> CommentResult:
        prior_id = await self._prior_id(owner_id, key, "CREATE_COMMENT")
        if prior_id is not None:
            comment = await self._session.get(Comment, prior_id)
            if comment is None or comment.author_id != owner_id:
                raise ApiError("RESOURCE_CONFLICT", "저장된 댓글 결과가 없습니다.", 409)
            return CommentResult(comment.id, comment)
        feed_id = _required_uuid(payload, "feedId")
        await self._visible_feed(feed_id)
        request = CommentCreate.model_validate(payload)
        if request.parent_comment_id is not None:
            parent = await self._visible_comment(request.parent_comment_id)
            if parent.feed_id != feed_id or parent.parent_comment_id is not None:
                raise ApiError(
                    "VALIDATION_ERROR", "답글은 최상위 댓글에만 작성할 수 있습니다.", 400
                )
        comment = Comment(
            feed_id=feed_id,
            author_id=owner_id,
            parent_comment_id=request.parent_comment_id,
            content=request.content,
        )
        self._session.add(comment)
        await self._session.flush()
        self._remember(owner_id, key, "CREATE_COMMENT", comment.id)
        await self._session.commit()
        return CommentResult(comment.id, comment)

    async def patch_comment(
        self, owner_id: UUID, payload: dict[str, object], _key: str
    ) -> CommentResult:
        comment_id = _required_uuid(payload, "commentId")
        comment = await self._visible_comment(comment_id)
        if comment.author_id != owner_id:
            raise ApiError("RESOURCE_FORBIDDEN", "본인의 댓글만 수정할 수 있습니다.", 403)
        request = CommentPatch.model_validate(payload)
        comment.content = request.content
        await self._session.commit()
        return CommentResult(comment.id, comment)

    async def _owned_feed(self, owner_id: UUID, feed_id: UUID) -> Feed:
        feed = await self._visible_feed(feed_id)
        if feed.author_id != owner_id:
            raise ApiError("RESOURCE_FORBIDDEN", "본인의 피드만 수정할 수 있습니다.", 403)
        return feed

    async def _visible_feed(self, feed_id: UUID) -> Feed:
        feed = await self._session.get(Feed, feed_id)
        if feed is None or feed.deleted_at is not None or feed.moderation_status == "HIDDEN":
            raise ApiError("RESOURCE_NOT_FOUND", "피드를 찾을 수 없습니다.", 404)
        return feed

    async def _visible_comment(self, comment_id: UUID) -> Comment:
        comment = await self._session.get(Comment, comment_id)
        if comment is None or comment.deleted_at is not None:
            raise ApiError("RESOURCE_NOT_FOUND", "댓글을 찾을 수 없습니다.", 404)
        return comment

    async def _prior_id(self, owner_id: UUID, key: str, operation: str) -> UUID | None:
        prior = await self._session.scalar(
            select(IdempotencyRecord).where(
                and_(IdempotencyRecord.user_id == owner_id, IdempotencyRecord.key == key)
            )
        )
        if prior is None:
            return None
        if prior.response.get("operation") != operation:
            raise ApiError("RESOURCE_CONFLICT", "멱등키가 다른 작업에 사용되었습니다.", 409)
        resource_id = prior.response.get("resourceId")
        if not isinstance(resource_id, str):
            raise ApiError("RESOURCE_CONFLICT", "저장된 작업 결과가 올바르지 않습니다.", 409)
        return UUID(resource_id)

    def _remember(self, owner_id: UUID, key: str, operation: str, resource_id: UUID) -> None:
        self._session.add(
            IdempotencyRecord(
                user_id=owner_id,
                key=key,
                response={"operation": operation, "resourceId": str(resource_id)},
            )
        )


def _required_uuid(payload: dict[str, object], key: str) -> UUID:
    value = payload.get(key)
    if not isinstance(value, str):
        raise ApiError("VALIDATION_ERROR", f"{key} 값이 올바르지 않습니다.", 400)
    try:
        return UUID(value)
    except ValueError:
        raise ApiError("VALIDATION_ERROR", f"{key} 값이 올바르지 않습니다.", 400) from None
