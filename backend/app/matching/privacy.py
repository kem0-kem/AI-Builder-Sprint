"""Explicit public boundary for matching decisions.

Matching internals can contain vectors, source IDs, and similarity values.  No
such value may cross this module into an HTTP or idempotency response.
"""

from collections.abc import Mapping
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.matching.metrics import FallbackReason


class PublicMatchStrategy(StrEnum):
    PROFILE = "PROFILE"
    SEMANTIC = "SEMANTIC"
    PROFILE_FALLBACK = "PROFILE_FALLBACK"


class MatchingPrivacyError(ValueError):
    """Raised before unsafe matching metadata can leave the service boundary."""


class PublicMatchingResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, serialize_by_alias=True)

    matched: bool
    strategy: PublicMatchStrategy | None
    fallback_reason: FallbackReason | None = Field(default=None, alias="fallbackReason")


_SENSITIVE_KEYS = frozenset(
    {
        "candidateid",
        "senderid",
        "recipientid",
        "sourceletterids",
        "similarity",
        "similarityscore",
        "score",
        "vector",
        "modelresponse",
        "content",
        "embedding",
        "payload",
        "modelname",
        "modelversion",
    }
)


def public_matching_result(
    *,
    matched: bool,
    strategy: PublicMatchStrategy | None,
    fallback_reason: FallbackReason | None = None,
) -> PublicMatchingResult:
    """Create the only response object matching code is allowed to publish."""
    if not matched and (strategy is not None or fallback_reason is not None):
        raise MatchingPrivacyError("an unmatched result cannot carry matching metadata")
    if matched and strategy is None:
        raise MatchingPrivacyError("a matched result requires a strategy")
    if strategy is PublicMatchStrategy.PROFILE_FALLBACK and fallback_reason is None:
        raise MatchingPrivacyError("profile fallback requires a bounded reason")
    if strategy is not PublicMatchStrategy.PROFILE_FALLBACK and fallback_reason is not None:
        raise MatchingPrivacyError("only profile fallback may expose a fallback reason")
    return PublicMatchingResult(
        matched=matched,
        strategy=strategy,
        fallback_reason=fallback_reason,
    )


def validate_public_matching_payload(payload: Mapping[str, Any]) -> None:
    """Reject forbidden keys recursively before serialization or replay storage."""
    _assert_no_sensitive_data(payload)
    expected = {"matched", "strategy", "fallbackReason"}
    if set(payload) != expected:
        raise MatchingPrivacyError("matching response has an invalid public shape")
    PublicMatchingResult.model_validate(payload)


def _assert_no_sensitive_data(value: object) -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            if _normalize_key(str(key)) in _SENSITIVE_KEYS:
                raise MatchingPrivacyError("matching response contains private metadata")
            _assert_no_sensitive_data(child)
    elif isinstance(value, (list, tuple)):
        for child in value:
            _assert_no_sensitive_data(child)


def _normalize_key(key: str) -> str:
    return "".join(character for character in key.lower() if character.isalnum())
