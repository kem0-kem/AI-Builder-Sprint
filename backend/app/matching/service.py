"""Delivery-time matching orchestration."""

from __future__ import annotations

import inspect
from collections.abc import Collection
from dataclasses import dataclass
from typing import Any, Protocol, cast
from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.models import User
from app.core.errors import ApiError
from app.matching.metrics import (
    AuthoritativeStrategy,
    FinalLockAttempt,
    MatchingMetrics,
)
from app.matching.models import MatchHistory
from app.matching.profile_policy import MatchCandidate, ProfileMatchingPolicy
from app.matching.repository import CandidateLostRace, MatchingRepository


class CandidatePolicy(Protocol):
    async def select(
        self,
        session: AsyncSession,
        sender_id: UUID,
        *,
        excluded_ids: Collection[UUID] = (),
    ) -> MatchCandidate | None: ...


@dataclass(frozen=True, slots=True)
class LockedMatch:
    user: User
    candidate: MatchCandidate


class MatchingService:
    """Choose then lock one recipient in the caller's transaction."""

    def __init__(
        self,
        repository: MatchingRepository,
        profile: CandidatePolicy | ProfileMatchingPolicy,
        *,
        mode: str,
        semantic: CandidatePolicy | None = None,
        metrics: MatchingMetrics | None = None,
    ) -> None:
        self._repository = repository
        self._profile = profile
        self._mode = mode
        self._semantic = semantic
        self._metrics = metrics

    async def select_and_lock(
        self,
        session: AsyncSession,
        sender_id: UUID,
        content: str,
    ) -> LockedMatch | None:
        excluded: set[UUID] = set()
        has_history = await self._repository.has_successful_match(sender_id)
        for attempt in range(2):
            candidate = await self._select(
                session,
                sender_id,
                content,
                has_history=has_history,
                excluded_ids=excluded,
            )
            if candidate is None:
                self._record_strategy(None)
                return None
            try:
                user = await self._repository.lock_candidate(sender_id, candidate.user_id)
            except CandidateLostRace:
                excluded.add(candidate.user_id)
                continue
            self._record_lock(attempt)
            self._record_strategy(candidate)
            return LockedMatch(user=user, candidate=candidate)
        self._record_lock(None)
        raise ApiError(
            "RESOURCE_CONFLICT",
            "매칭 후보 상태가 변경되었습니다. 다시 시도해 주세요.",
            409,
        )

    async def _select(
        self,
        session: AsyncSession,
        sender_id: UUID,
        content: str,
        *,
        has_history: bool,
        excluded_ids: set[UUID],
    ) -> MatchCandidate | None:
        if not has_history or self._mode == "disabled":
            return await _select_with_exclusions(
                self._profile, session, sender_id, excluded_ids, content=None
            )
        if self._mode == "enforce":
            if self._semantic is None:
                raise ApiError(
                    "EMBEDDING_SERVICE_UNAVAILABLE",
                    "의미 기반 매칭을 사용할 수 없습니다.",
                    503,
                )
            return await _select_with_exclusions(
                self._semantic, session, sender_id, excluded_ids, content=content
            )
        # Shadow deliberately computes semantic selection only for bounded
        # observation.  It never supplies the candidate that is locked.
        if self._mode == "shadow" and self._semantic is not None:
            shadow = await _select_with_exclusions(
                self._semantic, session, sender_id, excluded_ids, content=content
            )
            profile = await _select_with_exclusions(
                self._profile, session, sender_id, excluded_ids, content=None
            )
            if self._metrics is not None:
                same_candidate = (
                    shadow.user_id == profile.user_id
                    if shadow is not None and profile is not None
                    else None
                )
                self._metrics.record_shadow_comparison(
                    same_candidate
                )
            return profile
        return await _select_with_exclusions(
            self._profile, session, sender_id, excluded_ids, content=None
        )

    def record_match(self, history: MatchHistory) -> None:
        self._repository.record_match(history)

    def _record_lock(self, attempt: int | None) -> None:
        if self._metrics is None:
            return
        self._metrics.record_final_lock_attempt(
            FinalLockAttempt.FIRST
            if attempt == 0
            else FinalLockAttempt.SECOND
            if attempt == 1
            else FinalLockAttempt.EXHAUSTED
        )

    def _record_strategy(self, candidate: MatchCandidate | None) -> None:
        if self._metrics is None:
            return
        strategy = (
            AuthoritativeStrategy.NO_MATCH
            if candidate is None
            else AuthoritativeStrategy(candidate.strategy.value)
        )
        self._metrics.record_authoritative_strategy(strategy)


async def _select_with_exclusions(
    policy: Any,
    session: AsyncSession,
    sender_id: UUID,
    excluded_ids: set[UUID],
    *,
    content: str | None,
) -> MatchCandidate | None:
    """Bridge the pre-Task-6 profile policy until its exclusion contract lands."""

    parameters = inspect.signature(policy.select).parameters
    kwargs: dict[str, Any] = {}
    if "excluded_ids" in parameters:
        kwargs["excluded_ids"] = excluded_ids
    if "content" in parameters:
        kwargs["content"] = content
    result = await policy.select(session, sender_id, **kwargs)
    return cast(MatchCandidate | None, result)
