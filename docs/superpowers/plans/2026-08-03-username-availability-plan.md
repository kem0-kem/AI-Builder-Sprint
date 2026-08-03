# Username Availability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a backward-compatible, public username availability API backed by an optional unique username on SlowTalk users.

**Architecture:** Extend the existing auth domain with one canonical username validation type shared by signup and query parsing. Store normalized usernames in a nullable uniquely indexed column, expose availability through the existing success envelope, and retain the database constraint as the concurrency authority.

**Tech Stack:** Python 3.11, FastAPI, Pydantic v2, SQLAlchemy 2, Alembic, pytest/httpx, Ruff, mypy

## Global Constraints

- The public path is exactly `GET /api/v1/auth/check-username` with required query parameter `username`.
- Username input accepts ASCII letters, digits, and underscore; length is 3 to 30; storage and comparison use lowercase.
- `username` remains optional during signup so existing clients keep working.
- `nickname` remains the editable display name and email remains the login identifier.
- API responses use SlowTalk's existing `success(...)` envelope.
- Availability is advisory; database uniqueness remains authoritative for concurrent signup.
- The migration is `0005_usernames` with parent `0004_matching_vectors` and leaves legacy usernames `NULL`.
- Do not modify or stage `.codex-remote-attachments/`.

---

### Task 1: Username persistence and linear migration

**Files:**
- Create: `backend/migrations/versions/0005_usernames.py`
- Create: `backend/tests/auth/test_username_migration.py`
- Modify: `backend/app/auth/models.py`
- Modify: `backend/tests/matching/test_vector_models.py`

**Interfaces:**
- Consumes: existing `User` SQLAlchemy model and Alembic revision `0004_matching_vectors`
- Produces: nullable `User.username: Mapped[str | None]`, unique index `ix_users_username`, and single Alembic head `0005_usernames`

- [ ] **Step 1: Write failing model and migration tests**

Create `backend/tests/auth/test_username_migration.py`:

```python
from pathlib import Path

from alembic.config import Config
from alembic.script import ScriptDirectory

from app.auth.models import User


def alembic_config() -> Config:
    backend_dir = Path(__file__).resolve().parents[2]
    config = Config(str(backend_dir / "alembic.ini"))
    config.set_main_option("path_separator", "os")
    return config


def test_username_model_is_optional_and_uniquely_indexed() -> None:
    username = User.__table__.c.username
    index = next(item for item in User.__table__.indexes if item.name == "ix_users_username")

    assert username.nullable is True
    assert username.type.length == 30
    assert index.unique is True


def test_username_migration_is_the_only_linear_head() -> None:
    scripts = ScriptDirectory.from_config(alembic_config())
    revision = scripts.get_revision("0005_usernames")

    assert scripts.get_heads() == ["0005_usernames"]
    assert revision is not None
    assert revision.down_revision == "0004_matching_vectors"
```

In `backend/tests/matching/test_vector_models.py`, rename
`test_matching_migration_is_the_only_linear_head` to
`test_matching_migration_follows_moderation` and remove only the assertion against
`scripts.get_heads()`. Keep the assertions for revision existence, parent revision, and
embedding dimensions.

- [ ] **Step 2: Run the focused tests and verify red state**

Run:

```powershell
cd backend
python -m pytest tests/auth/test_username_migration.py tests/matching/test_vector_models.py -q
```

Expected: failure because `User.username` and revision `0005_usernames` do not exist.

- [ ] **Step 3: Add the model field and migration**

Add to `User` in `backend/app/auth/models.py`:

```python
username: Mapped[str | None] = mapped_column(
    String(30),
    unique=True,
    index=True,
    default=None,
)
```

Create `backend/migrations/versions/0005_usernames.py`:

```python
"""Add optional unique usernames."""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0005_usernames"
down_revision: str | Sequence[str] | None = "0004_matching_vectors"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("users", sa.Column("username", sa.String(length=30), nullable=True))
    op.create_index("ix_users_username", "users", ["username"], unique=True)


def downgrade() -> None:
    op.drop_index("ix_users_username", table_name="users")
    op.drop_column("users", "username")
```

- [ ] **Step 4: Run focused tests and verify green state**

Run:

```powershell
cd backend
python -m pytest tests/auth/test_username_migration.py tests/matching/test_vector_models.py -q
python -m ruff check app/auth/models.py migrations/versions/0005_usernames.py tests/auth/test_username_migration.py tests/matching/test_vector_models.py
python -m mypy app/auth/models.py
```

Expected: all commands exit 0.

- [ ] **Step 5: Commit persistence changes**

```powershell
git add -- backend/app/auth/models.py backend/migrations/versions/0005_usernames.py backend/tests/auth/test_username_migration.py backend/tests/matching/test_vector_models.py
git commit -m "feat(auth): add optional usernames"
```

---

### Task 2: Availability API and signup integration

**Files:**
- Modify: `backend/app/auth/schemas.py`
- Modify: `backend/app/auth/router.py`
- Modify: `backend/tests/auth/test_auth_api.py`

**Interfaces:**
- Consumes: `User.username` from Task 1 and existing `success(...)`/`ApiError` response conventions
- Produces: reusable `Username` validation type, optional `SignupRequest.username`, and `GET /auth/check-username`

- [ ] **Step 1: Write failing auth API tests**

Append tests to `backend/tests/auth/test_auth_api.py` that verify:

```python
import pytest


async def test_username_availability_and_case_normalization(client: AsyncClient) -> None:
    available = await client.get(
        "/api/v1/auth/check-username", params={"username": "Test_User"}
    )
    assert available.status_code == 200
    assert available.json()["data"] == {"available": True}

    created = await client.post(
        "/api/v1/auth/signup",
        json={
            "email": "username@example.com",
            "password": "strong-pass",
            "nickname": "표시 이름",
            "username": "Test_User",
        },
    )
    assert created.status_code == 201

    unavailable = await client.get(
        "/api/v1/auth/check-username", params={"username": "TEST_USER"}
    )
    assert unavailable.status_code == 200
    assert unavailable.json()["data"] == {"available": False}


async def test_duplicate_username_signup_is_rejected(client: AsyncClient) -> None:
    first = {
        "email": "first-username@example.com",
        "password": "strong-pass",
        "nickname": "첫 번째",
        "username": "same_user",
    }
    second = {**first, "email": "second-username@example.com", "nickname": "두 번째"}

    assert (await client.post("/api/v1/auth/signup", json=first)).status_code == 201
    duplicate = await client.post("/api/v1/auth/signup", json=second)

    assert duplicate.status_code == 409
    assert duplicate.json()["error"]["code"] == "USERNAME_ALREADY_EXISTS"


@pytest.mark.parametrize("username", ["ab", "has-hyphen", "한글id"])
async def test_username_availability_rejects_invalid_values(
    client: AsyncClient, username: str
) -> None:
    response = await client.get(
        "/api/v1/auth/check-username", params={"username": username}
    )
    assert response.status_code == 422


async def test_legacy_signup_without_username_still_works(client: AsyncClient) -> None:
    response = await client.post(
        "/api/v1/auth/signup",
        json={
            "email": "legacy@example.com",
            "password": "strong-pass",
            "nickname": "기존 가입",
        },
    )
    assert response.status_code == 201
```

- [ ] **Step 2: Run auth tests and verify red state**

Run:

```powershell
cd backend
python -m pytest tests/auth/test_auth_api.py -q
```

Expected: the availability endpoint returns 404 and duplicate usernames are not rejected.

- [ ] **Step 3: Define shared validation and signup field**

In `backend/app/auth/schemas.py`, add:

```python
from typing import Annotated

from pydantic import BeforeValidator

USERNAME_PATTERN = r"^[a-z0-9_]+$"


def normalize_username(value: str) -> str:
    return value.lower()


Username = Annotated[
    str,
    BeforeValidator(normalize_username),
    Field(min_length=3, max_length=30, pattern=USERNAME_PATTERN),
]
```

Add the optional field to `SignupRequest`:

```python
username: Username | None = None
```

- [ ] **Step 4: Add conflict lookup, signup storage, and availability route**

In `backend/app/auth/router.py`, import `Annotated` and `Username`, then add this helper:

```python
async def raise_signup_conflict(
    session: Session, *, email: str, username: str | None
) -> None:
    if username is not None:
        username_exists = await session.scalar(
            select(User.id).where(User.username == username)
        )
        if username_exists is not None:
            raise ApiError(
                "USERNAME_ALREADY_EXISTS",
                "이미 사용 중인 아이디입니다.",
                409,
            )
    email_exists = await session.scalar(select(User.id).where(User.email == email))
    if email_exists is not None:
        raise ApiError("EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다.", 409)
```

At the beginning of `signup`, normalize the email once, check conflicts, and include the
username in the inserted model:

```python
email = request.email.lower()
await raise_signup_conflict(session, email=email, username=request.username)
user = User(
    email=email,
    username=request.username,
    password_hash=hash_password(request.password),
    nickname=request.nickname,
)
```

Replace the existing `IntegrityError` handler with a rollback, a repeated conflict lookup
for race classification, and a non-leaking fallback:

```python
except IntegrityError as exc:
    await session.rollback()
    await raise_signup_conflict(session, email=email, username=request.username)
    raise ApiError("SIGNUP_CONFLICT", "회원가입 정보를 사용할 수 없습니다.", 409) from exc
```

Add the public endpoint near `email_availability`:

```python
@router.get("/check-username")
async def check_username(
    session: Session,
    username: Annotated[Username, Query(description="확인할 아이디")],
) -> dict[str, object]:
    exists = await session.scalar(select(User.id).where(User.username == username))
    return success({"available": exists is None})
```

- [ ] **Step 5: Run auth tests and verify green state**

Run:

```powershell
cd backend
python -m pytest tests/auth/test_auth_api.py -q
python -m ruff check app/auth tests/auth
python -m mypy app/auth
```

Expected: all commands exit 0.

- [ ] **Step 6: Commit API behavior**

```powershell
git add -- backend/app/auth/schemas.py backend/app/auth/router.py backend/tests/auth/test_auth_api.py
git commit -m "feat(auth): add username availability check"
```

---

### Task 3: OpenAPI contract and full verification

**Files:**
- Modify: `backend/tests/contract/test_openapi.py`
- Modify: `backend/openapi/slowtalk-v1.json`
- Modify: `backend/README.md`

**Interfaces:**
- Consumes: FastAPI route and schema generated by Task 2
- Produces: versioned API snapshot and operator-facing documentation

- [ ] **Step 1: Add failing OpenAPI assertions**

Add `/api/v1/auth/check-username` to the `required` set in
`test_required_contract_and_removed_feed_ocr`. Add a focused test:

```python
def test_username_availability_contract() -> None:
    operation = create_app().openapi()["paths"]["/api/v1/auth/check-username"]["get"]
    username = next(
        parameter for parameter in operation["parameters"] if parameter["name"] == "username"
    )

    assert username["required"] is True
    assert username["schema"]["minLength"] == 3
    assert username["schema"]["maxLength"] == 30
    assert username["schema"]["pattern"] == "^[a-z0-9_]+$"
```

- [ ] **Step 2: Run the snapshot tests and verify red state**

Run:

```powershell
cd backend
python -m pytest tests/contract/test_openapi.py -q
```

Expected: snapshot mismatch until the versioned OpenAPI file is regenerated.

- [ ] **Step 3: Regenerate OpenAPI and document the rollout**

Run:

```powershell
cd backend
python scripts/export_openapi.py openapi/slowtalk-v1.json
```

Add a README note stating that username is optional during the compatibility rollout,
the check endpoint is advisory, valid input is 3-30 ASCII letters/digits/underscore, and
stored values are lowercase.

- [ ] **Step 4: Run fresh full verification**

Run:

```powershell
cd backend
python -m ruff check app tests migrations
python -m mypy app
python -m pytest -q
git diff --check
```

Expected: every command exits 0, the test report contains no failures, and the Alembic
test reports exactly one head at `0005_usernames`.

- [ ] **Step 5: Review scope and commit documentation**

Confirm `git status --short` contains only the listed Task 3 files plus the intentionally
untracked `.codex-remote-attachments/`, then run:

```powershell
git add -- backend/README.md backend/openapi/slowtalk-v1.json backend/tests/contract/test_openapi.py
git commit -m "docs(auth): publish username availability contract"
```
