# SlowTalk API

FastAPI와 PostgreSQL로 구현한 SlowTalk 백엔드입니다. Android 앱 코드는 변경하지 않습니다.

## 로컬 실행

Docker Desktop과 Python 3.11 이상을 설치한 뒤 PowerShell에서 실행합니다.

```powershell
Copy-Item .env.example .env
python -m pip install -e ".[dev]"
.\scripts\run-local.ps1
```

스크립트는 PostgreSQL을 시작하고 컨테이너가 준비될 때까지 기다린 다음 Alembic
마이그레이션을 적용하고 FastAPI 개발 서버를 실행합니다. `.env`가 없으면 예제 파일을
복사하는 안내만 출력하고 종료하며, 비밀 값을 자동 생성하거나 Git에 추가하지 않습니다.

- API: `http://localhost:8000/api/v1`
- Swagger UI: `http://localhost:8000/docs`
- Liveness: `http://localhost:8000/api/v1/health`
- Readiness: `http://localhost:8000/api/v1/ready`
- 기본 `.env.example`은 matching과 moderation을 비활성화하므로 외부 AI 키 없이 준비 상태가 됩니다.
- Readiness는 데이터베이스에 `SELECT 1`을 실행하며 연결할 수 없으면 `503`과
  `databaseReady=false`를 반환합니다. 데이터베이스 주소나 오류 원문은 응답에 포함하지 않습니다.

서버를 중지하려면 실행 중인 PowerShell에서 `Ctrl+C`를 누릅니다. PostgreSQL 컨테이너도
중지하려면 다른 PowerShell에서 `docker compose stop postgres`를 실행합니다.

## 품질 검사

```bash
ruff check .
mypy app
pytest -q
python scripts/export_openapi.py openapi/slowtalk-v1.json
```

## Railway deployment

1. Create a Railway Postgres service, then create a backend service from this repository.
2. Set the backend service **Root Directory** to `/backend` and its config file path to
   `/backend/railway.toml`. The included pre-deploy command runs `alembic upgrade head`.
3. Reference the Postgres service's `DATABASE_URL` in the backend service variables.
   Railway's normal `postgresql://` URL is converted to the async driver form automatically.
4. Set `APP_ENVIRONMENT=production`, a new 32+ character `JWT_SECRET`, and the required
   moderation/matching variables. Do not copy the local `.env` file to Railway.
5. Generate a public domain and check `/api/v1/health` and `/api/v1/ready`.

For a browser frontend, set `CORS_ORIGINS` to a JSON array such as
`["https://your-frontend.up.railway.app"]`. Android does not require CORS. Build a release
APK with `-PAPI_BASE_URL=https://your-api.up.railway.app/api/v1/`; do not bake secrets into
the APK.

## Moderation rollout

- `MODERATION_MODE=disabled`: 외부 AI 호출 없이 콘텐츠 CRUD를 실행합니다. 로컬 개발의
  기본값이며 `/ready`에서 완전한 설정으로 취급합니다. production에서는 설정 검증 단계에서
  거부됩니다.
- `MODERATION_MODE=shadow`: when `UPSTAGE_API_KEY`, `UPSTAGE_CHAT_MODEL`,
  `MODERATION_ALLOW_CONFIDENCE`, and `MODERATION_BLOCK_CONFIDENCE` are configured,
  classifies content and records only bounded metrics without persisting moderation
  submissions, decisions, retries, encrypted commands, or replay state; all outcomes
  preserve the existing successful API behavior. Blank optional template values make
  moderation configuration incomplete; malformed values fail settings validation.
- `MODERATION_MODE=enforce`: requires all provider, encryption, confidence, and internal
  token settings; pending content returns `202` and blocked content returns `422`.
- `/api/v1/health` is liveness-only. `/api/v1/ready` returns `503` when the selected
  moderation mode is incompletely configured. Set
  `ALLOW_DEVELOPMENT_MODERATION_FALLBACK=true` to opt into a `200` fallback only when
  `APP_ENVIRONMENT=development` or `APP_ENVIRONMENT=test`; the flag never bypasses
  incomplete production configuration. Readiness reports only the mode, configuration
  completeness, and fallback status, and does not probe the moderation provider.
- Metrics use only content type, decision, policy category, and bounded latency buckets.

## Semantic matching rollout

- `MATCHING_MODE=disabled` skips the embedding provider probe.
- `MATCHING_MODE=shadow` or `MATCHING_MODE=enforce` requires `UPSTAGE_API_KEY`,
  `UPSTAGE_EMBEDDING_MODEL`, and `MATCH_MIN_SIMILARITY`. Application startup sends one
  fixed Korean query probe to Upstage and requires an exact 1024-dimensional finite
  vector before matching is reported ready.
- Query text uses the `embedding-query` provider alias. Delivered source letters use
  the `embedding-passage` alias and preserve provider batch index ordering.
- `/api/v1/ready` exposes only matching mode, configured model name, expected dimensions,
  and readiness success. This value is the startup contract-check result, not a live
  provider-health signal. Probe text and vector values are never included.
- Roll out in this order: confirm readiness, backfill delivered letters, deploy `shadow`,
  inspect only bounded metrics, then move to `enforce`. Rolling back to `disabled` keeps
  profile matching available and retains projections for a later retry.

### Embedding backfill

```bash
python scripts/backfill_match_embeddings.py --limit 100
python scripts/backfill_match_embeddings.py --after 00000000-0000-0000-0000-000000000000 --limit 100
```

Pass the previous `nextCursor` as `--after`, stop when `exhausted` is true, and retry a
failed page with the same input cursor. Pages are bounded to 500 letters and provider
requests to 32 passages; existing active-model projections are skipped.

## Username compatibility rollout

- `username` is optional during the compatibility rollout so existing signup clients
  continue to work without changes.
- `GET /api/v1/auth/check-username` is an advisory availability check; the database
  unique index remains authoritative if concurrent signup requests race.
- Usernames must contain 3-30 ASCII letters, digits, or underscores. Accepted values
  are normalized to lowercase before storage and comparison.

피드 작성은 OCR을 제공하지 않습니다. `/feeds/feedback`은 제목과 본문 텍스트만 받습니다.
