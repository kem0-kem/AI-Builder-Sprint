---
name: slowtalk-development
description: Implement, diagnose, test, and document SlowTalk features across Android Jetpack Compose, FastAPI, PostgreSQL/pgvector, Upstage, and Railway. Use when changing app/backend contracts, AI OCR or feedback or matching or moderation, local startup, deployment readiness, or regression fixes in this repository.
---

# SlowTalk Development

## Establish context

1. Read `AGENTS.md` and the relevant specification or plan under `docs/superpowers`.
2. Inspect `git status`; preserve unrelated changes and emulator artifacts.
3. Map the request through the Android UI/view model/repository, Retrofit contract, FastAPI route/service/schema, database, and tests.
4. Identify whether the path uses a real provider, a mock, or an explicitly supported development fallback.

## Implement safely

- Keep Android serialization models and backend schemas in sync.
- Preserve authentication, common response envelopes, timezone-aware API timestamps, and client-side relative-time formatting.
- Route OCR through Upstage Document Parse, contextual feedback and moderation through Solar Chat, and semantic matching through Solar Embedding.
- Keep model identifiers, thresholds, and rollout modes environment-configurable.
- Never log provider authorization, complete user text, source images, or embedding vectors.
- Update `AI_USAGE_EVIDENCE.md` when AI behavior, prompt policy, provider boundaries, or validation changes.

## Verify the affected surface

For backend work, run targeted tests first, then:

```powershell
cd backend
ruff check .
mypy app
pytest -q
python scripts/export_openapi.py openapi/slowtalk-v1.json
```

For Android work, run:

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

For an API contract change, verify the FastAPI contract test, exported OpenAPI document, Retrofit interface, serialization model, and consuming view model. For Upstage behavior, cover normal output, malformed output, timeout/retry, missing configuration, and privacy-safe logs.

## Deploy only with authority

Deploy to Railway only when the user explicitly requests it. Confirm migrations, `/api/v1/health`, and mode-dependent `/api/v1/ready` after deployment. Keep all secrets in local `.env` or Railway Variables, never in Git or the APK.

## Report

State the changed behavior, files, tests actually run, any mocked or fallback AI behavior, and remaining deployment or environment assumptions.
