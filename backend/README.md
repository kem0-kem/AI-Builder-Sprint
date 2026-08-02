# SlowTalk API

FastAPI와 PostgreSQL로 구현한 SlowTalk 백엔드입니다. Android 앱 코드는 변경하지 않습니다.

## 로컬 실행

```bash
docker compose up -d postgres
python -m pip install -e ".[dev]"
alembic upgrade head
uvicorn app.main:app --reload
```

- API: `http://localhost:8000/api/v1`
- Swagger UI: `http://localhost:8000/docs`
- 환경 변수는 `.env.example`을 참고합니다.

## 품질 검사

```bash
ruff check .
mypy app
pytest -q
python scripts/export_openapi.py openapi/slowtalk-v1.json
```

## Moderation rollout

- `MODERATION_MODE=shadow`: when `UPSTAGE_API_KEY`, `UPSTAGE_CHAT_MODEL`,
  `MODERATION_ALLOW_CONFIDENCE`, and `MODERATION_BLOCK_CONFIDENCE` are configured,
  classifies content and records only bounded metrics without persisting moderation
  submissions, decisions, retries, encrypted commands, or replay state; all outcomes
  preserve the existing successful API behavior. Blank template values are treated as
  omitted/incomplete configuration; malformed nonblank values fail settings validation.
- `MODERATION_MODE=enforce`: requires all provider, encryption, confidence, and internal
  token settings; pending content returns `202` and blocked content returns `422`.
- `/api/v1/health` is liveness-only. `/api/v1/ready` returns `503` when the selected
  moderation mode is incompletely configured. Set
  `ALLOW_DEVELOPMENT_MODERATION_FALLBACK=true` to opt into a `200` fallback only when
  `APP_ENVIRONMENT=development` or `APP_ENVIRONMENT=test`; the flag never bypasses
  incomplete production configuration. Readiness reports only the mode, configuration
  completeness, and fallback status, and does not probe the moderation provider.
- Metrics use only content type, decision, policy category, and bounded latency buckets.

피드 작성은 OCR을 제공하지 않습니다. `/feeds/feedback`은 제목과 본문 텍스트만 받습니다.
