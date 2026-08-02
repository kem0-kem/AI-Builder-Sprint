from collections import Counter
from dataclasses import dataclass, field

from app.moderation.schemas import ContentType, ModerationCategory, ModerationDecision


def latency_bucket(duration_ms: float) -> str:
    for limit in (25, 50, 100, 250, 500, 1000, 2500):
        if duration_ms <= limit:
            return f"le_{limit}ms"
    return "gt_2500ms"


@dataclass(slots=True)
class ModerationMetrics:
    decisions: Counter[tuple[str, str]] = field(default_factory=Counter)
    categories: Counter[tuple[str, str]] = field(default_factory=Counter)
    latencies: Counter[tuple[str, str]] = field(default_factory=Counter)
    retries: Counter[str] = field(default_factory=Counter)
    manual_reviews: Counter[str] = field(default_factory=Counter)

    def record_decision(
        self,
        content_type: ContentType,
        decision: ModerationDecision,
        categories: set[ModerationCategory] | frozenset[ModerationCategory],
        duration_ms: float,
    ) -> None:
        content = content_type.value
        self.decisions[(content, decision.value)] += 1
        self.latencies[(content, latency_bucket(duration_ms))] += 1
        for category in categories:
            self.categories[(content, category.value)] += 1


moderation_metrics = ModerationMetrics()
