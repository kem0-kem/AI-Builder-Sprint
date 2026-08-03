# 마음잇기

지역 기반 연결 플랫폼입니다. 손편지 OCR, AI 글쓰기 피드백, 의미 기반 매칭, 익명·모임 대화와 회고를 하나의 Android 앱에서 제공합니다.

- 운영 API: [backend-production-2f6a.up.railway.app](https://backend-production-2f6a.up.railway.app/docs)
- [AI 활용 증빙](AI_USAGE_EVIDENCE.md)
- [자막 포함 시연 영상](docs/demo/slowtalk-demo.mp4)
- [기능 명세](docs/superpowers/specs) / [구현 계획](docs/superpowers/plans)

## Upstage API 활용

| Upstage 기능 | 제품 내 용도 | 연결 지점 |
| --- | --- | --- |
| Document Parse | 카메라·갤러리에서 가져온 손편지와 회고 이미지 OCR | `POST /v1/document-digitization`, `document-parse`, `ocr=force` |
| Solar Chat | 편지·피드·회고 내용별 AI 글쓰기 피드백과 콘텐츠 안전성 분류 | `POST /v1/chat/completions`, `UPSTAGE_CHAT_MODEL` |
| Solar Embedding | 편지·사용자 프로필을 1,024차원 벡터로 변환해 의미 기반 연결 | `POST /v1/embeddings`, `solar-embedding-2` |

`UPSTAGE_API_KEY`는 GitHub나 APK에 넣지 않고 백엔드 실행 환경에만 주입합니다. 구체적인 요청 흐름, 프롬프트 원칙, fallback 범위와 테스트 근거는 [AI_USAGE_EVIDENCE.md](AI_USAGE_EVIDENCE.md)에 정리되어 있습니다.

## 개발 과정의 AI 활용

Codex를 코드 탐색, Android–FastAPI API 계약 구현, 장애 재현, 회귀 테스트, Railway 배포 점검과 문서화에 활용했습니다. AI가 같은 저장소 규칙과 검증 절차를 반복 적용할 수 있도록 아래 파일도 함께 관리합니다.

- [AGENTS.md](AGENTS.md): 모든 코딩 에이전트가 따르는 아키텍처·보안·검증 규칙
- [CLAUDE.md](CLAUDE.md), [.claude/settings.json](.claude/settings.json): Claude Code용 프로젝트 지침과 공유 권한 설정
- [.agents/skills/slowtalk-development/SKILL.md](.agents/skills/slowtalk-development/SKILL.md): 마음잇기 기능 구현·진단·배포 준비를 위한 저장소 전용 스킬
- [AI_USAGE_EVIDENCE.md](AI_USAGE_EVIDENCE.md): 제품 내 Upstage 사용과 개발 과정 AI 활용 증빙

`.omc`는 이 프로젝트의 개발 과정에서 사용하지 않아 실제 사용하지 않은 설정을 증빙처럼 만들지 않았습니다. 개인 설정인 `.claude/settings.local.json`, 환경 파일, API 키와 서명 키는 추적하지 않습니다.

## 구성

```text
Android (Kotlin, Jetpack Compose)
  └─ HTTPS/JSON → FastAPI on Railway
                    ├─ PostgreSQL 16 + pgvector
                    └─ Upstage Document Parse / Solar Chat / Solar Embedding
```

| 영역 | 실행 환경 |
| --- | --- |
| Android | Kotlin 2.2.10, Jetpack Compose, JDK 17, minSdk 26, target/compile SDK 37 |
| Backend | Python 3.11+, FastAPI, Uvicorn, SQLAlchemy async, Alembic |
| Database | PostgreSQL 16 + pgvector; 로컬 Docker Compose 또는 Railway PostgreSQL |
| 배포 | Railway Dockerfile 배포, pre-deploy Alembic migration, `/api/v1/health` health check |
| AI | Upstage API; API 키와 모델 선택은 백엔드 환경변수로 주입 |

## 로컬 기동

### 1. 백엔드와 DB

필수 도구는 Python 3.11+, Docker Desktop입니다. PowerShell 기준으로 실행합니다.

```powershell
cd backend
Copy-Item .env.example .env
docker compose up -d postgres
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[dev]"
alembic upgrade head
uvicorn app.main:app --reload
```

- API base URL: `http://localhost:8000/api/v1/`
- Swagger UI: `http://localhost:8000/docs`
- 상태 확인: `http://localhost:8000/api/v1/health`

AI 기능을 실제 Upstage API로 시험하려면 `backend/.env`의 `UPSTAGE_API_KEY`와 `UPSTAGE_CHAT_MODEL`을 채웁니다. 키 없이도 일반 API는 실행할 수 있지만 AI 기능 및 readiness 결과는 선택한 모드에 따라 제한됩니다.

### 2. Android 앱

Android Studio에서 SDK 37과 Android Emulator를 설치하고 저장소를 엽니다. Android Studio가 생성한 `local.properties`에 SDK 경로와 로컬 API 주소를 설정합니다.

```properties
sdk.dir=C\:\\Users\\<USER>\\AppData\\Local\\Android\\Sdk
API_BASE_URL=http://10.0.2.2:8000/api/v1/
```

`10.0.2.2`는 Android Emulator에서 호스트 PC를 가리킵니다. 실제 단말에서는 같은 네트워크의 PC 주소를 사용합니다.

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

운영 API를 향한 APK는 다음처럼 빌드합니다.

```powershell
.\gradlew.bat assembleDebug `
  -PAPI_BASE_URL=https://backend-production-2f6a.up.railway.app/api/v1/
```

`API_AUTH_TOKEN`은 개발 호환용 선택값이며 배포 APK에는 넣지 않습니다. 사용자는 앱 로그인으로 받은 토큰을 Android 암호화 저장소에 보관합니다.

## 환경변수와 빌드 속성

실제 비밀값은 커밋하지 말고 로컬은 `backend/.env`, Railway는 서비스 Variables에 설정합니다. 전체 템플릿은 [backend/.env.example](backend/.env.example)에서 확인할 수 있습니다.

### Android Gradle 속성

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `API_BASE_URL` | 예 | `/api/v1/`까지 포함한 백엔드 주소 |
| `API_AUTH_TOKEN` | 아니요 | 개발 호환용 토큰. 운영 APK에는 미포함 |

### 백엔드 기본 설정

| 이름 | 필수 | 기본값/설명 |
| --- | --- | --- |
| `APP_ENVIRONMENT` | 예 | `development`, `test`, `production` |
| `DATABASE_URL` | 예 | PostgreSQL 연결 URL. Railway의 일반 URL도 async 형식으로 정규화 |
| `CORS_ORIGINS` | 웹 클라이언트만 | 허용 origin의 JSON 배열; Android에는 불필요 |
| `JWT_SECRET` | 예 | 최소 32자의 임의 비밀값 |
| `ACCESS_TOKEN_TTL_SECONDS` | 아니요 | 기본 `900` |
| `REFRESH_TOKEN_TTL_SECONDS` | 아니요 | 기본 `2592000` |
| `PORT` | 배포 시 자동 | Railway가 주입하며 기본 fallback은 `8000` |

### Upstage 및 의미 매칭

| 이름 | 필수 | 기본값/설명 |
| --- | --- | --- |
| `UPSTAGE_API_KEY` | AI 기능 사용 시 | 비밀값. 저장소·로그·APK에 포함 금지 |
| `UPSTAGE_BASE_URL` | 아니요 | `https://api.upstage.ai/v1` |
| `UPSTAGE_CHAT_MODEL` | Solar Chat 사용 시 | 글쓰기 피드백·모더레이션에 사용할 운영 모델명 |
| `UPSTAGE_DOCUMENT_MODEL` | 아니요 | `document-parse` |
| `UPSTAGE_EMBEDDING_MODEL` | 아니요 | `solar-embedding-2` |
| `EMBEDDING_DIMENSIONS` | 아니요 | `1024`; 시작 시 응답 차원을 검증 |
| `MATCHING_MODE` | 아니요 | `disabled`, `shadow`, `enforce` |
| `MATCH_MIN_SIMILARITY` | shadow/enforce 시 | 의미 매칭 최소 유사도 임계값 |

### 콘텐츠 모더레이션

| 이름 | 필수 | 기본값/설명 |
| --- | --- | --- |
| `MODERATION_MODE` | 아니요 | `shadow` 기본; `disabled`, `shadow`, `enforce` |
| `ALLOW_DEVELOPMENT_MODERATION_FALLBACK` | 아니요 | 개발·테스트에서만 fallback 허용, 기본 `false` |
| `MODERATION_ALLOW_CONFIDENCE` | 아니요 | 허용 임계값, 기본 `0.7` |
| `MODERATION_BLOCK_CONFIDENCE` | 아니요 | 차단 임계값, 기본 `0.9` |
| `MODERATION_ENCRYPTION_KEY` | enforce 시 | 내부 명령 암호화 키 |
| `CONTENT_HASH_PEPPER` | enforce 시 | 콘텐츠 해시용 비밀 pepper |
| `INTERNAL_MODERATION_TOKEN` | enforce 시 | 내부 moderation worker 인증 토큰 |

## 테스트

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

Upstage 호출은 provider gateway 단위에서 mock해 요청 모델, 프롬프트 정책, OCR multipart, embedding 차원과 오류 처리를 회귀 검증합니다. 실제 키를 사용하는 smoke test는 로컬 또는 Railway의 비밀 환경에서만 수행합니다.

## Railway 배포

1. Railway PostgreSQL 서비스를 만들고 백엔드 서비스에 `DATABASE_URL` reference를 연결합니다.
2. 서비스 Root Directory를 `/backend`, config path를 `/backend/railway.toml`로 지정합니다.
3. 위 환경변수를 Railway Variables에 등록하고 `APP_ENVIRONMENT=production`으로 설정합니다.
4. GitHub 연동 배포 또는 `railway up`을 실행합니다. 배포 전에 `alembic upgrade head`가 자동 실행됩니다.
5. `/api/v1/health`가 `200`, 선택한 AI 모드까지 준비된 경우 `/api/v1/ready`가 `200`인지 확인합니다.

운영 비밀값은 Railway Variables에서만 관리하며 `.env` 파일을 업로드하지 않습니다.
