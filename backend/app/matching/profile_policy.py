from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from uuid import UUID

from sqlalchemy import Select, or_, select, true
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.sql.elements import ColumnElement

from app.auth.models import User
from app.matching.eligibility import MatchingEligibilityPolicy
from app.matching.models import MatchHistory, MatchStrategy
from app.profiles.models import UserInterest


@dataclass(frozen=True, slots=True)
class MatchCandidate:
    user_id: UUID
    strategy: MatchStrategy
    score: float
    fallback_reason: str | None = None


@dataclass(frozen=True, slots=True)
class RegionStage:
    name: str
    predicate: ColumnElement[bool]


@dataclass(frozen=True, slots=True)
class _RankedProfile:
    user_id: UUID
    interest_ids: frozenset[UUID]
    matches_last_30_days: int
    last_matched_at: datetime | None


def jaccard(left: set[UUID] | frozenset[UUID], right: set[UUID] | frozenset[UUID]) -> float:
    union = left | right
    return len(left & right) / len(union) if union else 0.0


class ProfileMatchingPolicy:
    def __init__(self, eligibility: MatchingEligibilityPolicy | None = None) -> None:
        self._eligibility = eligibility or MatchingEligibilityPolicy()

    async def select(self, session: AsyncSession, sender_id: UUID) -> MatchCandidate | None:
        sender = await session.get(User, sender_id)
        if sender is None or not sender.is_active:
            return None

        for stage in self._region_stages(sender):
            candidates = list(
                await session.scalars(
                    self._eligibility.base_candidate_query(sender_id).where(stage.predicate)
                )
            )
            if candidates:
                return await self._select_within_stage(session, sender_id, candidates)
        return None

    def _region_stages(self, sender: User) -> list[RegionStage]:
        stages: list[RegionStage] = []
        if sender.sub_district_code:
            stages.append(
                RegionStage(
                    "SUB_DISTRICT",
                    User.sub_district_code == sender.sub_district_code,
                )
            )
        if sender.district_code:
            stages.append(
                RegionStage("DISTRICT", User.district_code == sender.district_code)
            )
        if sender.province_code:
            stages.append(
                RegionStage("PROVINCE", User.province_code == sender.province_code)
            )
        stages.append(RegionStage("NATIONAL", true()))
        return stages

    async def _select_within_stage(
        self,
        session: AsyncSession,
        sender_id: UUID,
        candidates: list[User],
    ) -> MatchCandidate:
        candidate_ids = [candidate.id for candidate in candidates]
        interest_ids = await self._interest_ids(session, [sender_id, *candidate_ids])
        profiles = await self._ranked_profiles(session, candidate_ids, interest_ids)
        sender_interests = interest_ids.get(sender_id, frozenset())
        never_matched = datetime.min.replace(tzinfo=UTC)
        selected = min(
            profiles,
            key=lambda item: (
                -jaccard(sender_interests, item.interest_ids),
                item.matches_last_30_days,
                item.last_matched_at or never_matched,
                str(item.user_id),
            ),
        )
        return MatchCandidate(
            user_id=selected.user_id,
            strategy=MatchStrategy.PROFILE,
            score=jaccard(sender_interests, selected.interest_ids),
        )

    async def _interest_ids(
        self,
        session: AsyncSession,
        user_ids: list[UUID],
    ) -> dict[UUID, frozenset[UUID]]:
        rows = await session.execute(
            select(UserInterest.user_id, UserInterest.interest_id).where(
                UserInterest.user_id.in_(user_ids)
            )
        )
        collected: dict[UUID, set[UUID]] = {user_id: set() for user_id in user_ids}
        for user_id, interest_id in rows:
            collected[user_id].add(interest_id)
        return {user_id: frozenset(values) for user_id, values in collected.items()}

    async def _ranked_profiles(
        self,
        session: AsyncSession,
        candidate_ids: list[UUID],
        interest_ids: dict[UUID, frozenset[UUID]],
    ) -> list[_RankedProfile]:
        statement: Select[tuple[UUID, UUID, datetime]] = select(
            MatchHistory.user_a_id,
            MatchHistory.user_b_id,
            MatchHistory.created_at,
        ).where(
            or_(
                MatchHistory.user_a_id.in_(candidate_ids),
                MatchHistory.user_b_id.in_(candidate_ids),
            )
        )
        rows = await session.execute(statement)
        cutoff = datetime.now(UTC) - timedelta(days=30)
        recent_counts = dict.fromkeys(candidate_ids, 0)
        last_matched: dict[UUID, datetime | None] = dict.fromkeys(candidate_ids)
        candidate_set = set(candidate_ids)
        for first_id, second_id, created_at in rows:
            occurred_at = self._as_utc(created_at)
            for candidate_id in (first_id, second_id):
                if candidate_id not in candidate_set:
                    continue
                if occurred_at >= cutoff:
                    recent_counts[candidate_id] += 1
                previous = last_matched[candidate_id]
                if previous is None or occurred_at > previous:
                    last_matched[candidate_id] = occurred_at
        return [
            _RankedProfile(
                user_id=candidate_id,
                interest_ids=interest_ids[candidate_id],
                matches_last_30_days=recent_counts[candidate_id],
                last_matched_at=last_matched[candidate_id],
            )
            for candidate_id in candidate_ids
        ]

    @staticmethod
    def _as_utc(value: datetime) -> datetime:
        return value if value.tzinfo is not None else value.replace(tzinfo=UTC)
