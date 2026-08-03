"""Small, deliberately bounded in-memory counters for matching rollout."""

from collections import Counter
from datetime import timedelta
from enum import StrEnum
from typing import TypeVar

MetricLabel = TypeVar("MetricLabel", bound=StrEnum)


class AuthoritativeStrategy(StrEnum):
    PROFILE = "PROFILE"
    SEMANTIC = "SEMANTIC"
    PROFILE_FALLBACK = "PROFILE_FALLBACK"
    NO_MATCH = "NO_MATCH"


class ProfileRegionStage(StrEnum):
    SUB_DISTRICT = "SUB_DISTRICT"
    DISTRICT = "DISTRICT"
    PROVINCE = "PROVINCE"
    NATIONAL = "NATIONAL"
    NO_CANDIDATE = "NO_CANDIDATE"


class SemanticThresholdOutcome(StrEnum):
    PASS = "PASS"
    BELOW = "BELOW"
    NO_VECTOR = "NO_VECTOR"


class FallbackReason(StrEnum):
    INSUFFICIENT_EMBEDDINGS = "INSUFFICIENT_EMBEDDINGS"
    PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"


class ProviderLatencyBucket(StrEnum):
    LE_25MS = "le_25ms"
    LE_50MS = "le_50ms"
    LE_100MS = "le_100ms"
    LE_250MS = "le_250ms"
    LE_500MS = "le_500ms"
    LE_1000MS = "le_1000ms"
    LE_2500MS = "le_2500ms"
    GT_2500MS = "gt_2500ms"


class FinalLockAttempt(StrEnum):
    FIRST = "first"
    SECOND = "second"
    EXHAUSTED = "exhausted"


class ShadowComparisonBucket(StrEnum):
    SAME = "same"
    DIFFERENT = "different"
    UNAVAILABLE = "unavailable"


class MatchingMetrics:
    """Counters that cannot accept identities, content, scores, or error text."""

    def __init__(self) -> None:
        self.authoritative_strategies: Counter[AuthoritativeStrategy] = Counter()
        self.profile_region_stages: Counter[ProfileRegionStage] = Counter()
        self.semantic_threshold_outcomes: Counter[SemanticThresholdOutcome] = Counter()
        self.fallback_reasons: Counter[FallbackReason] = Counter()
        self.provider_latency_buckets: Counter[ProviderLatencyBucket] = Counter()
        self.final_lock_attempts: Counter[FinalLockAttempt] = Counter()
        self.shadow_comparisons: Counter[ShadowComparisonBucket] = Counter()

    def record_authoritative_strategy(self, strategy: AuthoritativeStrategy) -> None:
        self.authoritative_strategies[_require_enum(strategy, AuthoritativeStrategy)] += 1

    def record_profile_region_stage(self, stage: ProfileRegionStage) -> None:
        self.profile_region_stages[_require_enum(stage, ProfileRegionStage)] += 1

    def record_semantic_threshold(self, outcome: SemanticThresholdOutcome) -> None:
        self.semantic_threshold_outcomes[_require_enum(outcome, SemanticThresholdOutcome)] += 1

    def record_fallback(self, reason: FallbackReason) -> None:
        self.fallback_reasons[_require_enum(reason, FallbackReason)] += 1

    def record_provider_latency(self, duration: timedelta) -> ProviderLatencyBucket:
        if not isinstance(duration, timedelta):
            raise TypeError("duration must be a timedelta")
        milliseconds = duration.total_seconds() * 1000
        if milliseconds < 0:
            raise ValueError("duration cannot be negative")
        bucket = _latency_bucket(milliseconds)
        self.provider_latency_buckets[bucket] += 1
        return bucket

    def record_final_lock_attempt(self, attempt: FinalLockAttempt) -> None:
        self.final_lock_attempts[_require_enum(attempt, FinalLockAttempt)] += 1

    def record_shadow_comparison(self, same_candidate: bool | None) -> None:
        if type(same_candidate) not in (bool, type(None)):
            raise TypeError("shadow comparison must be bool or None")
        bucket = (
            ShadowComparisonBucket.SAME
            if same_candidate is True
            else ShadowComparisonBucket.DIFFERENT
            if same_candidate is False
            else ShadowComparisonBucket.UNAVAILABLE
        )
        self.shadow_comparisons[bucket] += 1

    def __repr__(self) -> str:
        # Enum names and integer counts are the complete metric surface.
        return (
            "MatchingMetrics("
            f"authoritative={dict(self.authoritative_strategies)!r}, "
            f"profile_regions={dict(self.profile_region_stages)!r}, "
            f"semantic_thresholds={dict(self.semantic_threshold_outcomes)!r}, "
            f"fallbacks={dict(self.fallback_reasons)!r}, "
            f"provider_latency={dict(self.provider_latency_buckets)!r}, "
            f"final_locks={dict(self.final_lock_attempts)!r}, "
            f"shadow={dict(self.shadow_comparisons)!r})"
        )


def _require_enum(value: object, enum_type: type[MetricLabel]) -> MetricLabel:
    if not isinstance(value, enum_type):
        raise TypeError(f"expected {enum_type.__name__}")
    return value


def _latency_bucket(milliseconds: float) -> ProviderLatencyBucket:
    if milliseconds <= 25:
        return ProviderLatencyBucket.LE_25MS
    if milliseconds <= 50:
        return ProviderLatencyBucket.LE_50MS
    if milliseconds <= 100:
        return ProviderLatencyBucket.LE_100MS
    if milliseconds <= 250:
        return ProviderLatencyBucket.LE_250MS
    if milliseconds <= 500:
        return ProviderLatencyBucket.LE_500MS
    if milliseconds <= 1000:
        return ProviderLatencyBucket.LE_1000MS
    if milliseconds <= 2500:
        return ProviderLatencyBucket.LE_2500MS
    return ProviderLatencyBucket.GT_2500MS
