import re
import unicodedata
from collections import Counter
from dataclasses import dataclass

from app.moderation.schemas import ModerationCategory, ModerationDecision

_HORIZONTAL_WHITESPACE = re.compile(r"[^\S\n]+")
_URL = re.compile(r"https?://[^\s<>();,]+", re.IGNORECASE)
_EMAIL = re.compile(r"(?<![\w.+-])[\w.+-]+@[\w-]+(?:\.[\w-]+)+", re.IGNORECASE)
_PHONE = re.compile(r"(?<!\d)(?:\+?82[- .]?)?0?1[016789][- .]?\d{3,4}[- .]?\d{4}(?!\d)")
_RESIDENT_NUMBER = re.compile(r"(?<!\d)\d{6}[- ]?[1-8]\d{6}(?!\d)")
_REPEATED_CHARACTER = re.compile(r"(.)\1{9,}", re.DOTALL)


def normalize_text(text: str) -> str:
    """Return the canonical form used by all deterministic moderation rules."""

    normalized = unicodedata.normalize("NFKC", text).replace("\r\n", "\n").replace("\r", "\n")
    lines = (_HORIZONTAL_WHITESPACE.sub(" ", line).strip() for line in normalized.split("\n"))
    return "\n".join(lines).strip()


@dataclass(frozen=True, slots=True)
class LocalRuleResult:
    decision: ModerationDecision
    categories: frozenset[ModerationCategory]
    reason_code: str

    @classmethod
    def allow(cls) -> "LocalRuleResult":
        return cls(ModerationDecision.ALLOW, frozenset(), "LOCAL_ALLOW")

    @classmethod
    def review(
        cls, categories: set[ModerationCategory], reason_code: str
    ) -> "LocalRuleResult":
        return cls(ModerationDecision.REVIEW, frozenset(categories), reason_code)

    @classmethod
    def block(
        cls, categories: set[ModerationCategory], reason_code: str
    ) -> "LocalRuleResult":
        return cls(ModerationDecision.BLOCK, frozenset(categories), reason_code)


class LocalRuleEngine:
    def inspect(self, text: str) -> LocalRuleResult:
        normalized = normalize_text(text)

        if len(_URL.findall(normalized)) > 3:
            return LocalRuleResult.block(
                {ModerationCategory.SPAM}, "EXCESSIVE_URLS"
            )

        comparable_lines = [
            line.casefold() for line in normalized.split("\n") if line.strip()
        ]
        if comparable_lines and max(Counter(comparable_lines).values()) >= 4:
            return LocalRuleResult.block(
                {ModerationCategory.SPAM}, "EXACT_REPEATED_MESSAGE"
            )

        if (
            _EMAIL.search(normalized)
            or _PHONE.search(normalized)
            or _RESIDENT_NUMBER.search(normalized)
        ):
            return LocalRuleResult.review(
                {ModerationCategory.PERSONAL_DATA}, "POSSIBLE_PERSONAL_DATA"
            )

        if _REPEATED_CHARACTER.search(normalized):
            return LocalRuleResult.review(
                {ModerationCategory.SPAM}, "REPEATED_CHARACTER_RUN"
            )

        return LocalRuleResult.allow()
