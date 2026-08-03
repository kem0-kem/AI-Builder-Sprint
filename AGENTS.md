# 마음잇기 Repository Instructions

## Scope

These instructions apply to the entire repository. 마음잇기 consists of an Android Jetpack Compose client, a FastAPI backend, PostgreSQL/pgvector, and Upstage AI integrations deployed on Railway.

## Before changing code

1. Read the relevant files under `docs/superpowers/specs` and `docs/superpowers/plans`.
2. Inspect `git status` and preserve unrelated or untracked user work.
3. Trace a feature across the Android API model, backend route/schema/service, persistence layer, and tests before changing a contract.
4. Use `.agents/skills/slowtalk-development/SKILL.md` for the repository workflow.

## Implementation rules

- Keep Android and FastAPI request/response contracts synchronized.
- Preserve the common API envelope and authentication behavior.
- Treat all timestamps as timezone-aware UTC on the wire and format relative time only in the client.
- Use Upstage Document Parse for image OCR, Solar Chat for contextual writing feedback and moderation, and Solar Embedding for semantic matching.
- Keep provider model names and rollout modes configurable through environment variables.
- Do not add fake production AI results. Deterministic fallback is allowed only where the existing development/test contract explicitly supports it.
- Update `AI_USAGE_EVIDENCE.md` when the product's AI behavior, provider, prompt policy, or validation changes materially.

## Secrets and user data

- Never commit `.env`, `local.properties`, API keys, JWT secrets, tokens, keystores, or Railway credentials.
- Do not print authorization headers, full user content, OCR source images, embeddings, or provider responses in logs.
- Use `backend/.env.example` for variable names and placeholders only.
- Do not place `UPSTAGE_API_KEY` or `API_AUTH_TOKEN` in an APK.

## Verification

Run the smallest relevant checks while iterating, then the affected suite before delivery.

```powershell
cd backend
ruff check .
mypy app
pytest -q
python scripts/export_openapi.py openapi/slowtalk-v1.json

cd ..
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

If an API contract changes, verify both backend contract tests and the Android Retrofit model/call site. If AI behavior changes, verify success, malformed output, timeout/retry, missing configuration, and privacy-safe logging paths.

## Deployment

- Railway deploys are production mutations. Perform them only when the user explicitly requests deployment.
- Railway runs Alembic migrations before starting Uvicorn and checks `/api/v1/health`.
- After deployment, check both `/api/v1/health` and `/api/v1/ready`; readiness depends on the selected moderation and matching modes.
