from datetime import UTC, datetime, timedelta
from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.auth.models import User
from app.matching.models import MatchHistory, MatchStrategy
from app.matching.profile_policy import ProfileMatchingPolicy
from app.profiles.models import Interest, UserInterest


def user(
    number: int,
    *,
    region: tuple[str | None, str | None, str | None] = ("11", "11440", "1144066000"),
) -> User:
    province, district, sub_district = region
    return User(
        id=UUID(int=number),
        email=f"user-{number}@example.com",
        password_hash="hashed",
        nickname=f"user-{number}",
        province_code=province,
        district_code=district,
        sub_district_code=sub_district,
        created_at=datetime(2026, 1, 1, tzinfo=UTC),
    )


def interest(number: int, slug: str) -> Interest:
    return Interest(id=UUID(int=number), slug=slug, name=slug)


def attach(user_id: UUID, *interests: Interest) -> list[UserInterest]:
    return [UserInterest(user_id=user_id, interest_id=item.id) for item in interests]


async def test_expands_from_empty_subdistrict_to_district(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        sender = user(100)
        same_province = user(200, region=("11", "11680", None))
        same_district = user(300, region=("11", "11440", "1144067000"))
        session.add_all([sender, same_province, same_district])
        await session.commit()

        selected = await ProfileMatchingPolicy().select(session, sender.id)

    assert selected is not None
    assert selected.user_id == same_district.id


async def test_narrower_region_stage_wins_before_interest_score(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        walking = interest(900, "walking")
        sender = user(100)
        same_subdistrict = user(200)
        same_district = user(300, region=("11", "11440", "1144067000"))
        session.add_all(
            [
                walking,
                sender,
                same_subdistrict,
                same_district,
                *attach(sender.id, walking),
                *attach(same_district.id, walking),
            ]
        )
        await session.commit()

        selected = await ProfileMatchingPolicy().select(session, sender.id)

    assert selected is not None
    assert selected.user_id == same_subdistrict.id
    assert selected.score == 0.0


async def test_expands_from_empty_district_to_province(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        sender = user(100, region=("11", "11440", None))
        same_province = user(200, region=("11", "11680", None))
        national = user(300, region=("26", "26110", None))
        session.add_all([sender, same_province, national])
        await session.commit()

        selected = await ProfileMatchingPolicy().select(session, sender.id)

    assert selected is not None
    assert selected.user_id == same_province.id


async def test_interest_score_precedes_match_load_within_stage(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        walking = interest(900, "walking")
        books = interest(901, "books")
        sender = user(100)
        one_interest = user(200)
        two_interests = user(300)
        recent_partner = user(400, region=("26", None, None))
        session.add_all(
            [
                walking,
                books,
                sender,
                one_interest,
                two_interests,
                recent_partner,
                *attach(sender.id, walking, books),
                *attach(one_interest.id, walking),
                *attach(two_interests.id, walking, books),
                MatchHistory.create(
                    two_interests.id,
                    recent_partner.id,
                    MatchStrategy.PROFILE,
                    created_at=datetime.now(UTC) - timedelta(days=1),
                ),
            ]
        )
        await session.commit()

        selected = await ProfileMatchingPolicy().select(session, sender.id)

    assert selected is not None
    assert selected.user_id == two_interests.id
    assert selected.score == 1.0


async def test_lower_recent_match_load_wins_equal_interest_score(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        walking = interest(900, "walking")
        sender = user(100)
        busy = user(200)
        quiet = user(300)
        partner_one = user(400, region=("26", None, None))
        partner_two = user(500, region=("27", None, None))
        now = datetime.now(UTC)
        session.add_all(
            [
                walking,
                sender,
                busy,
                quiet,
                partner_one,
                partner_two,
                *attach(sender.id, walking),
                *attach(busy.id, walking),
                *attach(quiet.id, walking),
                MatchHistory.create(
                    busy.id,
                    partner_one.id,
                    MatchStrategy.PROFILE,
                    created_at=now - timedelta(days=1),
                ),
                MatchHistory.create(
                    busy.id,
                    partner_two.id,
                    MatchStrategy.PROFILE,
                    created_at=now - timedelta(days=2),
                ),
            ]
        )
        await session.commit()

        selected = await ProfileMatchingPolicy().select(session, sender.id)

    assert selected is not None
    assert selected.user_id == quiet.id


async def test_older_last_match_wins_when_recent_load_is_equal(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        sender = user(100)
        older = user(200)
        newer = user(300)
        older_partner = user(400, region=("26", None, None))
        newer_partner = user(500, region=("27", None, None))
        now = datetime.now(UTC)
        session.add_all(
            [
                sender,
                older,
                newer,
                older_partner,
                newer_partner,
                MatchHistory.create(
                    older.id,
                    older_partner.id,
                    MatchStrategy.PROFILE,
                    created_at=now - timedelta(days=20),
                ),
                MatchHistory.create(
                    newer.id,
                    newer_partner.id,
                    MatchStrategy.PROFILE,
                    created_at=now - timedelta(days=2),
                ),
            ]
        )
        await session.commit()

        selected = await ProfileMatchingPolicy().select(session, sender.id)

    assert selected is not None
    assert selected.user_id == older.id


async def test_missing_region_and_empty_interests_use_stable_uuid_tie_break(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        sender = user(100, region=(None, None, None))
        lower_uuid = user(200, region=("11", "11440", None))
        higher_uuid = user(300, region=("26", "26110", None))
        session.add_all([sender, higher_uuid, lower_uuid])
        await session.commit()

        selected = await ProfileMatchingPolicy().select(session, sender.id)

    assert selected is not None
    assert selected.user_id == lower_uuid.id
    assert selected.strategy is MatchStrategy.PROFILE
    assert selected.score == 0.0
    assert selected.fallback_reason is None


async def test_returns_none_when_no_eligible_candidate(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        sender = user(100)
        session.add(sender)
        await session.commit()

        selected = await ProfileMatchingPolicy().select(session, sender.id)

    assert selected is None


async def test_selection_allows_semantic_fallback_metadata(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        sender = user(100)
        candidate = user(200)
        session.add_all([sender, candidate])
        await session.commit()

        selected = await ProfileMatchingPolicy().select(
            session,
            sender.id,
            strategy=MatchStrategy.PROFILE_FALLBACK,
            fallback_reason="INSUFFICIENT_EMBEDDINGS",
        )

    assert selected is not None
    assert selected.user_id == candidate.id
    assert selected.strategy is MatchStrategy.PROFILE_FALLBACK
    assert selected.fallback_reason == "INSUFFICIENT_EMBEDDINGS"
    assert selected.model_name is None
    assert selected.model_version is None
