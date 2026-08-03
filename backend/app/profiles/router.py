from uuid import UUID

from fastapi import APIRouter
from sqlalchemy import delete, func, or_, select

from app.auth.dependencies import CurrentUser, Session
from app.auth.models import User
from app.common.responses import success
from app.core.errors import ApiError
from app.letters.models import Letter
from app.matching.models import MatchHistory
from app.profiles.models import Interest, UserInterest
from app.profiles.schemas import InterestReplace, ProfilePatch
from app.regions.catalog import (
    DISTRICTS,
    PROVINCES,
    SUB_DISTRICTS,
    find,
    valid_region,
)

router = APIRouter(tags=["profiles"])

DEFAULT_INTERESTS = [
    ("walking", "산책"),
    ("reading", "독서"),
    ("music", "음악"),
    ("cooking", "요리"),
    ("travel", "여행"),
    ("exercise", "운동"),
]


async def ensure_interests(session: Session) -> None:
    if await session.scalar(select(Interest.id).limit(1)) is None:
        session.add_all([Interest(slug=slug, name=name) for slug, name in DEFAULT_INTERESTS])
        await session.commit()


async def profile_statistics(session: Session, user_id: UUID) -> dict[str, int]:
    sent_letters = (
        select(func.count())
        .select_from(Letter)
        .where(Letter.sender_id == user_id, Letter.recipient_id.is_not(None))
        .scalar_subquery()
    )
    received_letters = (
        select(func.count())
        .select_from(Letter)
        .where(Letter.recipient_id == user_id)
        .scalar_subquery()
    )
    match_count = (
        select(func.count())
        .select_from(MatchHistory)
        .where(
            or_(
                MatchHistory.user_a_id == user_id,
                MatchHistory.user_b_id == user_id,
            )
        )
        .scalar_subquery()
    )
    row = (
        await session.execute(
            select(
                sent_letters.label("sent_letters"),
                received_letters.label("received_letters"),
                match_count.label("match_count"),
            )
        )
    ).one()
    return {
        "sentLetters": int(row.sent_letters or 0),
        "receivedLetters": int(row.received_letters or 0),
        "matchCount": int(row.match_count or 0),
    }


async def serialize_profile(session: Session, user: User) -> dict[str, object]:
    interests = (
        await session.execute(
            select(Interest)
            .join(UserInterest, UserInterest.interest_id == Interest.id)
            .where(UserInterest.user_id == user.id)
            .order_by(Interest.name)
        )
    ).scalars()
    region = None
    if user.province_code and user.district_code:
        region = {
            "province": find(PROVINCES, user.province_code),
            "district": find(DISTRICTS.get(user.province_code, []), user.district_code),
            "subDistrict": find(SUB_DISTRICTS.get(user.district_code, []), user.sub_district_code),
        }
    statistics = await profile_statistics(session, user.id)
    return {
        "id": str(user.id),
        "nickname": user.nickname,
        "bio": user.bio,
        "interests": [{"id": str(item.id), "name": item.name} for item in interests],
        "region": region,
        "statistics": statistics,
    }


@router.get("/users/me")
async def get_me(user: CurrentUser, session: Session) -> dict[str, object]:
    return success(await serialize_profile(session, user))


@router.patch("/users/me")
async def patch_me(request: ProfilePatch, user: CurrentUser, session: Session) -> dict[str, object]:
    if "nickname" in request.model_fields_set:
        user.nickname = request.nickname or user.nickname
    if "bio" in request.model_fields_set:
        user.bio = request.bio
    if request.region is not None:
        region = request.region
        if not valid_region(region.province_code, region.district_code, region.sub_district_code):
            raise ApiError("VALIDATION_ERROR", "유효하지 않은 지역 조합입니다.", 400)
        user.province_code = region.province_code
        user.district_code = region.district_code
        user.sub_district_code = region.sub_district_code
    await session.commit()
    return success(await serialize_profile(session, user))


@router.get("/interests")
async def list_interests(session: Session) -> dict[str, object]:
    await ensure_interests(session)
    items = (await session.execute(select(Interest).order_by(Interest.name))).scalars()
    return success([{"id": str(item.id), "name": item.name} for item in items])


@router.put("/users/me/interests")
async def replace_interests(
    request: InterestReplace, user: CurrentUser, session: Session
) -> dict[str, object]:
    if len(set(request.interest_ids)) != len(request.interest_ids):
        raise ApiError("VALIDATION_ERROR", "관심사는 중복될 수 없습니다.", 400)
    found = set(
        (
            await session.execute(select(Interest.id).where(Interest.id.in_(request.interest_ids)))
        ).scalars()
    )
    if found != set(request.interest_ids):
        raise ApiError("VALIDATION_ERROR", "존재하지 않는 관심사가 포함되어 있습니다.", 400)
    await session.execute(delete(UserInterest).where(UserInterest.user_id == user.id))
    session.add_all(
        [
            UserInterest(user_id=user.id, interest_id=interest_id)
            for interest_id in request.interest_ids
        ]
    )
    await session.commit()
    return success(await serialize_profile(session, user))
