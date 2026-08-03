from datetime import UTC, datetime
from unittest.mock import AsyncMock
from uuid import UUID

import pytest
from sqlalchemy.dialects import postgresql
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.auth.models import User
from app.letters.models import UserBlock
from app.matching.eligibility import MatchingEligibilityPolicy
from app.matching.models import MatchHistory, MatchStrategy
from app.matching.repository import CandidateLostRace, MatchingRepository

SENDER_ID = UUID("00000000-0000-0000-0000-000000000100")
MATCHED_LOW_ID = UUID("00000000-0000-0000-0000-000000000050")
ELIGIBLE_ID = UUID("00000000-0000-0000-0000-000000000200")
INACTIVE_ID = UUID("00000000-0000-0000-0000-000000000300")
BLOCKED_BY_SENDER_ID = UUID("00000000-0000-0000-0000-000000000400")
BLOCKS_SENDER_ID = UUID("00000000-0000-0000-0000-000000000500")
MATCHED_HIGH_ID = UUID("00000000-0000-0000-0000-000000000600")


def make_user(user_id: UUID, *, active: bool = True) -> User:
    return User(
        id=user_id,
        email=f"{user_id.int}@example.com",
        password_hash="hashed",
        nickname=str(user_id.int),
        is_active=active,
        created_at=datetime(2026, 1, 1, tzinfo=UTC),
    )


async def seed_exclusion_scenario(session: AsyncSession) -> None:
    session.add_all(
        [
            make_user(SENDER_ID),
            make_user(MATCHED_LOW_ID),
            make_user(ELIGIBLE_ID),
            make_user(INACTIVE_ID, active=False),
            make_user(BLOCKED_BY_SENDER_ID),
            make_user(BLOCKS_SENDER_ID),
            make_user(MATCHED_HIGH_ID),
            UserBlock(blocker_id=SENDER_ID, blocked_id=BLOCKED_BY_SENDER_ID),
            UserBlock(blocker_id=BLOCKS_SENDER_ID, blocked_id=SENDER_ID),
            MatchHistory.create(SENDER_ID, MATCHED_LOW_ID, MatchStrategy.PROFILE),
            MatchHistory.create(SENDER_ID, MATCHED_HIGH_ID, MatchStrategy.SEMANTIC),
        ]
    )
    await session.commit()


async def test_shared_query_excludes_self_inactive_both_block_directions_and_history(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        await seed_exclusion_scenario(session)

        candidates = list(
            await session.scalars(
                MatchingEligibilityPolicy().base_candidate_query(SENDER_ID).order_by(User.id)
            )
        )

    assert [candidate.id for candidate in candidates] == [ELIGIBLE_ID]


async def test_successful_match_lookup_checks_both_canonical_sides(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        await seed_exclusion_scenario(session)
        repository = MatchingRepository(session)

        assert await repository.has_successful_match(SENDER_ID) is True
        assert await repository.has_successful_match(MATCHED_LOW_ID) is True
        assert await repository.has_successful_match(MATCHED_HIGH_ID) is True
        assert await repository.has_successful_match(ELIGIBLE_ID) is False


async def test_record_match_joins_callers_transaction(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        session.add_all([make_user(SENDER_ID), make_user(ELIGIBLE_ID)])
        await session.flush()
        repository = MatchingRepository(session)
        history = MatchHistory.create(SENDER_ID, ELIGIBLE_ID, MatchStrategy.PROFILE)

        repository.record_match(history)
        await session.flush()

        assert await session.get(MatchHistory, history.id) is history


async def test_lock_candidate_rechecks_eligibility_after_initial_selection(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        session.add_all([make_user(SENDER_ID), make_user(ELIGIBLE_ID)])
        await session.commit()
        policy = MatchingEligibilityPolicy()
        initially_selected = await session.scalar(
            policy.base_candidate_query(SENDER_ID).where(User.id == ELIGIBLE_ID)
        )
        assert initially_selected is not None

        session.add(UserBlock(blocker_id=ELIGIBLE_ID, blocked_id=SENDER_ID))
        await session.commit()

        with pytest.raises(CandidateLostRace) as error:
            await MatchingRepository(session, policy).lock_candidate(
                SENDER_ID,
                ELIGIBLE_ID,
            )

    assert error.value.candidate_id == ELIGIBLE_ID


async def test_lock_candidate_uses_postgresql_skip_locked() -> None:
    session = AsyncMock(spec=AsyncSession)
    session.scalar.return_value = make_user(ELIGIBLE_ID)

    candidate = await MatchingRepository(session).lock_candidate(SENDER_ID, ELIGIBLE_ID)
    statement = session.scalar.await_args.args[0]
    sql = str(
        statement.compile(
            dialect=postgresql.dialect(),
            compile_kwargs={"literal_binds": True},
        )
    )

    assert candidate.id == ELIGIBLE_ID
    assert "NOT (EXISTS" in sql
    assert "NOT IN" not in sql
    assert "FOR UPDATE OF users SKIP LOCKED" in sql
