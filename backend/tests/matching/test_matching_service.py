from collections.abc import Collection
from dataclasses import dataclass, field
from uuid import UUID

import pytest

from app.auth.models import User
from app.core.errors import ApiError
from app.matching.models import MatchStrategy
from app.matching.profile_policy import MatchCandidate
from app.matching.repository import CandidateLostRace
from app.matching.service import MatchingService

SENDER_ID = UUID(int=1)
FIRST_ID = UUID(int=2)
SECOND_ID = UUID(int=3)


def candidate(user_id: UUID) -> MatchCandidate:
    return MatchCandidate(user_id=user_id, strategy=MatchStrategy.PROFILE, score=0.5)


def user(user_id: UUID) -> User:
    return User(
        id=user_id,
        email=f"{user_id}@example.com",
        password_hash="hashed",
        nickname=str(user_id),
    )


@dataclass
class Repository:
    has_history: bool = False
    outcomes: list[User | Exception] = field(default_factory=list)
    history_calls: int = 0

    async def has_successful_match(self, sender_id: UUID) -> bool:
        assert sender_id == SENDER_ID
        self.history_calls += 1
        return self.has_history

    async def lock_candidate(self, sender_id: UUID, candidate_id: UUID) -> User:
        assert sender_id == SENDER_ID
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        assert outcome.id == candidate_id
        return outcome

    def record_match(self, history: object) -> None:
        pass


@dataclass
class Policy:
    candidates: list[MatchCandidate | None]
    calls: list[set[UUID]] = field(default_factory=list)

    async def select(
        self, session: object, sender_id: UUID, *, excluded_ids: Collection[UUID]
    ) -> MatchCandidate | None:
        assert sender_id == SENDER_ID
        self.calls.append(set(excluded_ids))
        return self.candidates.pop(0)


@dataclass
class SemanticPolicy(Policy):
    contents: list[str] = field(default_factory=list)

    async def select(
        self,
        session: object,
        sender_id: UUID,
        *,
        content: str,
        excluded_ids: Collection[UUID],
    ) -> MatchCandidate | None:
        self.contents.append(content)
        return await super().select(session, sender_id, excluded_ids=excluded_ids)


async def test_no_history_uses_profile_only() -> None:
    profile = Policy([candidate(FIRST_ID)])
    semantic = SemanticPolicy([candidate(SECOND_ID)])
    service = MatchingService(
        Repository(outcomes=[user(FIRST_ID)]), profile, mode="enforce", semantic=semantic  # type: ignore[arg-type]
    )

    locked = await service.select_and_lock(object(), SENDER_ID, "hello")  # type: ignore[arg-type]

    assert locked is not None and locked.user.id == FIRST_ID
    assert len(profile.calls) == 1
    assert semantic.calls == []


async def test_history_uses_semantic_in_enforce_mode() -> None:
    profile = Policy([candidate(FIRST_ID)])
    semantic = SemanticPolicy([candidate(SECOND_ID)])
    service = MatchingService(
        Repository(has_history=True, outcomes=[user(SECOND_ID)]),
        profile,
        mode="enforce",
        semantic=semantic,  # type: ignore[arg-type]
    )

    locked = await service.select_and_lock(object(), SENDER_ID, "letter content")  # type: ignore[arg-type]

    assert locked is not None and locked.user.id == SECOND_ID
    assert profile.calls == []
    assert semantic.contents == ["letter content"]


async def test_disabled_mode_uses_profile_even_with_history() -> None:
    profile = Policy([candidate(FIRST_ID)])
    semantic = SemanticPolicy([candidate(SECOND_ID)])
    service = MatchingService(
        Repository(has_history=True, outcomes=[user(FIRST_ID)]),
        profile,
        mode="disabled",
        semantic=semantic,  # type: ignore[arg-type]
    )

    await service.select_and_lock(object(), SENDER_ID, "hello")  # type: ignore[arg-type]

    assert len(profile.calls) == 1
    assert semantic.calls == []


async def test_lost_race_excludes_candidate_before_second_selection() -> None:
    profile = Policy([candidate(FIRST_ID), candidate(SECOND_ID)])
    service = MatchingService(
        Repository(
            outcomes=[CandidateLostRace(FIRST_ID), user(SECOND_ID)],
        ),
        profile,
        mode="disabled",
    )

    locked = await service.select_and_lock(object(), SENDER_ID, "hello")  # type: ignore[arg-type]

    assert locked is not None and locked.user.id == SECOND_ID
    assert profile.calls == [set(), {FIRST_ID}]


async def test_two_lost_races_return_bounded_conflict() -> None:
    profile = Policy([candidate(FIRST_ID), candidate(SECOND_ID)])
    service = MatchingService(
        Repository(outcomes=[CandidateLostRace(FIRST_ID), CandidateLostRace(SECOND_ID)]),
        profile,
        mode="disabled",
    )

    with pytest.raises(ApiError, match="매칭 후보") as error:
        await service.select_and_lock(object(), SENDER_ID, "hello")  # type: ignore[arg-type]

    assert error.value.code == "RESOURCE_CONFLICT"
    assert error.value.status_code == 409


async def test_no_candidate_returns_none() -> None:
    service = MatchingService(Repository(), Policy([None]), mode="disabled")

    assert await service.select_and_lock(object(), SENDER_ID, "hello") is None  # type: ignore[arg-type]


async def test_policy_exception_propagates() -> None:
    class BrokenPolicy:
        async def select(
            self, session: object, sender_id: UUID, *, excluded_ids: Collection[UUID]
        ) -> MatchCandidate | None:
            raise RuntimeError("unexpected policy failure")

    service = MatchingService(Repository(), BrokenPolicy(), mode="disabled")  # type: ignore[arg-type]

    with pytest.raises(RuntimeError, match="unexpected policy failure"):
        await service.select_and_lock(object(), SENDER_ID, "hello")  # type: ignore[arg-type]
