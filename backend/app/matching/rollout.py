"""Safe selection ordering for disabled, shadow, and enforce matching rollout."""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from enum import StrEnum
from typing import Literal, Protocol
from uuid import UUID

from app.matching.gateway import EmbeddingProviderUnavailable
from app.matching.metrics import FallbackReason, MatchingMetrics
from app.matching.privacy import PublicMatchStrategy

MatchingMode = Literal["disabled", "shadow", "enforce"]


class SemanticOutcome(StrEnum):
    SEMANTIC = "SEMANTIC"
    PROFILE_FALLBACK = "PROFILE_FALLBACK"
    NO_CANDIDATE = "NO_CANDIDATE"
    PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"


@dataclass(frozen=True, slots=True)
class ShadowComparison:
    semantic_outcome: str
    same_candidate: bool | None

    def __post_init__(self) -> None:
        if self.semantic_outcome not in {outcome.value for outcome in SemanticOutcome}:
            raise ValueError("invalid shadow semantic outcome")
        if type(self.same_candidate) not in (bool, type(None)):
            raise TypeError("shadow candidate equality must be bool or None")


@dataclass(frozen=True, slots=True)
class CandidateSelection:
    candidate_id: UUID | None
    strategy: PublicMatchStrategy
    fallback_reason: FallbackReason | None = None


@dataclass(frozen=True, slots=True)
class RolloutSelection:
    """Internal result. Candidate IDs must be stripped at the HTTP boundary."""

    authoritative: CandidateSelection
    shadow_comparison: ShadowComparison | None = None


class RolloutSelector(Protocol):
    async def has_prior_history(self, sender_id: UUID) -> bool: ...

    async def select_profile(
        self, sender_id: UUID, excluded: frozenset[UUID]
    ) -> CandidateSelection: ...

    async def select_semantic(
        self, sender_id: UUID, excluded: frozenset[UUID]
    ) -> CandidateSelection: ...


class CandidateLocker(Protocol):
    async def lock_candidate(self, sender_id: UUID, candidate_id: UUID) -> None: ...


class MatchingRollout:
    """Coordinate selection without ever locking around a provider call.

    ``select`` has no database side effects.  A delivery service performs its
    final lock after receiving the authoritative candidate, or uses
    ``select_and_lock`` for the standard two-attempt race loop.
    """

    def __init__(
        self,
        selector: RolloutSelector,
        *,
        mode: MatchingMode,
        metrics: MatchingMetrics | None = None,
    ) -> None:
        if mode not in {"disabled", "shadow", "enforce"}:
            raise ValueError("invalid matching rollout mode")
        self._selector = selector
        self._mode = mode
        self._metrics = metrics

    async def select(
        self,
        sender_id: UUID,
        *,
        excluded: frozenset[UUID] = frozenset(),
    ) -> RolloutSelection:
        if self._mode == "disabled":
            return RolloutSelection(await self._selector.select_profile(sender_id, excluded))

        has_history = await self._selector.has_prior_history(sender_id)
        if not has_history:
            return RolloutSelection(await self._selector.select_profile(sender_id, excluded))

        if self._mode == "enforce":
            return RolloutSelection(await self._selector.select_semantic(sender_id, excluded))

        # Shadow is intentionally fail-open only for a known provider failure.
        semantic_outcome, semantic_candidate_id = await self._shadow_observation(
            sender_id, excluded
        )
        authoritative = await self._selector.select_profile(sender_id, excluded)
        same_candidate = (
            semantic_candidate_id == authoritative.candidate_id
            if semantic_candidate_id is not None and authoritative.candidate_id is not None
            else None
        )
        comparison = ShadowComparison(semantic_outcome, same_candidate)
        if self._metrics is not None:
            self._metrics.record_shadow_comparison(comparison.same_candidate)
        return RolloutSelection(authoritative=authoritative, shadow_comparison=comparison)

    async def select_and_lock(
        self,
        sender_id: UUID,
        locker: CandidateLocker,
        *,
        lost_race: type[Exception],
        record: Callable[[CandidateSelection], Awaitable[None]],
    ) -> RolloutSelection:
        """Lock only after all profile/provider computation has completed."""
        excluded: frozenset[UUID] = frozenset()
        for _attempt in range(2):
            selection = await self.select(sender_id, excluded=excluded)
            candidate_id = selection.authoritative.candidate_id
            if candidate_id is None:
                return selection
            try:
                await locker.lock_candidate(sender_id, candidate_id)
            except lost_race:
                excluded = excluded | {candidate_id}
                continue
            await record(selection.authoritative)
            return selection
        return RolloutSelection(
            authoritative=CandidateSelection(
                candidate_id=None,
                strategy=PublicMatchStrategy.PROFILE,
            )
        )

    async def _shadow_observation(
        self, sender_id: UUID, excluded: frozenset[UUID]
    ) -> tuple[SemanticOutcome, UUID | None]:
        try:
            semantic = await self._selector.select_semantic(sender_id, excluded)
        except EmbeddingProviderUnavailable:
            return SemanticOutcome.PROVIDER_UNAVAILABLE, None
        return _semantic_outcome(semantic), semantic.candidate_id


def _semantic_outcome(selection: CandidateSelection) -> SemanticOutcome:
    if selection.strategy is PublicMatchStrategy.SEMANTIC:
        return SemanticOutcome.SEMANTIC
    if selection.strategy is PublicMatchStrategy.PROFILE_FALLBACK:
        return SemanticOutcome.PROFILE_FALLBACK
    return SemanticOutcome.NO_CANDIDATE
