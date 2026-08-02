from datetime import timedelta
from uuid import UUID, uuid4

import pytest

from app.matching.gateway import EmbeddingProviderUnavailable
from app.matching.metrics import (
    AuthoritativeStrategy,
    MatchingMetrics,
    ProviderLatencyBucket,
)
from app.matching.privacy import (
    MatchingPrivacyError,
    PublicMatchStrategy,
    public_matching_result,
    validate_public_matching_payload,
)
from app.matching.rollout import CandidateSelection, MatchingRollout, SemanticOutcome


class FakeSelector:
    def __init__(
        self,
        *,
        history: bool,
        profile_id: UUID | None,
        semantic_id: UUID | None,
    ) -> None:
        self.history = history
        self.profile_id = profile_id
        self.semantic_id = semantic_id
        self.calls: list[str] = []
        self.semantic_error: Exception | None = None

    async def has_prior_history(self, sender_id: UUID) -> bool:
        del sender_id
        self.calls.append("history")
        return self.history

    async def select_profile(
        self, sender_id: UUID, excluded: frozenset[UUID]
    ) -> CandidateSelection:
        del sender_id, excluded
        self.calls.append("profile")
        return CandidateSelection(self.profile_id, PublicMatchStrategy.PROFILE)

    async def select_semantic(
        self, sender_id: UUID, excluded: frozenset[UUID]
    ) -> CandidateSelection:
        del sender_id, excluded
        self.calls.append("semantic")
        if self.semantic_error is not None:
            raise self.semantic_error
        return CandidateSelection(self.semantic_id, PublicMatchStrategy.SEMANTIC)


@pytest.mark.parametrize(
    ("mode", "history", "expected_calls", "expected_strategy"),
    [
        ("disabled", False, ["profile"], PublicMatchStrategy.PROFILE),
        ("disabled", True, ["profile"], PublicMatchStrategy.PROFILE),
        ("shadow", False, ["history", "profile"], PublicMatchStrategy.PROFILE),
        (
            "shadow",
            True,
            ["history", "semantic", "profile"],
            PublicMatchStrategy.PROFILE,
        ),
        ("enforce", False, ["history", "profile"], PublicMatchStrategy.PROFILE),
        (
            "enforce",
            True,
            ["history", "semantic"],
            PublicMatchStrategy.SEMANTIC,
        ),
    ],
)
async def test_rollout_modes_select_the_right_authoritative_policy(
    mode: str,
    history: bool,
    expected_calls: list[str],
    expected_strategy: PublicMatchStrategy,
) -> None:
    selector = FakeSelector(
        history=history,
        profile_id=uuid4(),
        semantic_id=uuid4(),
    )
    result = await MatchingRollout(selector, mode=mode).select(uuid4())  # type: ignore[arg-type]

    assert selector.calls == expected_calls
    assert result.authoritative.strategy is expected_strategy


async def test_shadow_records_only_bounded_candidate_equality() -> None:
    candidate_id = uuid4()
    selector = FakeSelector(history=True, profile_id=candidate_id, semantic_id=candidate_id)
    result = await MatchingRollout(selector, mode="shadow").select(uuid4())

    assert result.shadow_comparison is not None
    assert result.shadow_comparison.semantic_outcome is SemanticOutcome.SEMANTIC
    assert result.shadow_comparison.same_candidate is True
    assert str(candidate_id) not in repr(result.shadow_comparison)


async def test_known_provider_failure_is_fail_open_only_in_shadow() -> None:
    selector = FakeSelector(history=True, profile_id=uuid4(), semantic_id=None)
    selector.semantic_error = EmbeddingProviderUnavailable("private provider response")

    result = await MatchingRollout(selector, mode="shadow").select(uuid4())

    assert result.authoritative.strategy is PublicMatchStrategy.PROFILE
    assert result.shadow_comparison is not None
    assert result.shadow_comparison.semantic_outcome is SemanticOutcome.PROVIDER_UNAVAILABLE
    assert result.shadow_comparison.same_candidate is None


async def test_programming_error_is_not_hidden_by_shadow() -> None:
    selector = FakeSelector(history=True, profile_id=uuid4(), semantic_id=None)
    selector.semantic_error = TypeError("bug")

    with pytest.raises(TypeError, match="bug"):
        await MatchingRollout(selector, mode="shadow").select(uuid4())


def test_metrics_only_accept_bounded_labels_and_never_capture_exception_text() -> None:
    metrics = MatchingMetrics()
    metrics.record_authoritative_strategy(AuthoritativeStrategy.SEMANTIC)
    assert (
        metrics.record_provider_latency(timedelta(milliseconds=2501))
        is ProviderLatencyBucket.GT_2500MS
    )
    with pytest.raises(TypeError):
        metrics.record_authoritative_strategy("private exception text")  # type: ignore[arg-type]
    assert "private exception text" not in repr(metrics)


def test_public_matching_boundary_has_exact_safe_shape() -> None:
    result = public_matching_result(
        matched=True,
        strategy=PublicMatchStrategy.PROFILE_FALLBACK,
        fallback_reason="PROVIDER_UNAVAILABLE",  # type: ignore[arg-type]
    )
    payload = result.model_dump(by_alias=True)
    assert payload == {
        "matched": True,
        "strategy": "PROFILE_FALLBACK",
        "fallbackReason": "PROVIDER_UNAVAILABLE",
    }
    validate_public_matching_payload(payload)
    with pytest.raises(MatchingPrivacyError):
        validate_public_matching_payload({**payload, "candidateId": str(uuid4())})
    with pytest.raises(MatchingPrivacyError):
        validate_public_matching_payload(
            {
                **payload,
                "vector": [0.123],
            }
        )
