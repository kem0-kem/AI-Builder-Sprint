from typing import Annotated
from uuid import UUID

from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.models import User
from app.auth.security import decode_access_token
from app.core.errors import ApiError
from app.db.session import get_session

bearer = HTTPBearer(auto_error=False)


async def get_current_user_id(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
) -> UUID:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise ApiError("AUTHENTICATION_REQUIRED", "로그인이 필요합니다.", 401)
    return decode_access_token(credentials.credentials)


async def get_current_user(
    user_id: Annotated[UUID, Depends(get_current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> User:
    user = await session.get(User, user_id)
    if user is None or not user.is_active:
        raise ApiError("AUTHENTICATION_REQUIRED", "로그인이 필요합니다.", 401)
    return user


CurrentUserId = Annotated[UUID, Depends(get_current_user_id)]
CurrentUser = Annotated[User, Depends(get_current_user)]
Session = Annotated[AsyncSession, Depends(get_session)]
