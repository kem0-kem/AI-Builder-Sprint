from uuid import UUID

from sqlalchemy import Select, and_, exists, or_, select

from app.auth.models import User
from app.letters.models import UserBlock
from app.matching.models import MatchHistory


class MatchingEligibilityPolicy:
    def base_candidate_query(self, sender_id: UUID) -> Select[tuple[User]]:
        blocked_relationship = exists().where(
            or_(
                and_(
                    UserBlock.blocker_id == sender_id,
                    UserBlock.blocked_id == User.id,
                ),
                and_(
                    UserBlock.blocked_id == sender_id,
                    UserBlock.blocker_id == User.id,
                ),
            )
        )
        prior_match = exists().where(
            or_(
                and_(
                    MatchHistory.user_a_id == sender_id,
                    MatchHistory.user_b_id == User.id,
                ),
                and_(
                    MatchHistory.user_b_id == sender_id,
                    MatchHistory.user_a_id == User.id,
                ),
            )
        )
        return select(User).where(
            User.id != sender_id,
            User.is_active.is_(True),
            ~blocked_relationship,
            ~prior_match,
        )
