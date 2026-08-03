from datetime import UTC, datetime
from typing import Annotated
from uuid import UUID, uuid4

from fastapi import APIRouter, Query, Response, status
from fastapi.responses import JSONResponse
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError

from app.auth.dependencies import CurrentUserId, Session
from app.chat.schemas import ChatRoomSuccessResponse
from app.chat.service import get_or_create_direct_room
from app.common.responses import page, success
from app.core.errors import ApiError
from app.feeds.models import Comment, Feed, FeedCategory, FeedLike, ModerationReport
from app.feeds.schemas import (
    CommentCreate,
    CommentPatch,
    FeedCreate,
    FeedPatch,
    ReportCreate,
)
from app.feeds.service import FeedCommandHandler
from app.moderation.dependencies import MODERATION_RESPONSES, Moderation, moderation_response
from app.moderation.repository import ModerationCommand
from app.moderation.schemas import ContentType

router = APIRouter(tags=["feeds"])

DEFAULT_CATEGORIES = [("daily", "일상"), ("growth", "성장"), ("comfort", "위로")]


async def ensure_categories(session: Session) -> None:
    if await session.scalar(select(FeedCategory.id).limit(1)) is None:
        session.add_all([FeedCategory(slug=slug, name=name) for slug, name in DEFAULT_CATEGORIES])
        await session.commit()


async def require_feed(session: Session, feed_id: UUID) -> Feed:
    feed = await session.get(Feed, feed_id)
    if feed is None or feed.deleted_at is not None or feed.moderation_status == "HIDDEN":
        raise ApiError("RESOURCE_NOT_FOUND", "피드를 찾을 수 없습니다.", 404)
    return feed


async def require_comment(session: Session, comment_id: UUID) -> Comment:
    comment = await session.get(Comment, comment_id)
    if comment is None or comment.deleted_at is not None:
        raise ApiError("RESOURCE_NOT_FOUND", "댓글을 찾을 수 없습니다.", 404)
    return comment


async def feed_view(session: Session, feed: Feed, viewer_id: UUID) -> dict[str, object]:
    like_count = await session.scalar(
        select(func.count()).select_from(FeedLike).where(FeedLike.feed_id == feed.id)
    )
    comment_count = await session.scalar(
        select(func.count())
        .select_from(Comment)
        .where(Comment.feed_id == feed.id, Comment.deleted_at.is_(None))
    )
    liked = await session.get(FeedLike, (viewer_id, feed.id)) is not None
    return {
        "id": str(feed.id),
        "categoryId": str(feed.category_id),
        "title": feed.title,
        "content": feed.content,
        "isMine": feed.author_id == viewer_id,
        "liked": liked,
        "likeCount": like_count or 0,
        "commentCount": comment_count or 0,
        "createdAt": feed.created_at.isoformat(),
        "updatedAt": feed.updated_at.isoformat(),
    }


def comment_view(comment: Comment, viewer_id: UUID) -> dict[str, object]:
    return {
        "id": str(comment.id),
        "feedId": str(comment.feed_id),
        "parentCommentId": str(comment.parent_comment_id) if comment.parent_comment_id else None,
        "content": comment.content,
        "isMine": comment.author_id == viewer_id,
        "createdAt": comment.created_at.isoformat(),
    }


@router.get("/feed-categories")
async def list_categories(session: Session) -> dict[str, object]:
    await ensure_categories(session)
    items = (await session.execute(select(FeedCategory).order_by(FeedCategory.name))).scalars()
    return success([{"id": str(item.id), "name": item.name} for item in items])


@router.get("/feeds")
async def list_feeds(
    user_id: CurrentUserId,
    session: Session,
    scope: str = Query("all", pattern="^(all|mine)$"),
    category_id: Annotated[UUID | None, Query(alias="categoryId")] = None,
    cursor: UUID | None = None,
    limit: int = Query(20, ge=1, le=100),
) -> dict[str, object]:
    statement = select(Feed).where(Feed.deleted_at.is_(None), Feed.moderation_status != "HIDDEN")
    if scope == "mine":
        statement = statement.where(Feed.author_id == user_id)
    if category_id is not None:
        statement = statement.where(Feed.category_id == category_id)
    if cursor is not None:
        pivot = await session.get(Feed, cursor)
        if pivot is None:
            raise ApiError("VALIDATION_ERROR", "유효하지 않은 커서입니다.", 400)
        statement = statement.where(Feed.created_at < pivot.created_at)
    items = list(
        (
            await session.execute(statement.order_by(Feed.created_at.desc()).limit(limit + 1))
        ).scalars()
    )
    next_cursor = str(items[limit - 1].id) if len(items) > limit else None
    return page(
        [await feed_view(session, item, user_id) for item in items[:limit]],
        next_cursor=next_cursor,
    )


@router.post(
    "/feeds",
    status_code=status.HTTP_201_CREATED,
    response_model=None,
    responses=MODERATION_RESPONSES,
)
async def create_feed(
    request: FeedCreate,
    user_id: CurrentUserId,
    session: Session,
    moderation: Moderation,
) -> dict[str, object] | JSONResponse:
    if await session.get(FeedCategory, request.category_id) is None:
        raise ApiError("VALIDATION_ERROR", "존재하지 않는 피드 카테고리입니다.", 400)
    payload = request.model_dump(by_alias=True, mode="json")
    key = str(uuid4())
    if moderation is not None:
        outcome = await moderation.evaluate(
            ModerationCommand(
                owner_id=user_id,
                content_type=ContentType.FEED,
                operation="CREATE_FEED",
                text=f"{request.title}\n{request.content}",
                payload=payload,
                idempotency_key=key,
            )
        )
        response = await moderation_response(outcome, session)
        if response is not None:
            return response
    result = await FeedCommandHandler(session).create_feed(user_id, payload, key)
    return success(await feed_view(session, result.feed, user_id))


@router.get("/feeds/{feed_id}")
async def get_feed(feed_id: UUID, user_id: CurrentUserId, session: Session) -> dict[str, object]:
    return success(await feed_view(session, await require_feed(session, feed_id), user_id))


@router.patch("/feeds/{feed_id}", response_model=None, responses=MODERATION_RESPONSES)
async def patch_feed(
    feed_id: UUID,
    request: FeedPatch,
    user_id: CurrentUserId,
    session: Session,
    moderation: Moderation,
) -> dict[str, object] | JSONResponse:
    feed = await require_feed(session, feed_id)
    if feed.author_id != user_id:
        raise ApiError("RESOURCE_FORBIDDEN", "본인의 피드만 수정할 수 있습니다.", 403)
    changes = request.model_dump(exclude_unset=True)
    if "category_id" in changes and await session.get(FeedCategory, changes["category_id"]) is None:
        raise ApiError("VALIDATION_ERROR", "존재하지 않는 피드 카테고리입니다.", 400)
    payload = request.model_dump(by_alias=True, mode="json", exclude_unset=True)
    payload["feedId"] = str(feed_id)
    moderated_text = "\n".join(
        value for value in (request.title, request.content) if value is not None
    )
    key = str(uuid4())
    if moderation is not None and moderated_text:
        outcome = await moderation.evaluate(
            ModerationCommand(
                owner_id=user_id,
                content_type=ContentType.FEED,
                operation="PATCH_FEED",
                text=moderated_text,
                payload=payload,
                idempotency_key=key,
            )
        )
        response = await moderation_response(outcome, session)
        if response is not None:
            return response
    result = await FeedCommandHandler(session).patch_feed(user_id, payload, key)
    return success(await feed_view(session, result.feed, user_id))


@router.delete("/feeds/{feed_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_feed(feed_id: UUID, user_id: CurrentUserId, session: Session) -> Response:
    feed = await require_feed(session, feed_id)
    if feed.author_id != user_id:
        raise ApiError("RESOURCE_FORBIDDEN", "본인의 피드만 삭제할 수 있습니다.", 403)
    feed.deleted_at = datetime.now(UTC)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.put("/feeds/{feed_id}/like")
async def like_feed(feed_id: UUID, user_id: CurrentUserId, session: Session) -> dict[str, object]:
    await require_feed(session, feed_id)
    if await session.get(FeedLike, (user_id, feed_id)) is None:
        session.add(FeedLike(user_id=user_id, feed_id=feed_id))
        await session.commit()
    return success({"liked": True})


@router.delete("/feeds/{feed_id}/like")
async def unlike_feed(feed_id: UUID, user_id: CurrentUserId, session: Session) -> dict[str, object]:
    await require_feed(session, feed_id)
    await session.execute(
        delete(FeedLike).where(FeedLike.user_id == user_id, FeedLike.feed_id == feed_id)
    )
    await session.commit()
    return success({"liked": False})


async def report_target(
    session: Session, reporter_id: UUID, target_type: str, target_id: UUID, reason: str
) -> None:
    session.add(
        ModerationReport(
            reporter_id=reporter_id,
            target_type=target_type,
            target_id=target_id,
            reason=reason,
        )
    )
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()


@router.post("/feeds/{feed_id}/reports", status_code=status.HTTP_201_CREATED)
async def report_feed(
    feed_id: UUID,
    request: ReportCreate,
    user_id: CurrentUserId,
    session: Session,
) -> dict[str, object]:
    feed = await require_feed(session, feed_id)
    await report_target(session, user_id, "FEED", feed_id, request.reason)
    feed.moderation_status = "UNDER_REVIEW"
    await session.commit()
    return success({"reported": True})


@router.get("/feeds/{feed_id}/comments")
async def list_comments(
    feed_id: UUID,
    user_id: CurrentUserId,
    session: Session,
    limit: int = Query(50, ge=1, le=100),
) -> dict[str, object]:
    await require_feed(session, feed_id)
    items = (
        await session.execute(
            select(Comment)
            .where(Comment.feed_id == feed_id, Comment.deleted_at.is_(None))
            .order_by(Comment.created_at)
            .limit(limit)
        )
    ).scalars()
    return page([comment_view(item, user_id) for item in items], next_cursor=None)


@router.post(
    "/feeds/{feed_id}/comments",
    status_code=status.HTTP_201_CREATED,
    response_model=None,
    responses=MODERATION_RESPONSES,
)
async def create_comment(
    feed_id: UUID,
    request: CommentCreate,
    user_id: CurrentUserId,
    session: Session,
    moderation: Moderation,
) -> dict[str, object] | JSONResponse:
    await require_feed(session, feed_id)
    if request.parent_comment_id is not None:
        parent = await require_comment(session, request.parent_comment_id)
        if parent.feed_id != feed_id or parent.parent_comment_id is not None:
            raise ApiError("VALIDATION_ERROR", "답글은 최상위 댓글에만 작성할 수 있습니다.", 400)
    payload = request.model_dump(by_alias=True, mode="json")
    payload["feedId"] = str(feed_id)
    key = str(uuid4())
    if moderation is not None:
        outcome = await moderation.evaluate(
            ModerationCommand(
                owner_id=user_id,
                content_type=ContentType.COMMENT,
                operation="CREATE_COMMENT",
                text=request.content,
                payload=payload,
                idempotency_key=key,
            )
        )
        response = await moderation_response(outcome, session)
        if response is not None:
            return response
    result = await FeedCommandHandler(session).create_comment(user_id, payload, key)
    return success(comment_view(result.comment, user_id))


@router.patch(
    "/comments/{comment_id}", response_model=None, responses=MODERATION_RESPONSES
)
async def patch_comment(
    comment_id: UUID,
    request: CommentPatch,
    user_id: CurrentUserId,
    session: Session,
    moderation: Moderation,
) -> dict[str, object] | JSONResponse:
    comment = await require_comment(session, comment_id)
    if comment.author_id != user_id:
        raise ApiError("RESOURCE_FORBIDDEN", "본인의 댓글만 수정할 수 있습니다.", 403)
    payload = request.model_dump(by_alias=True, mode="json")
    payload["commentId"] = str(comment_id)
    key = str(uuid4())
    if moderation is not None:
        outcome = await moderation.evaluate(
            ModerationCommand(
                owner_id=user_id,
                content_type=ContentType.COMMENT,
                operation="PATCH_COMMENT",
                text=request.content,
                payload=payload,
                idempotency_key=key,
            )
        )
        response = await moderation_response(outcome, session)
        if response is not None:
            return response
    result = await FeedCommandHandler(session).patch_comment(user_id, payload, key)
    return success(comment_view(result.comment, user_id))


@router.delete("/comments/{comment_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_comment(comment_id: UUID, user_id: CurrentUserId, session: Session) -> Response:
    comment = await require_comment(session, comment_id)
    if comment.author_id != user_id:
        raise ApiError("RESOURCE_FORBIDDEN", "본인의 댓글만 삭제할 수 있습니다.", 403)
    comment.deleted_at = datetime.now(UTC)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post(
    "/comments/{comment_id}/chat-room",
    response_model=ChatRoomSuccessResponse,
)
async def create_comment_chat_room(
    comment_id: UUID,
    user_id: CurrentUserId,
    session: Session,
) -> dict[str, object]:
    comment = await require_comment(session, comment_id)
    if comment.author_id == user_id:
        raise ApiError(
            "VALIDATION_ERROR",
            "자기 댓글에서는 채팅을 시작할 수 없습니다.",
            400,
        )
    room = await get_or_create_direct_room(session, user_id, comment.author_id)
    await session.commit()
    return success({"id": str(room.id), "type": room.type, "name": room.name})


@router.post("/comments/{comment_id}/reports", status_code=status.HTTP_201_CREATED)
async def report_comment(
    comment_id: UUID,
    request: ReportCreate,
    user_id: CurrentUserId,
    session: Session,
) -> dict[str, object]:
    await require_comment(session, comment_id)
    await report_target(session, user_id, "COMMENT", comment_id, request.reason)
    return success({"reported": True})
