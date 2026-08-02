from dataclasses import dataclass
from uuid import UUID

from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.models import User
from app.matching.eligibility import MatchingEligibilityPolicy
from app.matching.gateway import EmbeddingVector
from app.matching.models import MatchHistory, UserMatchVector


@dataclass(frozen=True, slots=True)
class SemanticCandidate:
    user_id: UUID
    similarity: float


class CandidateLostRace(RuntimeError):
    def __init__(self, candidate_id: UUID) -> None:
        super().__init__(f"candidate lost eligibility before lock: {candidate_id}")
        self.candidate_id = candidate_id


class MatchingRepository:
    def __init__(
        self,
        session: AsyncSession,
        eligibility: MatchingEligibilityPolicy | None = None,
    ) -> None:
        self._session = session
        self._eligibility = eligibility or MatchingEligibilityPolicy()

    async def has_successful_match(self, user_id: UUID) -> bool:
        history_id = await self._session.scalar(
            select(MatchHistory.id)
            .where(
                or_(
                    MatchHistory.user_a_id == user_id,
                    MatchHistory.user_b_id == user_id,
                )
            )
            .limit(1)
        )
        return history_id is not None

    async def search_semantic_candidates(
        self,
        sender_id: UUID,
        query_vector: EmbeddingVector,
        model_name: str,
        model_version: str,
        *,
        limit: int = 20,
    ) -> list[SemanticCandidate]:
        bounded_limit = max(1, min(limit, 20))
        distance = UserMatchVector.embedding.cosine_distance(query_vector.values)
        statement = (
            self._eligibility.base_candidate_query(sender_id)
            .join(UserMatchVector, UserMatchVector.user_id == User.id)
            .where(
                UserMatchVector.model_name == model_name,
                UserMatchVector.model_version == model_version,
            )
            .add_columns((1 - distance).label("similarity"))
            .order_by(distance, User.id)
            .limit(bounded_limit)
        )
        rows = (await self._session.execute(statement)).all()
        return [
            SemanticCandidate(user_id=user.id, similarity=float(similarity))
            for user, similarity in rows
        ]

    async def lock_candidate(self, sender_id: UUID, candidate_id: UUID) -> User:
        candidate = await self._session.scalar(
            self._eligibility.base_candidate_query(sender_id)
            .where(User.id == candidate_id)
            .with_for_update(skip_locked=True, of=User)
        )
        if candidate is None:
            raise CandidateLostRace(candidate_id)
        return candidate

    def record_match(self, history: MatchHistory) -> None:
        self._session.add(history)
