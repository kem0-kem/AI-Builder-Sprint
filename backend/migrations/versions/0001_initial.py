"""Create the SlowTalk schema."""

from collections.abc import Sequence

from alembic import op

# Register all model tables before create_all/drop_all.
from app.auth import models as auth_models  # noqa: F401
from app.chat import models as chat_models  # noqa: F401
from app.db.base import Base
from app.feeds import models as feed_models  # noqa: F401
from app.letters import models as letter_models  # noqa: F401
from app.meetings import models as meeting_models  # noqa: F401
from app.profiles import models as profile_models  # noqa: F401
from app.reports import models as report_models  # noqa: F401

revision: str = "0001_initial"
down_revision: str | Sequence[str] | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    Base.metadata.create_all(bind=op.get_bind())


def downgrade() -> None:
    Base.metadata.drop_all(bind=op.get_bind())
