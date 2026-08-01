from datetime import UTC, datetime, timedelta
from uuid import UUID

from fastapi import APIRouter, Depends, Query, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.auth.dependencies import CurrentUserId, Session
from app.auth.models import RefreshToken, User
from app.auth.schemas import LoginRequest, RefreshRequest, SignupRequest, TokenPair
from app.auth.security import (
    create_access_token,
    hash_password,
    hash_refresh_token,
    new_refresh_token,
    verify_password,
)
from app.common.responses import success
from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.rate_limit import login_limiter

router = APIRouter(prefix="/auth", tags=["auth"])


async def issue_pair(session: Session, user_id: UUID) -> TokenPair:
    settings = get_settings()
    raw_refresh = new_refresh_token()
    expires_at = datetime.now(UTC) + timedelta(seconds=settings.refresh_token_ttl_seconds)
    session.add(
        RefreshToken(
            user_id=user_id,
            token_hash=hash_refresh_token(raw_refresh),
            expires_at=expires_at,
        )
    )
    await session.flush()
    return TokenPair(
        access_token=create_access_token(user_id),
        refresh_token=raw_refresh,
        expires_in=settings.access_token_ttl_seconds,
    )


@router.post("/signup", status_code=status.HTTP_201_CREATED)
async def signup(request: SignupRequest, session: Session) -> dict[str, object]:
    user = User(
        email=request.email.lower(),
        password_hash=hash_password(request.password),
        nickname=request.nickname,
    )
    session.add(user)
    try:
        await session.flush()
    except IntegrityError as exc:
        await session.rollback()
        raise ApiError("EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다.", 409) from exc
    pair = await issue_pair(session, user.id)
    await session.commit()
    return success({"userId": str(user.id), **pair.model_dump(by_alias=True)})


@router.post("/login", dependencies=[Depends(login_limiter)])
async def login(request: LoginRequest, session: Session) -> dict[str, object]:
    user = await session.scalar(select(User).where(User.email == request.email.lower()))
    if user is None or not verify_password(request.password, user.password_hash):
        raise ApiError("INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.", 401)
    pair = await issue_pair(session, user.id)
    await session.commit()
    return success(pair.model_dump(by_alias=True))


@router.post("/token/refresh")
async def refresh(request: RefreshRequest, session: Session) -> dict[str, object]:
    now = datetime.now(UTC)
    token = await session.scalar(
        select(RefreshToken).where(
            RefreshToken.token_hash == hash_refresh_token(request.refresh_token)
        )
    )
    expires_at = token.expires_at.replace(tzinfo=UTC) if token else now
    if token is None or token.revoked_at is not None or expires_at <= now:
        raise ApiError("AUTHENTICATION_REQUIRED", "유효하지 않은 갱신 토큰입니다.", 401)
    token.revoked_at = now
    pair = await issue_pair(session, token.user_id)
    await session.commit()
    return success(pair.model_dump(by_alias=True))


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(request: RefreshRequest, _: CurrentUserId, session: Session) -> None:
    token = await session.scalar(
        select(RefreshToken).where(
            RefreshToken.token_hash == hash_refresh_token(request.refresh_token)
        )
    )
    if token is not None and token.revoked_at is None:
        token.revoked_at = datetime.now(UTC)
        await session.commit()


@router.get("/email-availability")
async def email_availability(
    session: Session, email: str = Query(min_length=3, max_length=320)
) -> dict[str, object]:
    exists = await session.scalar(select(User.id).where(User.email == email.lower()))
    return success({"available": exists is None})
