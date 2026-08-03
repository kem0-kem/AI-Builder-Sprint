# Profile Statistics Design

## Goal

Replace the fixed zero values in the authenticated user's profile statistics with counts derived from persisted letter delivery and match history data.

## Scope

The existing `GET /api/v1/users/me`, `PATCH /api/v1/users/me`, and `PUT /api/v1/users/me/interests` response contract remains unchanged. Only the values inside `data.statistics` change from fixed placeholders to live database aggregates.

The statistics use these definitions:

- `sentLetters`: letters authored by the current user that have a non-null recipient. Personal saved letters created with `match: false` are excluded.
- `receivedLetters`: letters whose recipient is the current user.
- `matchCount`: unique users matched with the current user, represented by rows in `match_history` where the current user is either member of the canonical pair.

## Architecture

Profile serialization remains responsible for assembling the response. A focused asynchronous helper in the profiles module queries the authoritative `letters` and `match_history` tables and returns the three counts. `serialize_profile` combines those counts with the existing identity, interests, and region data.

The implementation performs aggregate SQL queries rather than loading ORM rows into Python. It does not add cached or denormalized counters, migrations, or new API endpoints.

## Data Flow

1. An authenticated profile endpoint resolves the current `User`.
2. `serialize_profile` loads interests and requests statistics for `user.id`.
3. The statistics query counts delivered outgoing letters, incoming letters, and match-history rows involving the user.
4. The API returns the existing camel-case statistics object.

When no matching rows exist, every count is `0`.

## Data Integrity

`Letter.recipient_id IS NOT NULL` distinguishes delivered letters from personal saved letters. Incoming letters are counted directly by `recipient_id`, so the same delivered letter contributes once to the sender and once to the recipient.

`MatchHistory` stores each canonical user pair once under `uq_match_history_canonical_pair`. Counting rows involving the current user therefore equals the number of unique matched people. No additional `DISTINCT` operation is required.

## Error Handling

Statistics are read in the same database session as the rest of the profile. Database failures follow the application's existing error handling; counts are not silently replaced with zeros on query failure. Empty aggregate results are normalized to integer zero.

## Testing

Profile API tests will verify:

- a new user receives zero for all three statistics;
- a personal saved letter does not increase `sentLetters`;
- a delivered letter increases the sender's sent count and recipient's received count;
- a successful match increases both users' `matchCount`;
- each user only sees counts derived from records involving that user;
- the existing profile fields and response shape remain intact.

The focused profile test module will run first, followed by the full backend test suite if the local environment supports it.
