# Username Availability Design

## Goal

Add a public username-availability endpoint based on the supplied API reference while
introducing usernames without breaking existing 마음잇기 accounts or clients that still
sign up with only email, password, and nickname.

## Scope

- Add an optional, unique username to `users`.
- Accept an optional username during signup.
- Add `GET /api/v1/auth/check-username?username=...`.
- Return availability through the existing 마음잇기 success envelope.
- Document and test the API, validation, migration, and duplicate-signup behavior.

This change does not replace email login, make username mandatory, or change nickname
semantics. Nickname remains the editable display name.

## Username Contract

- Length: 3 to 30 characters.
- Accepted input characters: ASCII letters, digits, and underscore.
- Canonical form: lowercase.
- Comparisons are case-insensitive because input is normalized before lookup and storage.
- Existing users may have `NULL` username values.

Both signup and the availability endpoint use the same Pydantic validation rule so they
cannot disagree about which usernames are valid. Invalid input receives the project's
standard validation response with HTTP 422.

## API Contract

### Availability

```http
GET /api/v1/auth/check-username?username=testuser
```

The endpoint is public. It performs a normalized exact lookup against `users.username`.

Available response:

```json
{
  "ok": true,
  "data": {
    "available": true
  }
}
```

Already used response:

```json
{
  "ok": true,
  "data": {
    "available": false
  }
}
```

Availability is advisory. Signup still relies on the database uniqueness constraint to
resolve races between a successful check and concurrent account creation.

### Signup

`POST /api/v1/auth/signup` accepts a new optional `username` field. When present, the
server validates, normalizes, and stores it. When absent, the existing signup request
continues to work and the stored username is `NULL`.

If a username uniqueness conflict occurs during signup, the response is HTTP 409 with
error code `USERNAME_ALREADY_EXISTS`. Existing email conflicts continue to use
`EMAIL_ALREADY_EXISTS`. The implementation checks for conflicts before insertion to
produce a specific error and still treats the database unique constraint as the final
concurrency guard.

## Persistence and Migration

Create the next sequential Alembic revision after `0004_matching_vectors`.

- Add nullable `users.username VARCHAR(30)`.
- Add a unique index on `users.username`.
- Do not backfill usernames for existing rows.
- Downgrade removes the unique index before removing the column.

PostgreSQL and SQLite permit multiple `NULL` values in a unique index, preserving legacy
accounts while preventing duplicate non-null usernames.

## Components

- `app/auth/models.py`: add the nullable, indexed username field.
- `app/auth/schemas.py`: define one reusable username type/validation contract and add it
  to signup.
- `app/auth/router.py`: add the public availability lookup and specific signup conflict
  handling.
- `migrations/versions/0005_usernames.py`: evolve the production schema.
- `tests/auth/test_auth_api.py`: cover available, unavailable, normalization, validation,
  legacy signup, username signup, and duplicate signup.
- `tests/contract/test_openapi.py` and `openapi/slowtalk-v1.json`: lock the public contract.

## Data Flow

1. FastAPI validates the query or signup username using the shared schema rule.
2. The value is normalized to lowercase.
3. Availability performs a scalar lookup of `User.id` by the normalized username.
4. Signup checks username and email conflicts, inserts the user, and commits normally.
5. A database unique constraint remains authoritative if concurrent requests race.

## Error Handling and Privacy

- Invalid usernames return HTTP 422 without querying the database.
- Duplicate username signup returns `USERNAME_ALREADY_EXISTS` with HTTP 409.
- Duplicate email signup retains `EMAIL_ALREADY_EXISTS` with HTTP 409.
- The availability response reveals only a boolean and no user profile or identifier.
- Database exceptions are not returned to clients.

## Testing and Verification

Use test-first implementation with focused auth and contract tests, followed by:

```powershell
python -m ruff check app tests
python -m mypy app
python -m pytest -q
```

Regenerate the OpenAPI snapshot with `scripts/export_openapi.py` and verify the Alembic
graph has exactly one head whose revision is `0005_usernames` and whose parent is
`0004_matching_vectors`.
