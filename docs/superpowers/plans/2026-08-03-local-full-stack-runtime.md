# SlowTalk Local Full-Stack Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GitHub `main`의 최신 Android 앱과 `codex/semantic-matching-foundation`의 FastAPI 백엔드를 정렬하여 Android 에뮬레이터에서 인증, 프로필, 피드, 편지, 채팅, 모임, 회고가 실제 PostgreSQL 데이터를 사용하도록 만든다.

**Architecture:** `origin/main`을 Android 기준선으로 사용하고 백엔드 브랜치의 `backend/`만 통합 브랜치로 가져온다. FastAPI의 `/api/v1` 계약을 단일 진실 공급원으로 삼아 Android가 공통 응답 envelope와 UUID 문자열을 해석하도록 변경하며, 로컬 개발 주소는 BuildConfig로 주입한다. 외부 AI 키가 없어도 핵심 CRUD와 채팅은 동작하고, AI/OCR 기능은 명시적인 비활성 상태를 제공한다.

**Tech Stack:** Kotlin, Jetpack Compose, Retrofit, kotlinx.serialization, OkHttp, FastAPI, SQLAlchemy async, PostgreSQL/pgvector 16, Alembic, pytest, JUnit, MockWebServer, Docker Compose

## Global Constraints

- Android 소스 기준은 작업 시작 시점의 `origin/main`이다.
- 백엔드 소스 기준은 작업 시작 시점의 `origin/codex/semantic-matching-foundation/backend`이다.
- Android 에뮬레이터의 기본 API URL은 `http://10.0.2.2:8000/api/v1/`이다.
- 실제 기기에서는 개발 PC의 LAN IPv4 주소를 `API_BASE_URL`로 주입한다.
- 토큰, `.env`, `local.properties`, Upstage API 키는 Git에 커밋하지 않는다.
- API 리소스 ID는 Android에서도 `String` 타입 UUID로 통일한다.
- 백엔드 성공 응답은 항상 `ApiEnvelope<T>(ok, data, error, meta)`로 해석한다.
- 출시 코드에서 `MOCK_MODE` 분기를 사용하지 않는다. 테스트 대역은 생성자 주입으로만 제공한다.
- 로컬 기본값은 `MATCHING_MODE=disabled`, `MODERATION_MODE=disabled`로 설정한다.
- 외부 AI 키가 없을 때 AI/OCR 기능은 가짜 성공 값을 만들지 않고 `FEATURE_UNAVAILABLE` 상태를 표시한다.
- 각 작업은 테스트 통과 후 별도 커밋으로 종료한다.

---

## Planned File Structure

- `backend/`: FastAPI 애플리케이션, 마이그레이션, pytest, Docker Compose의 단일 소유 위치
- `backend/scripts/run-local.ps1`: PostgreSQL, 마이그레이션, API 실행을 안내하고 사전 조건을 검증
- `app/src/main/java/com/apptive/slowtalk/data/auth/AuthSession.kt`: 액세스/리프레시 토큰 저장 및 조회
- `app/src/main/java/com/apptive/slowtalk/data/remote/ApiEnvelope.kt`: 공통 성공/오류 응답 모델
- `app/src/main/java/com/apptive/slowtalk/data/remote/RetrofitClient.kt`: BuildConfig 주소, Bearer 인증, Retrofit/WebSocket 생성
- `app/src/main/java/com/apptive/slowtalk/data/remote/*Api*.kt`: FastAPI OpenAPI와 일치하는 Retrofit 계약
- `app/src/main/java/com/apptive/slowtalk/data/repository/*Repository.kt`: 목업 없는 도메인 데이터 접근
- `app/src/debug/AndroidManifest.xml`: 로컬 HTTP 통신을 debug 빌드에만 허용
- `app/src/test/...`: JSON 계약, 저장소, 인증 인터셉터 단위 테스트
- `docs/local-development.md`: Windows, 에뮬레이터, 실제 기기 실행 및 문제 해결 절차

---

### Task 1: 통합 기준선과 변경 범위 고정

**Files:**
- Create: `docs/local-development.md`
- Import from backend branch: `backend/`
- Preserve from main: `app/`, Gradle files, Android resources

**Interfaces:**
- Consumes: `origin/main`, `origin/codex/semantic-matching-foundation`
- Produces: `integration/local-full-stack` 브랜치와 동일 저장소 안의 Android/백엔드 기준선

- [ ] **Step 1: 원격 상태를 갱신하고 기준 커밋을 기록한다**

```powershell
git fetch origin --prune
git rev-parse origin/main
git rev-parse origin/codex/semantic-matching-foundation
git status --short
```

Expected: 두 SHA가 출력되고, 사용자가 만든 추적 파일 변경은 그대로 보존된다.

- [ ] **Step 2: 최신 Android 기준으로 통합 브랜치를 만든다**

```powershell
git switch -c integration/local-full-stack origin/main
git restore --source origin/codex/semantic-matching-foundation -- backend
```

Expected: `app/`은 `origin/main`, `backend/`는 백엔드 브랜치 내용과 동일하다.

- [ ] **Step 3: 두 기준선의 무결성을 비교한다**

```powershell
git diff --exit-code origin/main -- app
git diff --exit-code origin/codex/semantic-matching-foundation -- backend
```

Expected: 두 명령 모두 exit code 0.

- [ ] **Step 4: 기준 SHA와 책임 범위를 문서에 기록한다**

`docs/local-development.md` 첫 부분에 아래 형식을 실제 SHA로 기록한다.

```markdown
# SlowTalk 로컬 개발

- Android baseline: `<origin/main SHA>`
- Backend baseline: `<origin/codex/semantic-matching-foundation SHA>`
- API prefix: `/api/v1`
- Emulator API URL: `http://10.0.2.2:8000/api/v1/`
```

- [ ] **Step 5: 기준선 통합을 커밋한다**

```powershell
git add backend docs/local-development.md
git commit -m "chore: establish local full-stack baseline"
```

---

### Task 2: 로컬 PostgreSQL과 FastAPI 실행 경로 완성

**Files:**
- Modify: `backend/.env.example`
- Create: `backend/scripts/run-local.ps1`
- Modify: `backend/README.md`
- Test: `backend/tests/test_health.py`

**Interfaces:**
- Consumes: Docker Desktop, Python 3.12, `backend/docker-compose.yml`
- Produces: `http://localhost:8000/api/v1/health`와 `/ready`가 응답하는 로컬 API

- [ ] **Step 1: 로컬 기본 환경값을 명시한다**

`backend/.env.example`에 아래 의미의 값이 정확히 존재하도록 정리한다.

```dotenv
APP_ENVIRONMENT=development
DATABASE_URL=postgresql+asyncpg://slowtalk:slowtalk@localhost:5432/slowtalk
API_PREFIX=/api/v1
JWT_SECRET=local-development-secret-change-before-deploy
MATCHING_MODE=disabled
MODERATION_MODE=disabled
```

- [ ] **Step 2: health/ready 계약 테스트를 작성한다**

```python
def test_health_is_live(client):
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    assert response.json()["ok"] is True


def test_ready_without_external_ai_in_development(client):
    response = client.get("/api/v1/ready")
    assert response.status_code == 200
    assert response.json()["data"]["status"] == "ready"
```

- [ ] **Step 3: 테스트가 현재 설정 차이를 드러내는지 확인한다**

```powershell
Set-Location backend
python -m pytest tests/test_health.py -q
```

Expected: 설정이나 응답 계약이 다르면 구체적인 assertion 실패가 발생한다.

- [ ] **Step 4: 로컬 실행 스크립트를 구현한다**

`backend/scripts/run-local.ps1`은 순서대로 Docker 존재, `.env` 존재, PostgreSQL 기동, 마이그레이션을 확인하고 마지막에 아래 프로세스를 실행한다.

```powershell
docker compose up -d postgres
python -m alembic upgrade head
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

스크립트는 `.env`가 없으면 `.env.example` 복사 명령을 출력하고 종료하며, 비밀값을 자동 생성하거나 Git에 추가하지 않는다.

- [ ] **Step 5: 정적 검사와 백엔드 테스트를 실행한다**

```powershell
python -m ruff check app tests
python -m mypy app
python -m pytest -q
```

Expected: 모두 exit code 0.

- [ ] **Step 6: 백엔드를 직접 기동하고 smoke test를 수행한다**

```powershell
Invoke-RestMethod http://localhost:8000/api/v1/health
Invoke-RestMethod http://localhost:8000/api/v1/ready
```

Expected: 두 요청 모두 `ok = true`.

- [ ] **Step 7: 로컬 백엔드 구성을 커밋한다**

```powershell
git add backend/.env.example backend/scripts/run-local.ps1 backend/README.md backend/tests/test_health.py
git commit -m "chore: add reproducible local backend runtime"
```

---

### Task 3: Android debug 빌드를 로컬 API에 연결

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/RetrofitClient.kt`
- Create: `app/src/debug/AndroidManifest.xml`
- Test: `app/src/test/java/com/apptive/slowtalk/data/remote/RetrofitConfigurationTest.kt`

**Interfaces:**
- Consumes: Gradle property `API_BASE_URL`
- Produces: `RetrofitClient.baseUrl == BuildConfig.API_BASE_URL`, debug 전용 cleartext 연결

- [ ] **Step 1: 실패하는 URL 정규화 테스트를 작성한다**

```kotlin
@Test
fun `base URL gets one trailing slash`() {
    assertEquals(
        "http://10.0.2.2:8000/api/v1/",
        normalizeBaseUrl("http://10.0.2.2:8000/api/v1")
    )
}
```

- [ ] **Step 2: 테스트가 함수 부재로 실패하는지 확인한다**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*RetrofitConfigurationTest*"
```

Expected: `normalizeBaseUrl` 미정의 실패.

- [ ] **Step 3: BuildConfig 기반 URL을 구현한다**

```kotlin
internal fun normalizeBaseUrl(value: String): String =
    value.trim().let { if (it.endsWith('/')) it else "$it/" }

private val baseUrl = normalizeBaseUrl(
    BuildConfig.API_BASE_URL.ifBlank { "http://10.0.2.2:8000/api/v1/" }
)
```

`Retrofit.Builder().baseUrl(baseUrl)`과 WebSocket URL 생성이 같은 값을 사용하게 한다.

- [ ] **Step 4: debug 빌드에만 HTTP를 허용한다**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:usesCleartextTraffic="true" />
</manifest>
```

- [ ] **Step 5: 로컬 주소 주입 방법을 문서화한다**

`local.properties` 예시는 다음과 같이 기록한다.

```properties
API_BASE_URL=http://10.0.2.2:8000/api/v1/
```

- [ ] **Step 6: 단위 테스트와 debug 빌드를 검증한다**

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Expected: 둘 다 `BUILD SUCCESSFUL`.

- [ ] **Step 7: 연결 구성을 커밋한다**

```powershell
git add app/build.gradle.kts app/src/main/java/com/apptive/slowtalk/data/remote/RetrofitClient.kt app/src/debug app/src/test docs/local-development.md
git commit -m "fix: connect debug app to local API"
```

---

### Task 4: 공통 envelope와 UUID 계약 적용

**Files:**
- Create: `app/src/main/java/com/apptive/slowtalk/data/remote/ApiEnvelope.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/*Api*.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/Models.kt`
- Test: `app/src/test/java/com/apptive/slowtalk/data/remote/ApiEnvelopeSerializationTest.kt`

**Interfaces:**
- Produces: `ApiEnvelope<T>`, `ApiMeta`, 모든 네트워크 ID의 `String` UUID

- [ ] **Step 1: 백엔드 예시 응답을 파싱하는 실패 테스트를 작성한다**

```kotlin
@Test
fun `feed page envelope decodes UUID items`() {
    val body = """{"ok":true,"data":[{"id":"9a4c3d88-5ac9-4a63-9e77-fbb74e33a610"}],"error":null,"meta":{"hasNext":false}}"""
    val decoded = json.decodeFromString<ApiEnvelope<List<IdFixture>>>(body)
    assertTrue(decoded.ok)
    assertEquals("9a4c3d88-5ac9-4a63-9e77-fbb74e33a610", decoded.data!!.single().id)
}
```

- [ ] **Step 2: 테스트 실패를 확인한다**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ApiEnvelopeSerializationTest*"
```

- [ ] **Step 3: 공통 모델을 구현한다**

```kotlin
@Serializable
data class ApiEnvelope<T>(
    val ok: Boolean,
    val data: T? = null,
    val error: ApiErrorDto? = null,
    val meta: ApiMeta? = null
)

@Serializable data class ApiErrorDto(val code: String, val message: String)
@Serializable data class ApiMeta(val nextCursor: String? = null, val hasNext: Boolean? = null)
```

- [ ] **Step 4: Retrofit 반환형을 envelope로 변경한다**

예: `suspend fun getFeeds(...): ApiEnvelope<List<FeedTimelineDto>>`. `feedId`, `commentId`, `chatRoomId`, `messageId`, `letterId`, `meetingId`, `categoryId`, `interestId`는 모두 `String`으로 변경한다.

- [ ] **Step 5: DTO 필드 이름을 OpenAPI alias와 맞춘다**

백엔드 응답의 `id`, `author`, `category`, `createdAt`, `isMine`을 실제 JSON 그대로 모델링하고, 화면 모델 변환은 repository/facade에 둔다.

- [ ] **Step 6: 전체 JSON 계약 테스트와 컴파일을 수행한다**

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

- [ ] **Step 7: 공통 계약을 커밋한다**

```powershell
git add app/src/main app/src/test
git commit -m "refactor: align Android API envelope and UUID contracts"
```

---

### Task 5: 실제 인증 세션과 Bearer 헤더 구현

**Files:**
- Create: `app/src/main/java/com/apptive/slowtalk/SlowTalkApplication.kt`
- Create: `app/src/main/java/com/apptive/slowtalk/data/auth/AuthSession.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/RetrofitClient.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/repository/AuthRepository.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/ui/auth/AuthViewModel.kt`
- Test: `app/src/test/java/com/apptive/slowtalk/data/auth/AuthInterceptorTest.kt`

**Interfaces:**
- Produces: `AuthSession.save(accessToken, refreshToken)`, `AuthSession.clear()`, `AuthSession.accessToken`

- [ ] **Step 1: 인증 헤더 실패 테스트를 작성한다**

MockWebServer에서 로그인 전 요청에는 헤더가 없고, 로그인 후 요청에는 아래 값이 있어야 한다.

```kotlin
assertEquals("Bearer access-123", recordedRequest.getHeader("Authorization"))
```

- [ ] **Step 2: 테스트 실패를 확인한다**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*AuthInterceptorTest*"
```

- [ ] **Step 3: 프로세스 내 토큰과 전용 SharedPreferences 저장소를 구현한다**

```kotlin
object AuthSession {
    @Volatile var accessToken: String? = null
        private set
    fun initialize(context: Context) { /* 저장된 세션 복원 */ }
    fun save(accessToken: String, refreshToken: String) { /* 메모리와 저장소 갱신 */ }
    fun clear() { /* 메모리와 저장소 삭제 */ }
}
```

- [ ] **Step 4: Application에서 세션을 초기화한다**

```xml
<application android:name=".SlowTalkApplication" ... />
```

- [ ] **Step 5: OkHttp 인증 인터셉터를 추가한다**

```kotlin
val token = AuthSession.accessToken
val request = if (token == null) chain.request() else
    chain.request().newBuilder().header("Authorization", "Bearer $token").build()
chain.proceed(request)
```

- [ ] **Step 6: AuthRepository의 `MOCK_MODE`를 제거한다**

로그인 성공 시 envelope의 토큰을 `AuthSession.save`, 로그아웃 성공 또는 로컬 세션 삭제 시 `AuthSession.clear`를 호출한다. 앱 시작 화면은 저장된 세션 유무로 결정한다.

- [ ] **Step 7: 실제 로컬 인증을 smoke test한다**

회원가입 → 로그인 → `/users/me` 200 → 로그아웃 → `/users/me` 401 순서로 확인한다.

- [ ] **Step 8: 인증 구현을 커밋한다**

```powershell
git add app/src/main app/src/test
git commit -m "feat: persist local authentication session"
```

---

### Task 6: 프로필, 지역, 관심사를 실제 API로 전환

**Files:**
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/ProfileApi.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/ProfileDto.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/RegionApi.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/InterestApi.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/repository/ProfileRepository.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/repository/RegionRepository.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/repository/InterestRepository.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/ui/profile/ProfileViewModel.kt`
- Test: `app/src/test/java/com/apptive/slowtalk/data/repository/ProfileRepositoryTest.kt`

**Interfaces:**
- Consumes: `ApiEnvelope<T>`, `AuthSession`
- Produces: 코드 기반 지역 선택과 UUID 관심사 교체

- [ ] **Step 1: 실제 백엔드 JSON fixture로 저장소 실패 테스트를 작성한다**

프로필의 `interests` 배열, `region.provinceCode`, `districtCode`, `subDistrictCode`가 화면 모델로 변환되는지 검증한다.

- [ ] **Step 2: `MOCK_MODE` 때문에 fixture가 사용되지 않는 실패를 확인한다**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ProfileRepositoryTest*"
```

- [ ] **Step 3: 지역 API 경로를 백엔드와 일치시킨다**

```text
GET /regions/provinces
GET /regions/provinces/{provinceCode}/districts
GET /regions/districts/{districtCode}/sub-districts
```

- [ ] **Step 4: 관심사 교체를 UUID와 PUT으로 변경한다**

```text
GET /interests
PUT /users/me/interests
Body: {"interestIds":["<uuid>"]}
```

- [ ] **Step 5: 세 repository의 목업 분기를 제거한다**

오류는 `Result.failure`로 ViewModel까지 전달하고 저장 실패 시 성공 화면으로 이동하지 않는다.

- [ ] **Step 6: 에뮬레이터에서 영속성을 검증한다**

프로필/지역/관심사 수정 → 앱 강제 종료 → 재실행 → 동일 값 조회를 확인한다.

- [ ] **Step 7: 프로필 도메인을 커밋한다**

```powershell
git add app/src/main app/src/test
git commit -m "feat: connect profile region and interest APIs"
```

---

### Task 7: 피드 API 전체 계약 정렬

**Files:**
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/FeedApiService.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/FeedApi.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/Models.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/MainActivity.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/FeedScreens.kt`
- Test: `app/src/test/java/com/apptive/slowtalk/FeedApiContractTest.kt`

**Interfaces:**
- Produces: UUID 기반 피드 CRUD, 공감, 댓글, 신고

- [ ] **Step 1: 현재 Android 경로가 백엔드 경로와 다른 실패 테스트를 작성한다**

MockWebServer에서 다음 요청을 단언한다.

```text
GET /api/v1/feeds?scope=mine
PUT /api/v1/feeds/{feedId}/like
POST /api/v1/feeds/{feedId}/reports
PATCH /api/v1/comments/{commentId}
DELETE /api/v1/comments/{commentId}
```

- [ ] **Step 2: 경로 테스트 실패를 확인한다**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*FeedApiContractTest*"
```

- [ ] **Step 3: Retrofit 경로, HTTP method, 신고 body를 수정한다**

신고 요청은 다음 모델을 사용한다.

```kotlin
@Serializable data class ReportCreateRequest(val reason: String)
```

- [ ] **Step 4: 목록 pagination envelope를 처리한다**

`scope`, `cursor`, `limit`, `categoryId`를 query로 전달하고 `meta.nextCursor`를 보존한다.

- [ ] **Step 5: 선반영 UI를 서버 성공 이후 반영하도록 변경한다**

삭제·신고·댓글 수정은 서버 성공 후 상태를 변경하며, 실패하면 오류 메시지와 재시도 동작을 제공한다.

- [ ] **Step 6: 실제 로컬 피드 시나리오를 검증한다**

사용자 A 작성 → 사용자 B 조회/공감/댓글 → 사용자 A 수정/삭제 → 재조회 순서로 검증한다.

- [ ] **Step 7: 피드 통합을 커밋한다**

```powershell
git add app/src/main app/src/test
git commit -m "feat: align feed flows with local backend"
```

---

### Task 8: 채팅과 모임을 인증된 UUID 흐름으로 전환

**Files:**
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/ChatApiService.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/ChatApi.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/MeetingApiService.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/MeetingApi.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/ConversationScreens.kt`
- Test: `app/src/test/java/com/apptive/slowtalk/ChatApiContractTest.kt`

**Interfaces:**
- Produces: `/ws/chat-rooms/{roomId}?token=...` WebSocket 연결, 읽음 위치, 후보 토큰 기반 모임 생성

- [ ] **Step 1: WebSocket URL 테스트를 작성한다**

```kotlin
assertEquals(
    "ws://10.0.2.2:8000/api/v1/ws/chat-rooms/$roomId?token=access-123",
    buildChatSocketUrl(baseUrl, roomId, "access-123")
)
```

- [ ] **Step 2: 현재 `/ws/chat/{Int}` 경로로 인해 실패하는지 확인한다**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ChatApiContractTest*"
```

- [ ] **Step 3: UUID 채팅 REST/WebSocket 계약을 적용한다**

REST ID를 `String`으로 변경하고 WebSocket은 `/ws/chat-rooms/{roomId}`와 URL-encoded token query를 사용한다.

- [ ] **Step 4: WebSocket 성공 전송과 ack를 구분한다**

`WebSocket.send()` 반환값만으로 메시지를 성공 처리하지 않는다. 서버 ack 또는 REST 성공을 받은 메시지만 목록에 확정하고, 연결 실패 시 REST 전송으로 fallback한다.

- [ ] **Step 5: 모임 후보 API를 백엔드 계약에 맞춘다**

```text
GET /meeting-invite-candidates
POST /meetings
Body: {"title":"...","description":"...","inviteCandidateIds":["<uuid>"]}
```

- [ ] **Step 6: 두 사용자 채팅을 검증한다**

에뮬레이터 2대에서 사용자 A/B 로그인 → 채팅방 조회 → 상호 전송 → 읽음 처리 → 앱 재실행 후 메시지 유지까지 확인한다.

- [ ] **Step 7: 채팅/모임 통합을 커밋한다**

```powershell
git add app/src/main app/src/test
git commit -m "feat: connect authenticated chat and meetings"
```

---

### Task 9: 편지, 매칭, 회고, AI/OCR의 로컬 동작 정의

**Files:**
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/LetterApiService.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/repository/LetterRepository.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/ReportApi.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/data/repository/ReportRepository.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/LetterScreens.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/ReflectionScreens.kt`
- Modify: `backend/app/ai/router.py`
- Modify: `backend/app/reports/router.py`
- Test: `backend/tests/test_ai_unavailable.py`
- Test: `app/src/test/java/com/apptive/slowtalk/AiAvailabilityTest.kt`

**Interfaces:**
- Produces: 실제 편지/회고 저장, 명시적인 AI 가용성 상태, 선택적 Upstage 연동

- [ ] **Step 1: 키가 없을 때의 백엔드 계약 테스트를 작성한다**

```python
def test_ai_endpoint_reports_unavailable_without_provider(client, auth_headers):
    response = client.post(
        "/api/v1/letters/feedback",
        headers=auth_headers,
        json={"content": "충분히 긴 편지 내용입니다."},
    )
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "FEATURE_UNAVAILABLE"
```

- [ ] **Step 2: 가짜 성공을 반환하는 Android 테스트가 실패하도록 작성한다**

`LetterRepository`와 `ReportRepository`가 고정 샘플 대신 503 오류를 `AiUnavailable` UI 상태로 변환하는지 검증한다.

- [ ] **Step 3: 편지와 회고의 기본 CRUD 목업을 제거한다**

AI 키와 무관하게 편지 생성/목록/상세, 회고 생성/목록/상세가 PostgreSQL에 저장되게 한다.

- [ ] **Step 4: AI/OCR 가용성 UI를 구현한다**

키가 없으면 AI 카드와 OCR 버튼에 “로컬 환경에서 AI 제공자 키가 설정되지 않았습니다”를 표시하고 저장 기능은 계속 사용할 수 있게 한다.

- [ ] **Step 5: Upstage 키가 있을 때만 provider 기능을 검증한다**

```powershell
$env:UPSTAGE_API_KEY="<session-only-key>"
python -m pytest tests/test_ai_integration.py -m integration -q
```

Expected: 키가 제공된 환경에서만 integration test 실행. 키 문자열은 로그와 파일에 남기지 않는다.

- [ ] **Step 6: 매칭 결과가 실제 채팅방 ID를 반환하도록 연결한다**

편지 생성 응답에서 `matchStatus`와 `chatRoomId: String?`를 해석하고, 채팅방 ID가 있을 때만 `Screen.Chat`으로 이동한다. 고정 제목만 가진 `Screen.Chat("익명의 이웃 05")` 이동은 제거한다.

- [ ] **Step 7: 도메인 테스트를 실행한다**

```powershell
Set-Location backend
python -m pytest -q
Set-Location ..
.\gradlew.bat testDebugUnitTest
```

- [ ] **Step 8: 편지/회고/AI 상태를 커밋한다**

```powershell
git add app/src/main app/src/test backend/app backend/tests
git commit -m "feat: make letter and reflection flows locally reliable"
```

---

### Task 10: 전체 로컬 검증과 인계 문서 완성

**Files:**
- Modify: `docs/local-development.md`
- Create: `docs/local-smoke-test.md`
- Modify: `README.md`
- Test: all backend and Android test suites

**Interfaces:**
- Produces: 새 개발자가 문서만 보고 30분 이내에 로컬 앱을 실행할 수 있는 절차

- [ ] **Step 1: 깨끗한 로컬 설치 절차를 문서화한다**

문서 순서를 다음으로 고정한다.

```text
1. Docker Desktop 시작
2. backend/.env.example을 backend/.env로 복사
3. Python 가상환경 생성 및 pip install -e ".[dev]"
4. PostgreSQL 기동 및 Alembic 마이그레이션
5. FastAPI 0.0.0.0:8000 기동
6. local.properties에 API_BASE_URL 설정
7. Android 에뮬레이터에서 debug 앱 실행
```

- [ ] **Step 2: 기능별 smoke-test 체크리스트를 작성한다**

```markdown
- [ ] 회원가입/로그인/재실행 세션 복원
- [ ] 프로필/지역/관심사 저장 후 재조회
- [ ] 피드 작성/조회/수정/삭제/공감/댓글/신고
- [ ] 편지 작성/목록/상세
- [ ] 모임 생성과 두 사용자 채팅/읽음 처리
- [ ] 회고 저장/재조회
- [ ] AI 키 없음 상태에서 명확한 안내와 핵심 기능 유지
```

- [ ] **Step 3: 백엔드 최종 검증을 실행한다**

```powershell
Set-Location backend
python -m ruff check app tests
python -m mypy app
python -m pytest -q
python scripts/export_openapi.py openapi/slowtalk-v1.json
```

Expected: 모두 성공하고 생성된 OpenAPI가 커밋된 계약과 일치한다.

- [ ] **Step 4: Android 최종 검증을 실행한다**

```powershell
Set-Location ..
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

Expected: 모두 `BUILD SUCCESSFUL`.

- [ ] **Step 5: 수동 E2E를 수행한다**

Docker DB를 비운 새 환경에서 smoke-test 체크리스트를 완료하고, 실패한 단계는 커밋 전에 수정한다. 두 사용자 검증에는 서로 다른 계정과 에뮬레이터를 사용한다.

- [ ] **Step 6: 최종 문서와 검증 결과를 커밋한다**

```powershell
git add README.md docs backend/openapi/slowtalk-v1.json
git commit -m "docs: add verified local full-stack workflow"
```

---

## Completion Gate

다음 조건이 모두 충족되어야 “로컬 정상 동작”으로 판정한다.

1. 새 PostgreSQL 볼륨에서 마이그레이션과 FastAPI 기동이 한 번에 성공한다.
2. Android debug 앱이 `api.example.com`을 참조하지 않는다.
3. 저장소에 `MOCK_MODE = true`가 남아 있지 않는다.
4. 보호 API 요청에 실제 로그인 토큰이 포함된다.
5. Android와 백엔드가 UUID, endpoint, HTTP method, envelope에서 일치한다.
6. 앱 재실행 후 프로필, 피드, 편지, 회고, 채팅 데이터가 유지된다.
7. 두 계정 사이의 채팅과 읽음 처리가 실제로 동기화된다.
8. AI 키가 없어도 핵심 기능은 정상이며 가짜 AI 결과를 표시하지 않는다.
9. backend 검사, Android unit test, lint, debug build가 모두 통과한다.

