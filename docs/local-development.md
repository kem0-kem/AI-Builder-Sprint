# SlowTalk 로컬 개발

이 문서는 로컬 풀스택 통합 작업의 고정 기준선과 소스 책임 범위를 기록합니다.

- Android baseline: `476400c6499450ca36d32426e1a35f8561fa3af8` (`origin/main`)
- Backend baseline: `b6e69d9093d303fe0df04317184d9ffc45bf2831` (`origin/codex/semantic-matching-foundation`)
- API prefix: `/api/v1`
- Emulator API URL: `http://10.0.2.2:8000/api/v1/`

## 기준선 책임 범위

- `app/`, Gradle 설정, Android 리소스는 위 Android baseline을 기준으로 유지합니다.
- `backend/`는 위 Backend baseline을 기준으로 유지합니다.
- `integration/local-full-stack` 브랜치는 두 기준선을 로컬에서 함께 실행하기 위한 통합 변경만 담당합니다.
- 이번 기준선 작업에서는 Android 또는 백엔드의 기능 동작을 변경하지 않습니다.
- 토큰, `.env`, `local.properties` 및 외부 서비스 API 키는 Git에 커밋하지 않습니다.

위 SHA는 `2026-08-03`에 `git fetch origin --prune`을 실행한 직후 기록했습니다.

## 백엔드 로컬 실행

필수 도구는 Docker Desktop과 Python 3.11 이상입니다. 저장소 루트에서 다음 명령을
실행합니다.

```powershell
Set-Location backend
Copy-Item .env.example .env
python -m pip install -e ".[dev]"
.\scripts\run-local.ps1
```

`run-local.ps1`은 Docker 엔진과 Python을 확인하고, PostgreSQL 준비 대기, Alembic
마이그레이션, FastAPI 개발 서버 실행을 순서대로 수행합니다. `.env`가 없으면 복사할
명령을 안내하고 종료합니다. `.env`는 Git에서 제외되므로 로컬에서만 관리합니다.

기본 설정은 `MATCHING_MODE=disabled`, `MODERATION_MODE=disabled`입니다. 따라서 Upstage
등 외부 AI 키가 없어도 핵심 API와 아래 상태 확인 경로를 실행할 수 있습니다.

```powershell
Invoke-RestMethod http://localhost:8000/api/v1/health
Invoke-RestMethod http://localhost:8000/api/v1/ready
```

두 응답 모두 `ok = true`여야 하고, readiness의 `data.status`는 `ready`여야 합니다.
Readiness는 실제 데이터베이스에 최소 쿼리를 실행하므로 `data.databaseReady`도 `true`여야
합니다. 연결 실패 시 민감한 연결 문자열 대신 `databaseReady=false`만 반환합니다.
서버는 `Ctrl+C`로 종료하고 PostgreSQL은 필요할 때 `docker compose stop postgres`로
중지합니다.

## Android 로컬 API 연결

Android 에뮬레이터에서 로컬 백엔드에 연결하려면 저장소 루트의 `local.properties`에
다음 값을 추가합니다. 이 파일은 Git에 커밋하지 않습니다.

```properties
API_BASE_URL=http://10.0.2.2:8000/api/v1/
```

`API_BASE_URL`을 생략하거나 빈 값으로 두면 debug 빌드는 위 에뮬레이터 주소를 기본값으로
사용합니다. 실제 Android 기기에서는 `10.0.2.2` 대신 개발 PC의 LAN IPv4 주소를
사용합니다. 예를 들어 PC 주소가 `192.168.0.20`이면 다음과 같이 설정합니다.

```properties
API_BASE_URL=http://192.168.0.20:8000/api/v1/
```

주소는 `/api/v1/`까지 포함해야 합니다. 끝의 `/`는 빌드된 앱에서 자동으로 하나로
정규화됩니다. `-PAPI_BASE_URL=...` Gradle 속성을 함께 지정하면 해당 값이
`local.properties`보다 우선합니다. REST와 WebSocket 연결은 동일한 기준 주소를
사용하며, 로컬 HTTP cleartext 허용은 debug 빌드에만 적용됩니다.

연결 설정을 검증하려면 저장소 루트에서 다음 명령을 실행합니다.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

### 문제 해결

- Docker 오류: Docker Desktop이 실행 중인지 확인하고 `docker info`를 실행합니다.
- 5432 포트 충돌: 기존 PostgreSQL을 중지하거나 `POSTGRES_HOST_PORT`를 변경하고
  `.env`의 `DATABASE_URL` 포트도 동일하게 맞춥니다.
- 마이그레이션 오류: `docker compose logs postgres`로 데이터베이스 로그를 확인한 뒤
  `python -m alembic upgrade head`를 다시 실행합니다.
- AI 기능: 로컬 기본 설정에서는 의도적으로 비활성화됩니다. 핵심 CRUD 실행에 AI 키는
  필요하지 않습니다.
- Android SDK를 찾지 못하는 경우: 기존 `local.properties`의 `sdk.dir`을 유지한 채
  `API_BASE_URL` 줄만 추가하거나 `ANDROID_HOME`을 설치된 SDK 경로로 설정합니다.
- 실제 기기에서 연결되지 않는 경우: PC와 기기가 같은 네트워크인지, FastAPI가
  `0.0.0.0:8000`에서 실행 중인지, Windows 방화벽이 8000 포트를 허용하는지 확인합니다.
