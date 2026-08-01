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

피드 작성은 OCR을 제공하지 않습니다. `/feeds/feedback`은 제목과 본문 텍스트만 받습니다.
