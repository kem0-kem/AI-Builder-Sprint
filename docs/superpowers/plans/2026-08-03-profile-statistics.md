# Profile Statistics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Return live delivered-letter and unique-match counts in the authenticated user's existing profile response.

**Architecture:** Add one focused aggregate helper to the profiles router. It reads `Letter` and `MatchHistory` with scalar SQL subqueries, and `serialize_profile` inserts the resulting values into the unchanged API response shape.

**Tech Stack:** Python 3.12, FastAPI, SQLAlchemy 2 async ORM, pytest, HTTPX

## Global Constraints

- `sentLetters` counts only authored letters with a non-null recipient.
- Personal saved letters created with `match: false` do not count as sent.
- `receivedLetters` counts letters whose recipient is the current user.
- `matchCount` counts canonical `match_history` rows involving the current user.
- Existing profile endpoints and response field names remain unchanged.
- No schema migration, cached counter, or Android change is required.

---

### Task 1: Profile statistics aggregation

**Files:**
- Modify: `backend/tests/profiles/test_profile_api.py`
- Modify: `backend/app/profiles/router.py`

**Interfaces:**
- Consumes: `Letter.sender_id`, `Letter.recipient_id`, `MatchHistory.user_a_id`, `MatchHistory.user_b_id`, and the current `User.id`.
- Produces: `profile_statistics(session: Session, user_id: UUID) -> dict[str, int]`, consumed only by `serialize_profile`.

- [ ] **Step 1: Write the failing API test**

Append imports for `uuid4`, then add a test that checks empty, personal, delivered, received, matched, and unrelated-user counts through the public profile API:

```python
from uuid import uuid4


async def test_profile_statistics_count_only_delivered_letters_and_user_matches(
    client: AsyncClient,
) -> None:
    alice = await signup_as(client, "statistics-alice@example.com", "Alice")
    bob = await signup_as(client, "statistics-bob@example.com", "Bob")

    empty = await client.get("/api/v1/users/me", headers=alice)
    assert empty.status_code == 200
    assert empty.json()["data"]["statistics"] == {
        "sentLetters": 0,
        "receivedLetters": 0,
        "matchCount": 0,
    }

    personal = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": str(uuid4())},
        json={"content": "private note", "match": False},
    )
    assert personal.status_code == 201

    delivered = await client.post(
        "/api/v1/letters",
        headers={**alice, "Idempotency-Key": str(uuid4())},
        json={"content": "hello", "match": True},
    )
    assert delivered.status_code == 201

    # Register the unrelated user after matching so Bob is the only candidate.
    carol = await signup_as(client, "statistics-carol@example.com", "Carol")

    alice_profile = await client.get("/api/v1/users/me", headers=alice)
    bob_profile = await client.get("/api/v1/users/me", headers=bob)
    carol_profile = await client.get("/api/v1/users/me", headers=carol)

    assert alice_profile.json()["data"]["statistics"] == {
        "sentLetters": 1,
        "receivedLetters": 0,
        "matchCount": 1,
    }
    assert bob_profile.json()["data"]["statistics"] == {
        "sentLetters": 0,
        "receivedLetters": 1,
        "matchCount": 1,
    }
    assert carol_profile.json()["data"]["statistics"] == {
        "sentLetters": 0,
        "receivedLetters": 0,
        "matchCount": 0,
    }
```

Because the existing `signup` helper has a fixed identity, replace it with a parameterized helper and update its current caller:

```python
async def signup_as(
    client: AsyncClient,
    email: str = "profile@example.com",
    nickname: str = "Profile",
) -> dict[str, str]:
    response = await client.post(
        "/api/v1/auth/signup",
        json={"email": email, "password": "strong-pass", "nickname": nickname},
    )
    return {"Authorization": f"Bearer {response.json()['data']['accessToken']}"}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run from `backend`:

```powershell
python -m pytest tests/profiles/test_profile_api.py::test_profile_statistics_count_only_delivered_letters_and_user_matches -q
```

Expected: FAIL because all three profile statistics are still fixed at zero.

- [ ] **Step 3: Implement the aggregate helper**

Update `backend/app/profiles/router.py` imports:

```python
from uuid import UUID

from sqlalchemy import delete, func, or_, select

from app.letters.models import Letter
from app.matching.models import MatchHistory
```

Add the helper before `serialize_profile`:

```python
async def profile_statistics(session: Session, user_id: UUID) -> dict[str, int]:
    sent_letters = (
        select(func.count())
        .select_from(Letter)
        .where(Letter.sender_id == user_id, Letter.recipient_id.is_not(None))
        .scalar_subquery()
    )
    received_letters = (
        select(func.count())
        .select_from(Letter)
        .where(Letter.recipient_id == user_id)
        .scalar_subquery()
    )
    match_count = (
        select(func.count())
        .select_from(MatchHistory)
        .where(
            or_(
                MatchHistory.user_a_id == user_id,
                MatchHistory.user_b_id == user_id,
            )
        )
        .scalar_subquery()
    )
    row = (
        await session.execute(
            select(
                sent_letters.label("sent_letters"),
                received_letters.label("received_letters"),
                match_count.label("match_count"),
            )
        )
    ).one()
    return {
        "sentLetters": int(row.sent_letters or 0),
        "receivedLetters": int(row.received_letters or 0),
        "matchCount": int(row.match_count or 0),
    }
```

Call it once in `serialize_profile` and replace the fixed object:

```python
statistics = await profile_statistics(session, user.id)
```

```python
"statistics": statistics,
```

- [ ] **Step 4: Run the focused profile tests**

Run from `backend`:

```powershell
python -m pytest tests/profiles/test_profile_api.py -q
```

Expected: all profile tests PASS.

- [ ] **Step 5: Run formatting and static checks**

Run from `backend`:

```powershell
python -m ruff check app/profiles/router.py tests/profiles/test_profile_api.py
python -m ruff format --check app/profiles/router.py tests/profiles/test_profile_api.py
```

Expected: both commands exit successfully. If the format check reports changes, run `python -m ruff format` on the same two files and repeat both checks.

- [ ] **Step 6: Run the full backend regression suite**

Run from `backend`:

```powershell
python -m pytest -q
```

Expected: all backend tests PASS. If optional external-service tests are environment-gated, record the exact skipped tests; do not treat unrelated missing credentials as a product failure.

- [ ] **Step 7: Commit the implementation**

```powershell
git add -- backend/app/profiles/router.py backend/tests/profiles/test_profile_api.py
git commit -m "feat: calculate live profile statistics"
```
