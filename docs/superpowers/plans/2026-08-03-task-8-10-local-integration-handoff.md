# SlowTalk Local Full-Stack Integration Task 8-10 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로컬 PostgreSQL/FastAPI와 Android 에뮬레이터 사이에서 채팅·모임, 편지·매칭·회고, 선택적 AI/OCR을 실제 데이터로 연결하고 전체 자동 검증과 두 사용자 E2E를 통과시킨다.

**Architecture:** 이 문서는 의미론 매칭 백엔드의 기존 “Task 8”과 관계없는 **local full-stack integration Task 8-10** 인계 문서다. FastAPI `/api/v1`와 `backend/openapi/slowtalk-v1.json`을 REST 계약의 기준으로 삼고, Android는 `ApiEnvelope<T>`, UUID 문자열, `AuthSession`의 Bearer 토큰을 사용한다. 채팅 메시지 저장은 REST 201 응답을 authoritative 결과로 삼고 WebSocket은 인증된 실시간 수신 채널로 사용하며, AI 키가 없는 로컬 환경에서는 저장·조회는 유지하고 AI/OCR만 503 `FEATURE_UNAVAILABLE`로 비활성화한다.

**Tech Stack:** Kotlin 2.2, Jetpack Compose, Retrofit 2.11, OkHttp 4.12, kotlinx.serialization, JUnit 4, MockWebServer, Python 3.11+, FastAPI, SQLAlchemy async, PostgreSQL/pgvector, pytest, Ruff, mypy, Alembic

## Global Constraints

- 구현 브랜치는 `integration/local-full-stack`이며 Task 1-7이 완료된 커밋 위에서 시작한다.
- 로컬 Android API 주소는 `http://10.0.2.2:8000/api/v1/`이고 WebSocket 주소는 `ws://10.0.2.2:8000/api/v1/ws/chat-rooms/{roomId}?token={urlEncodedAccessToken}`이다.
- 모든 backend UUID는 Android에서 `String`으로 표현한다. `Int` ID로 되돌리지 않는다.
- 보호 REST 요청은 `AuthSession.accessToken`을 `Authorization: Bearer <token>`으로 전송한다. WebSocket은 같은 access token을 URL-encoded query로 전송한다.
- 성공 REST 응답은 `ApiEnvelope<T>`로 역직렬화하고 `ok=false`, `data=null`, HTTP 4xx/5xx를 성공 화면으로 취급하지 않는다.
- 메시지는 REST 201 응답을 받은 뒤에만 확정한다. `WebSocket.send()`의 Boolean 값이나 현재 서버의 echo ack는 저장 성공 증거가 아니다.
- 모임 생성에는 사용자 ID가 아니라 `GET /meeting-invite-candidates`가 발급한 15분 유효 `candidateId` UUID를 사용한다.
- 외부 AI 키가 없을 때 AI/OCR은 가짜 텍스트를 반환하지 않는다. HTTP 503과 `error.code == "FEATURE_UNAVAILABLE"`을 반환하고 편지·회고 저장·목록·상세는 계속 동작한다.
- 민감한 access/refresh token과 AI 키를 BODY 로그, 예외 문자열, 테스트 fixture, Git 추적 파일에 기록하지 않는다.
- `.codex-remote-attachments/`, `.android-sdk/`, `.worktrees/`, `backend/.env`, `local.properties`는 커밋하지 않는다.

## 작업 경계

- Task 8 담당 범위는 chat REST/WebSocket과 meeting REST/UI뿐이다. auth/session/interceptor, profile/region/interest, feed/comment 코드는 Task 5-7 소유이므로 Task 8에서 수정하지 않는다.
- Task 9 담당 범위는 letter/matching/report/AI/OCR REST와 관련 Android repository/ViewModel/UI뿐이다. auth/region/feed 문제를 Task 9 커밋에 섞지 않는다.
- Task 5-7에서 남은 auth/region/feed 계약 결함은 해당 선행 작업 담당자가 고친 뒤 이 문서의 Gate 3-5를 다시 통과해야 한다. 특히 feed 상세 응답에 댓글이 포함된다고 가정하지 않고 `GET /feeds/{feedId}/comments`; 생성은 `POST /feeds/{feedId}/comments`; 수정·삭제·신고는 각각 `PATCH /comments/{commentId}`, `DELETE /comments/{commentId}`, `POST /comments/{commentId}/reports`를 사용하는 것은 Task 7 책임이다.
- HTTP error body의 `ApiEnvelope(error.code, error.message, error.details)` 역직렬화와 HTTP 202 moderation 응답(`moderationStatus=PENDING_REVIEW`, `submissionId`) 처리는 Task 4 보완 책임이다. Task 8/9는 이 공통 error/accepted 결과 타입을 소비하고 자체적으로 중복 파서를 만들지 않는다.

Task 4 보완이 제공해야 하는 공통 결과 경계는 다음 의미를 가져야 한다. 실제 타입명이 이미 확정됐다면 그 타입을 그대로 사용하되 세 상태를 합치지 않는다.

```kotlin
sealed interface ApiCallResult<out T> {
    data class Data<T>(val value: T) : ApiCallResult<T>
    data class PendingReview(val submissionId: String) : ApiCallResult<Nothing>
    data class Failure(val status: Int, val error: ApiErrorDto) : ApiCallResult<Nothing>
}
```

---

## 선행조건: Task 1-7 완료 게이트

Task 8 담당자는 아래 게이트가 모두 통과하기 전에는 구현을 시작하지 않는다. 누락이 있으면 Task 1-7 담당자에게 반환한다.

- [ ] **Gate 1: 현재 브랜치와 작업 트리를 확인한다**

```powershell
git branch --show-current
git status --short
```

Expected: 브랜치가 `integration/local-full-stack`이고, 인계받은 변경 외에 사용자 첨부 파일이나 다른 작업자의 미커밋 코드가 없다.

- [ ] **Gate 2: Task 1-7 결과 커밋을 확인한다**

```powershell
git log --oneline --grep="chore: establish local full-stack baseline"
git log --oneline --grep="chore: add reproducible local backend runtime"
git log --oneline --grep="fix: connect debug app to local API"
git log --oneline --grep="refactor: align Android API envelope and UUID contracts"
git log --oneline --grep="feat: persist local authentication session"
git log --oneline --grep="feat: connect profile region and interest APIs"
git log --oneline --grep="feat: align feed flows with local backend"
```

Expected: 각 명령이 한 줄 이상의 commit SHA와 정확한 제목을 출력한다.

- [ ] **Gate 3: Task 1-7 코드 조건을 검색한다**

```powershell
rg -n "MOCK_MODE\s*=\s*true|api\.example\.com|/ws/chat/" app/src/main
rg -n "object AuthSession|fun save\(|val accessToken" app/src/main/java/com/apptive/slowtalk/data/auth/AuthSession.kt
```

Expected: 첫 번째 명령은 결과가 없고 두 번째 명령은 Task 5의 `AuthSession` 인터페이스를 출력한다.

- [ ] **Gate 4: backend 준비 상태와 Task 1-7 자동 검증을 실행한다**

```powershell
Set-Location backend
docker compose up -d db
python -m alembic upgrade head
python -m pytest tests/auth tests/profiles tests/feeds tests/test_health.py -q
Set-Location ..
.\gradlew.bat testDebugUnitTest --tests "*RetrofitConfigurationTest*" --tests "*ApiEnvelopeSerializationTest*" --tests "*AuthInterceptorTest*" --tests "*ProfileRepositoryTest*" --tests "*FeedApiContractTest*"
```

Expected: Alembic이 head까지 적용되고 backend 테스트가 모두 PASS하며 Gradle이 `BUILD SUCCESSFUL`로 종료한다.

- [ ] **Gate 5: Task 4 error/202와 Task 7 feed-comment 보완을 확인한다**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ApiErrorEnvelopeTest*" --tests "*ModerationAcceptedResponseTest*" --tests "*FeedApiContractTest*"
rg -n 'GET\("feeds/\{feedId\}/comments"\)|PATCH\("comments/\{commentId\}"\)|DELETE\("comments/\{commentId\}"\)|POST\("comments/\{commentId\}/reports"\)' app/src/main/java/com/apptive/slowtalk/data/remote/FeedApiService.kt
```

Expected: error envelope와 202 moderation 테스트가 PASS하고 `FeedApiService`가 backend의 별도 comment endpoint 네 개를 출력한다. 이 테스트 파일이 없거나 endpoint 검색이 누락되면 Task 8 담당자가 대신 고치지 않고 Task 4/7 담당자에게 반환한다.

## Planned File Structure

### Task 8 책임 파일

- `backend/app/chat/connections.py`: room UUID별 `(userId, WebSocket)` 연결과 viewer별 message event broadcast만 담당한다.
- `backend/app/chat/router.py`: REST 저장 후 broadcast하고 WebSocket 인증·연결 생명주기를 처리한다.
- `backend/tests/chat/test_chat_realtime.py`: 저장된 REST 메시지의 broadcast payload와 비참여자 차단을 검증한다.
- `app/src/main/java/com/apptive/slowtalk/data/remote/ChatApiService.kt`: 채팅 REST DTO와 pagination/read 계약을 선언한다.
- `app/src/main/java/com/apptive/slowtalk/data/remote/RetrofitClient.kt`: UUID room ID와 URL-encoded token으로 WebSocket request를 생성한다.
- `app/src/main/java/com/apptive/slowtalk/ChatApi.kt`: REST 확정 전송과 WebSocket event 역직렬화·중복 제거 경계를 제공한다.
- `app/src/main/java/com/apptive/slowtalk/data/remote/MeetingApiService.kt`: 후보 token과 모임 생성 REST 계약을 선언한다.
- `app/src/main/java/com/apptive/slowtalk/MeetingApi.kt`: 후보 ID를 보존하고 생성된 group room ID를 화면에 전달한다.
- `app/src/main/java/com/apptive/slowtalk/ConversationScreens.kt`: 로딩·실패·재시도, pending 전송, 실시간 수신, 읽음 위치를 표시한다.
- `app/src/test/java/com/apptive/slowtalk/ChatApiContractTest.kt`: REST path/body/envelope와 socket URL을 검증한다.
- `app/src/test/java/com/apptive/slowtalk/MeetingApiContractTest.kt`: 후보와 생성 path/body/UUID를 검증한다.

### Task 9 책임 파일

- `backend/app/ai/gateway.py`: provider 미설정 상태와 실제 provider interface를 구분한다.
- `backend/app/ai/upstage_gateway.py`: 설정이 완전할 때만 실제 feedback/OCR 요청을 수행한다.
- `backend/app/ai/router.py`: provider 미설정 및 provider 장애를 표준 API 오류로 변환한다.
- `backend/app/core/config.py`, `backend/app/main.py`: writing provider 설정과 lifespan의 공유 `httpx.AsyncClient`를 관리한다.
- `backend/app/reports/schemas.py`: `analysisId`가 없는 기본 회고 저장 계약을 허용한다.
- `backend/app/reports/router.py`, `backend/app/reports/service.py`: AI 분석 유무와 무관한 회고 저장, 목록, 상세를 구현한다.
- `backend/tests/ai/test_ai_unavailable.py`: key 없는 503 계약과 가짜 응답 부재를 검증한다.
- `backend/tests/ai/test_upstage_writing_gateway.py`: respx로 feedback/OCR provider 요청과 응답 변환을 검증한다.
- `backend/tests/reports/test_report_api.py`: analysis 없는 CRUD와 analysis 있는 단일사용 저장을 모두 검증한다.
- `app/src/main/java/com/apptive/slowtalk/data/remote/LetterApiService.kt`: direction, idempotency, matching, `text`, feedback DTO를 backend와 맞춘다.
- `app/src/main/java/com/apptive/slowtalk/data/repository/LetterRepository.kt`: 목업 없이 실제 CRUD/feedback/OCR 결과를 분리한다.
- `app/src/main/java/com/apptive/slowtalk/data/remote/ReportApi.kt`: 회고 CRUD와 선택적 `analysisId` 계약을 선언한다.
- `app/src/main/java/com/apptive/slowtalk/data/repository/ReportRepository.kt`: AI 실패 후에도 기본 저장이 가능한 흐름을 제공한다.
- `app/src/main/java/com/apptive/slowtalk/ui/letter/LetterViewModel.kt`, `app/src/main/java/com/apptive/slowtalk/ui/reflection/ReflectionViewModel.kt`: `FeatureAvailability.Unavailable`과 core CRUD 실패를 별도 상태로 노출한다.
- `app/src/main/java/com/apptive/slowtalk/LetterScreens.kt`, `app/src/main/java/com/apptive/slowtalk/ReflectionScreens.kt`, `app/src/main/java/com/apptive/slowtalk/MainActivity.kt`: 실제 room ID 이동과 기능 비활성 UI를 구현한다.
- `app/src/test/java/com/apptive/slowtalk/AiAvailabilityTest.kt`: 503 분류와 core 저장 유지 여부를 검증한다.
- `app/src/test/java/com/apptive/slowtalk/LetterApiContractTest.kt`, `app/src/test/java/com/apptive/slowtalk/ReportApiContractTest.kt`: REST 계약을 MockWebServer로 고정한다.

### Task 10 책임 파일

- `backend/scripts/smoke_two_users.py`: 두 계정으로 인증, 편지 매칭, 채팅, 읽음, 모임을 자동 검증한다.
- `docs/local-development.md`: 빈 환경에서 backend와 Android를 시작하는 절차를 제공한다.
- `docs/local-smoke-test.md`: 자동 검증과 에뮬레이터 수동 확인표를 제공한다.
- `README.md`: 로컬 실행 진입점과 위 문서를 연결한다.
- `backend/openapi/slowtalk-v1.json`: 최종 REST 계약 export 결과를 저장한다. WebSocket 계약은 OpenAPI 대상이 아니므로 문서에 별도 기록한다.

---

### Task 8: 인증된 UUID 채팅과 초대 후보 기반 모임 통합

**Interfaces:**

- Consumes: Task 4의 `ApiEnvelope<T>`와 UUID `String`, Task 5의 `AuthSession.accessToken`, Task 3의 `RetrofitClient.baseUrl`.
- Produces: `buildChatSocketUrl(apiBaseUrl: String, roomId: String, accessToken: String): String`, REST-confirmed message 전송, `ChatSocketEvent(type, roomId, data)`, `MeetingInviteCandidateDto(candidateId, displayName)`, `MeetingCreation(meetingId, chatRoomId)`.
- REST contract: `GET /chat-rooms`, `GET /chat-rooms/{uuid}`, `GET /chat-rooms/{uuid}/messages?cursor={uuid}&limit=30`, `POST /chat-rooms/{uuid}/messages`, `PATCH /chat-rooms/{uuid}/read`, `GET /meeting-invite-candidates?keyword=`, `POST /meetings`, `GET /meetings/{uuid}`.
- WebSocket contract: `GET ws://host/api/v1/ws/chat-rooms/{uuid}?token={encoded JWT}`; server message event is `{"type":"message","roomId":"<uuid>","data":<ChatMessageDto>}`.

```kotlin
@Serializable
data class ChatSocketEvent(
    val type: String,
    val roomId: String,
    val data: ChatMessageDto,
)

data class MeetingCreation(val meetingId: String, val chatRoomId: String)
```

- [ ] **Step 1: Android 채팅과 모임 계약 실패 테스트를 작성한다**

`ChatApiContractTest.kt`에 아래 단언을 포함한다.

```kotlin
@Test fun socketUrlUsesUuidRoomAndEncodedToken() {
    assertEquals(
        "ws://10.0.2.2:8000/api/v1/ws/chat-rooms/5c1fd89d-07ea-4f9d-9be9-08bcbba57415?token=access%2Btoken%2Fvalue",
        buildChatSocketUrl(
            "http://10.0.2.2:8000/api/v1/",
            "5c1fd89d-07ea-4f9d-9be9-08bcbba57415",
            "access+token/value",
        ),
    )
}
```

MockWebServer recorded request에는 다음 값도 단언한다.

```text
POST /api/v1/chat-rooms/5c1fd89d-07ea-4f9d-9be9-08bcbba57415/messages
Authorization: Bearer access-123
{"clientMessageId":"7ba4b6fc-f211-4f52-b7ca-5316b87d59a8","content":"안녕하세요"}

PATCH /api/v1/chat-rooms/5c1fd89d-07ea-4f9d-9be9-08bcbba57415/read
{"lastReadMessageId":"8f2117e1-1acb-4f22-9061-b7d26185ea68"}

GET /api/v1/meeting-invite-candidates?keyword=%EC%82%B0%EC%B1%85
POST /api/v1/meetings
{"title":"주말 산책","description":"천천히 걸어요","inviteCandidateIds":["4bd2af75-0ee6-4ef2-954d-9eb9b95531ac"]}
```

- [ ] **Step 2: Android 계약 테스트가 현재 경로와 무토큰 socket 때문에 실패하는지 확인한다**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ChatApiContractTest*" --tests "*MeetingApiContractTest*"
```

Expected: `/ws/chat/{id}` 또는 `meetings/invite-users` 경로 불일치 단언이 FAIL한다.

- [ ] **Step 3: Chat REST DTO를 backend와 일치시킨다**

`ChatApiService.kt`에서 모든 ID를 `String`으로 유지하고 `getMessages`는 cursor와 limit를 받는다. pagination 응답은 Task 4에서 정의한 `ApiEnvelope<List<ChatMessageDto>>`와 `meta.nextCursor`를 사용한다. `ChatMessageRequest.clientMessageId`는 매 재시도마다 바꾸지 않고 한 사용자 전송 시도 동안 같은 UUID를 재사용한다.

```kotlin
@GET("chat-rooms/{roomId}/messages")
suspend fun getMessages(
    @Path("roomId") roomId: String,
    @Query("cursor") cursor: String? = null,
    @Query("limit") limit: Int = 30,
): ApiEnvelope<List<ChatMessageDto>>
```

- [ ] **Step 4: WebSocket URL 생성과 인증을 구현한다**

`RetrofitClient.openChatWebSocket`은 `AuthSession.accessToken`이 없으면 socket을 열지 않고 인증 필요 오류를 반환한다. `HttpUrl.Builder`로 token query를 추가해 직접 문자열 연결에 따른 인코딩 오류를 피한다.

```kotlin
internal fun buildChatSocketUrl(apiBaseUrl: String, roomId: String, accessToken: String): String =
    webSocketBaseUrl(apiBaseUrl).toHttpUrl().newBuilder()
        .addPathSegments("ws/chat-rooms/$roomId")
        .addQueryParameter("token", accessToken)
        .build().toString()
```

- [ ] **Step 5: backend WebSocket을 broadcast 수신 채널로 만든다**

`backend/app/chat/connections.py`에 `ChatConnectionManager.connect(room_id, user_id, socket)`, `disconnect(room_id, socket)`, `connections(room_id)`를 만든다. `POST /chat-rooms/{room_id}/messages`가 DB commit에 성공한 뒤 각 연결의 `user_id`로 `message_view(session, message, viewer_id)`를 호출해 viewer별 event를 broadcast한다. 발신자 연결은 `sender.isMe=true`, 상대방 연결은 `sender.isMe=false`여야 한다.

```json
{
  "type": "message",
  "roomId": "5c1fd89d-07ea-4f9d-9be9-08bcbba57415",
  "data": {
    "id": "8f2117e1-1acb-4f22-9061-b7d26185ea68",
    "clientMessageId": "7ba4b6fc-f211-4f52-b7ca-5316b87d59a8",
    "type": "TEXT",
    "sender": {"displayName": "나", "isMe": true},
    "content": "안녕하세요",
    "createdAt": "2026-08-03T12:00:00+00:00"
  }
}
```

WebSocket으로 받은 임의 payload를 저장하지 않는다. Android 전송은 항상 REST를 사용하므로 moderation, idempotency, DB transaction을 우회하지 않는다.

- [ ] **Step 6: backend realtime 테스트를 추가한다**

`test_chat_realtime.py`에서 connection manager에 Alice/Bob fake socket을 같은 room에 연결하고, REST 저장 후 생성한 event를 broadcast했을 때 두 socket이 동일한 persisted message ID와 content를 받는지 검증한다. Bob이 발신자라면 Bob payload의 `sender.isMe`는 true, Alice payload는 false여야 한다. 다른 room socket은 받지 않아야 하며, router의 기존 4401(토큰 없음/잘못됨), 4404(비참여자) 조건은 유지한다.

```powershell
Set-Location backend
python -m pytest tests/chat/test_chat_api.py tests/chat/test_chat_realtime.py tests/meetings/test_meeting_api.py -q
Set-Location ..
```

Expected: 모든 테스트 PASS.

- [ ] **Step 7: Android 메시지 확정·중복 제거·fallback 흐름을 구현한다**

Compose에서 전송 버튼 클릭 시 client UUID와 pending UI를 만들고 `ChatApi.sendMessage` REST 201 응답을 받으면 server message ID로 교체한다. socket event가 먼저 또는 나중에 도착해도 `message.id` 또는 `clientMessageId`가 같은 항목은 하나만 유지한다. REST 실패 시 pending 항목을 실패 상태로 바꾸고 같은 client UUID로 재시도할 수 있게 한다. socket 연결 실패는 REST 목록/전송을 계속 허용하고 “실시간 연결이 끊겼습니다. 다시 연결 중입니다.” 상태만 표시한다.

HTTP 202 `PendingReview`는 확정 메시지 목록에 추가하지 않고 “검토 후 전송됩니다” 상태와 `submissionId`를 보관한다. 202를 DTO 역직렬화 실패나 REST 201 성공으로 변환하지 않는다.

- [ ] **Step 8: 모임 후보 token 계약을 구현한다**

`MeetingApiService.kt`의 후보 경로를 정확히 `meeting-invite-candidates`로 바꾼다. 화면 선택 값은 `candidateId: String`이어야 하고 사용자 ID나 목록 index를 전송하지 않는다. 후보를 받은 지 15분이 지나거나 생성이 409 `RESOURCE_CONFLICT`이면 후보 목록을 다시 조회하고 사용자가 재선택하도록 한다. 201 응답의 `chatRoom.id`로만 group 채팅 화면에 이동한다.

- [ ] **Step 9: Android 계약 테스트와 backend 도메인 테스트를 재실행한다**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ChatApiContractTest*" --tests "*MeetingApiContractTest*"
Set-Location backend
python -m pytest tests/chat tests/meetings -q
Set-Location ..
```

Expected: Gradle `BUILD SUCCESSFUL`, pytest 모두 PASS.

- [ ] **Step 10: Task 8 변경만 커밋한다**

```powershell
git add backend/app/chat backend/tests/chat app/src/main/java/com/apptive/slowtalk/data/remote/ChatApiService.kt app/src/main/java/com/apptive/slowtalk/data/remote/MeetingApiService.kt app/src/main/java/com/apptive/slowtalk/data/remote/RetrofitClient.kt app/src/main/java/com/apptive/slowtalk/ChatApi.kt app/src/main/java/com/apptive/slowtalk/MeetingApi.kt app/src/main/java/com/apptive/slowtalk/ConversationScreens.kt app/src/test/java/com/apptive/slowtalk/ChatApiContractTest.kt app/src/test/java/com/apptive/slowtalk/MeetingApiContractTest.kt
git commit -m "feat: connect authenticated chat and meetings"
```

---

### Task 9: 편지·매칭·회고 영속화와 AI/OCR 가용성 분리

**Backend 지원 현황:**

| 영역 | 현재 backend 지원 | Task 9에서 확정할 계약 |
|---|---|---|
| 편지 | `POST /letters`, `GET /letters?direction=sent|received`, `GET /letters/{uuid}` 구현됨 | Android 목업 제거, `Idempotency-Key` 전송, direction과 pagination 정렬 |
| 편지 매칭 | `match=true`이면 profile matching으로 recipient, direct room, first LETTER message를 한 transaction에서 생성함 | 응답의 `matching.matched`와 `chatRoom.id`를 사용; room이 없으면 채팅 이동 금지 |
| 의미론 매칭 | `matching_mode=shadow|enforce`와 Upstage embedding 설정 시 지원; 기본 `disabled`에서도 profile matching은 지원 | 로컬 필수 게이트는 `disabled` profile matching, semantic 검증은 설정이 완전한 환경에서만 수행 |
| 회고 목록·상세 | `GET /reports`, `GET /reports/{uuid}` 구현됨 | Android API/화면 연결 |
| 회고 생성 | 현재 `analysisId`가 필수여서 AI feedback 없이는 저장 불가 | `analysisId: String?`로 바꾸고 null이면 content, 빈 summary/feedback으로 저장 |
| AI feedback | endpoint는 있지만 `LocalWritingAssistant`가 가짜 성공 결과를 반환함 | provider 미설정 시 503 `FEATURE_UNAVAILABLE`; 설정 완료 시에만 실제 adapter 사용 |
| OCR | endpoint는 있지만 고정 샘플 문자를 반환함 | provider 미설정 시 503 `FEATURE_UNAVAILABLE`; 가짜 문구 제거 |

**Interfaces:**

- Produces: `FeatureAvailability.Available` 또는 `FeatureAvailability.Unavailable(message)`, AI와 독립적인 letter/report CRUD, `LetterCreateResult(letter, matching, chatRoom, firstMessage)`.
- Error contract: HTTP 503 envelope의 `error.code`는 정확히 `FEATURE_UNAVAILABLE`, message는 `로컬 환경에서 AI 제공자 키가 설정되지 않았습니다.`이다.

```kotlin
sealed interface FeatureAvailability {
    data object Available : FeatureAvailability
    data class Unavailable(val message: String) : FeatureAvailability
}

data class LetterCreateResult(
    val letter: LetterDetailDto,
    val matching: LetterMatchingDto,
    val chatRoom: LetterChatRoomDto?,
    val firstMessage: LetterFirstMessageDto?,
)
```

- [ ] **Step 1: key 없는 AI/OCR 실패 테스트를 먼저 작성한다**

`backend/tests/ai/test_ai_unavailable.py`에 다음 네 endpoint를 parameterize한다.

```python
@pytest.mark.parametrize(
    ("path", "request_kwargs"),
    [
        ("/api/v1/letters/feedback", {"json": {"content": "천천히 쓴 편지"}}),
        ("/api/v1/reports/feedback", {"json": {"content": "오늘의 회고"}}),
        ("/api/v1/letters/ocr", {"files": {"image": ("letter.png", VALID_PNG, "image/png")}}),
        ("/api/v1/reports/ocr", {"files": {"image": ("report.png", VALID_PNG, "image/png")}}),
    ],
)
async def test_ai_feature_is_unavailable_without_provider(client, path, request_kwargs):
    headers = await register(client, "no-ai@example.com", "NoAI")
    response = await client.post(path, headers=headers, **request_kwargs)
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "FEATURE_UNAVAILABLE"
```

Expected: 현재 `LocalWritingAssistant`가 200/201과 가짜 결과를 반환하므로 FAIL.

- [ ] **Step 2: AI provider 선택을 명시적으로 구현한다**

`Settings`에 `upstage_ocr_model: str | None`, `upstage_feedback_path: str = "/solar/chat/completions"`, `upstage_ocr_path: str = "/document-digitization"`을 추가한다. app lifespan은 `UPSTAGE_API_KEY`, `UPSTAGE_CHAT_MODEL`, `UPSTAGE_OCR_MODEL`이 모두 비어 있지 않을 때만 기존 `upstage_base_url`을 사용하는 공유 `httpx.AsyncClient`와 `UpstageWritingAssistant`를 `application.state.writing_assistant`에 저장하고 종료 시 client를 닫는다. `get_writing_assistant(request: Request)`는 이 state가 없으면 `ApiError("FEATURE_UNAVAILABLE", "로컬 환경에서 AI 제공자 키가 설정되지 않았습니다.", 503)`를 발생시킨다. 기존 `LocalWritingAssistant`와 `이미지 텍스트 추출 결과`, `편지의 흐름이 자연스럽습니다.` 같은 고정 성공 문구는 삭제한다.

`UpstageWritingAssistant.feedback()`은 `POST /solar/chat/completions`에 Bearer key, configured chat model, system/user messages를 보내고 응답 JSON을 `WritingFeedback(summary, suggestions)`으로 엄격하게 검증한다. `ocr()`은 `POST /document-digitization`에 multipart `document`와 configured OCR model을 보내고 provider 응답의 추출 text를 반환한다. `respx` 테스트는 URL, Authorization header 존재 여부, model, 응답 변환을 고정하되 key 값 자체를 snapshot이나 assertion failure에 넣지 않는다.

실제 adapter는 access token이나 원문 전체를 로그에 기록하지 않고 timeout/provider 5xx를 `AI_SERVICE_UNAVAILABLE` 503으로 변환한다. provider 통합 테스트는 환경변수가 모두 있을 때만 실행하고, key 없는 기본 test suite는 network를 호출하지 않는다.

- [ ] **Step 3: AI 없는 기본 회고 저장 backend 테스트를 작성한다**

`backend/tests/reports/test_report_api.py`에 아래 흐름을 추가한다.

```python
async def test_report_can_be_saved_without_ai_analysis(client):
    headers = await register(client, "plain-report@example.com", "PlainReport")
    created = await client.post(
        "/api/v1/reports",
        headers=headers,
        json={"content": "AI 없이 저장하는 오늘의 회고", "analysisId": None},
    )
    assert created.status_code == 201
    assert created.json()["data"]["summary"] == ""
    assert created.json()["data"]["feedback"] == []
    report_id = created.json()["data"]["id"]
    assert (await client.get(f"/api/v1/reports/{report_id}", headers=headers)).status_code == 200
```

- [ ] **Step 4: 회고 저장을 AI analysis와 분리한다**

`ReportCreate.analysis_id`를 `UUID | None = Field(None, alias="analysisId")`로 바꾼다. null이면 snapshot 조회 없이 `ReflectionReport(owner_id, content, summary="", feedback=[])`를 저장한다. UUID가 있으면 기존 소유자, 만료, single-use, source hash 검증을 그대로 적용한다. 목록과 상세 응답 형식은 두 저장 방식에서 동일하다.

- [ ] **Step 5: 편지 Android 계약 테스트를 작성하고 수정한다**

MockWebServer에서 다음을 단언한다.

```text
GET /api/v1/letters?direction=sent&limit=30
POST /api/v1/letters
Idempotency-Key: <8자 이상이며 재시도 동안 동일한 값>
{"content":"안녕하세요","match":true}
```

DTO는 backend JSON 이름을 그대로 사용한다.

```kotlin
@Serializable data class WritingFeedbackDto(val summary: String, val suggestions: List<String>)
@Serializable data class OcrTextDto(val text: String)
@Serializable data class LetterMatchingDto(
    val matched: Boolean,
    val strategy: String? = null,
    val fallbackReason: String? = null,
)
```

`LetterRepository.createLetter`는 `Result<Unit>`이 아니라 `Result<LetterCreateResponse>`를 반환해 화면이 `chatRoom?.id`를 소비할 수 있게 한다.

- [ ] **Step 6: 실제 편지 CRUD와 매칭 화면 흐름을 구현한다**

`LetterRepository`의 `MOCK_MODE`와 고정 feedback/OCR 문자열을 삭제한다. 받은 편지함은 `direction=received`, 보낸 편지함은 `direction=sent`를 요청한다. `match=true` 생성 결과에 `matching.matched == true`이고 `chatRoom != null`일 때만 `Screen.Chat(title="익명의 이웃", chatRoomId=chatRoom.id)`로 이동한다. `MATCH_NOT_FOUND` 409이면 편지는 transaction상 생성되지 않았음을 알리고 재시도를 제공한다. 고정 `Screen.Chat("익명의 이웃 05")` 이동은 삭제한다.

편지 생성의 HTTP 202 `PendingReview`에는 `LetterCreateResponse`나 `chatRoom`이 없으므로 채팅으로 이동하지 않고 “검토 후 전달됩니다” 상태만 표시한다. HTTP 422 `CONTENT_POLICY_VIOLATION`은 Task 4의 parsed error code/message를 그대로 표시한다.

- [ ] **Step 7: 회고 Android 계약과 repository를 AI 비의존 방식으로 수정한다**

`ReportApi`에 `GET reports`, `GET reports/{reportId}`를 추가하고 `ReportCreateRequest.analysisId`를 nullable로 바꾼다. feedback 성공 시 받은 analysis ID를 저장 요청에 포함하고, 503 `FEATURE_UNAVAILABLE`이면 `analysisId=null`로 기본 저장을 허용한다. feedback의 다른 오류는 AI 비활성으로 위장하지 않고 오류로 표시한다. OCR 응답 필드는 `content`가 아니라 `text`를 사용한다.

- [ ] **Step 8: Android UI에서 AI 비활성과 저장 실패를 분리한다**

503 `FEATURE_UNAVAILABLE`일 때만 AI 카드와 OCR 버튼에 `로컬 환경에서 AI 제공자 키가 설정되지 않았습니다.`를 표시하고 비활성화한다. 편지 작성, 편지 목록/상세, 회고 작성, 회고 목록/상세 버튼은 활성 상태를 유지한다. 저장 API가 실패하면 성공·매칭·상세 화면으로 이동하지 않는다.

- [ ] **Step 9: Task 9 backend와 Android 집중 테스트를 실행한다**

```powershell
Set-Location backend
python -m pytest tests/ai/test_ai_unavailable.py tests/letters tests/reports tests/matching -q
Set-Location ..
.\gradlew.bat testDebugUnitTest --tests "*LetterApiContractTest*" --tests "*ReportApiContractTest*" --tests "*AiAvailabilityTest*"
```

Expected: key 없이 모든 core CRUD/matching 테스트 PASS, AI/OCR는 503 계약 PASS, Gradle `BUILD SUCCESSFUL`.

- [ ] **Step 10: provider 설정이 있는 경우에만 실제 AI 통합을 검증한다**

```powershell
Set-Location backend
$env:UPSTAGE_API_KEY.Length -gt 0
$env:UPSTAGE_CHAT_MODEL.Length -gt 0
$env:UPSTAGE_OCR_MODEL.Length -gt 0
python -m pytest tests/ai -m integration -q
Set-Location ..
```

Expected: 세 길이 검사가 `True`인 환경에서만 실제 provider 테스트를 실행하며 feedback은 비어 있지 않은 summary/suggestions를, OCR은 업로드 이미지에서 추출한 비어 있지 않은 text를 반환한다. key 값 자체는 출력하지 않는다.

- [ ] **Step 11: Task 9 변경만 커밋한다**

```powershell
git add backend/app/ai backend/app/reports backend/tests/ai backend/tests/reports app/src/main/java/com/apptive/slowtalk/data/remote/LetterApiService.kt app/src/main/java/com/apptive/slowtalk/data/remote/ReportApi.kt app/src/main/java/com/apptive/slowtalk/data/repository/LetterRepository.kt app/src/main/java/com/apptive/slowtalk/data/repository/ReportRepository.kt app/src/main/java/com/apptive/slowtalk/ui/letter app/src/main/java/com/apptive/slowtalk/ui/reflection app/src/main/java/com/apptive/slowtalk/LetterScreens.kt app/src/main/java/com/apptive/slowtalk/ReflectionScreens.kt app/src/main/java/com/apptive/slowtalk/MainActivity.kt app/src/test/java/com/apptive/slowtalk/AiAvailabilityTest.kt app/src/test/java/com/apptive/slowtalk/LetterApiContractTest.kt app/src/test/java/com/apptive/slowtalk/ReportApiContractTest.kt
git commit -m "feat: make letter matching and reflection flows locally reliable"
```

---

### Task 10: 전체 자동 검증, 두 사용자 E2E, 문서와 OpenAPI 완료 게이트

**Interfaces:**

- Consumes: Task 1-9의 모든 commit과 `backend/.env`, Android `API_BASE_URL` 로컬 설정.
- Produces: 재실행 가능한 `backend/scripts/smoke_two_users.py`, `docs/local-development.md`, `docs/local-smoke-test.md`, 최신 `backend/openapi/slowtalk-v1.json`, 모든 completion gate의 증거.

- [ ] **Step 1: 두 사용자 자동 E2E script를 작성한다**

`backend/scripts/smoke_two_users.py`는 `SLOWTALK_API_URL` 기본값 `http://127.0.0.1:8000/api/v1`을 사용하고 실행마다 UUID suffix가 붙은 Alice/Bob 계정을 생성한다. 다음 순서를 assert하고 하나라도 실패하면 exit code 1을 반환한다.

```text
1. Alice/Bob signup -> 두 accessToken 획득
2. Alice/Bob /users/me -> 각각 200
3. Alice POST /letters match=true -> 201, matching.matched=true, chatRoom.id UUID
4. Bob GET /chat-rooms -> 같은 room ID 포함
5. Bob POST /chat-rooms/{roomId}/messages -> 201, server message UUID
6. Alice GET messages -> Bob message 포함
7. Alice PATCH read -> unreadCount=0
8. Alice GET /meeting-invite-candidates -> candidateId UUID
9. Alice POST /meetings -> 201, GROUP chatRoom UUID
10. Alice POST /reports analysisId=null -> 201; 재조회 content 일치
11. provider 미설정이면 /letters/feedback -> 503 FEATURE_UNAVAILABLE
```

성공 시 token이나 원문을 출력하지 않고 다음 한 줄만 출력한다.

```text
PASS two-user local E2E: auth letter match chat read meeting report ai-unavailable
```

- [ ] **Step 2: backend 전체 정적·동적 검증을 실행한다**

```powershell
Set-Location backend
python -m ruff check app tests scripts
python -m mypy app
python -m pytest -q
python -m alembic downgrade base
python -m alembic upgrade head
python scripts/export_openapi.py openapi/slowtalk-v1.json
git diff --exit-code -- openapi/slowtalk-v1.json
Set-Location ..
```

Expected: Ruff, mypy, pytest, Alembic fresh migration이 모두 성공하고 OpenAPI export 후 diff가 없다. OpenAPI가 의도적으로 바뀌었다면 export 파일을 검토·stage한 뒤 다시 export하여 diff가 0인지 확인한다.

- [ ] **Step 3: OpenAPI 필수 REST 계약을 검사한다**

```powershell
$schema = Get-Content -Raw backend/openapi/slowtalk-v1.json | ConvertFrom-Json
$required = @(
  '/api/v1/chat-rooms',
  '/api/v1/chat-rooms/{room_id}/messages',
  '/api/v1/chat-rooms/{room_id}/read',
  '/api/v1/meeting-invite-candidates',
  '/api/v1/meetings',
  '/api/v1/letters',
  '/api/v1/letters/{letter_id}',
  '/api/v1/letters/feedback',
  '/api/v1/letters/ocr',
  '/api/v1/reports',
  '/api/v1/reports/{report_id}',
  '/api/v1/reports/feedback',
  '/api/v1/reports/ocr'
)
$missing = $required | Where-Object { -not $schema.paths.PSObject.Properties.Name.Contains($_) }
if ($missing.Count -ne 0) { throw "Missing OpenAPI paths: $($missing -join ', ')" }
```

Expected: 예외 없이 종료. WebSocket은 OpenAPI에 나타나지 않으므로 `docs/local-development.md`에 URL, token query, message event JSON을 별도로 기록한다.

- [ ] **Step 4: Android 전체 자동 검증을 실행한다**

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

Expected: 세 명령 모두 `BUILD SUCCESSFUL`.

- [ ] **Step 5: 실행 중인 로컬 backend에 두 사용자 E2E를 실행한다**

```powershell
Set-Location backend
docker compose up -d db
python -m alembic upgrade head
Start-Process -FilePath python -ArgumentList '-m','uvicorn','app.main:app','--host','0.0.0.0','--port','8000' -WindowStyle Hidden
$env:SLOWTALK_API_URL='http://127.0.0.1:8000/api/v1'
python scripts/smoke_two_users.py
Set-Location ..
```

Expected: `/api/v1/ready`가 200인 상태에서 script가 정확히 `PASS two-user local E2E: auth letter match chat read meeting report ai-unavailable`를 출력한다.

- [ ] **Step 6: 에뮬레이터 수동 smoke test를 수행한다**

서로 다른 두 계정을 사용하고 각 체크를 실제 화면과 재조회로 확인한다.

- [ ] 회원가입, 로그아웃, 로그인, 앱 강제 종료 후 세션 복원
- [ ] 프로필·지역·관심사 변경 후 앱 재실행 시 값 유지
- [ ] 피드 작성·조회·수정·삭제·공감·댓글·신고
- [ ] 편지 저장·보낸 편지함·받은 편지함·상세 조회
- [ ] 매칭 편지로 받은 실제 `chatRoomId`에 두 사용자가 입장
- [ ] 상대방 메시지 실시간 표시, 앱 재진입 후 REST history 유지, 읽음 0 동기화
- [ ] candidate 선택으로 group meeting 생성 후 응답 room ID로 입장
- [ ] AI key 없음 안내 표시 중에도 회고 저장·목록·상세 동작
- [ ] OCR/feedback 영역에 가짜 샘플 문구가 한 번도 나타나지 않음

- [ ] **Step 7: 로컬 개발 문서를 완성한다**

`docs/local-development.md`에 Docker Desktop, Python 3.11 가상환경, `pip install -e ".[dev]"`, `.env.example` 복사, moderation/matching local 설정, DB/Alembic/FastAPI 시작, `/health`와 `/ready`, Android `local.properties`의 `API_BASE_URL`, 에뮬레이터 실행, WebSocket 계약, 종료 절차를 순서대로 기록한다. `docs/local-smoke-test.md`에는 Step 2-6의 명령과 체크박스를 그대로 옮기고 마지막 검증 날짜와 검증 commit SHA는 해당 검증 커밋의 `git rev-parse HEAD` 출력으로 기록한다. `README.md`에는 두 문서 링크를 추가한다.

- [ ] **Step 8: 금지된 목업과 비밀 추적을 검사한다**

```powershell
rg -n "MOCK_MODE\s*=\s*true|api\.example\.com|이미지 텍스트 추출 결과|OCR 인식 결과 샘플|mock-access-token" app/src/main backend/app
git ls-files backend/.env local.properties .codex-remote-attachments .android-sdk .worktrees
git diff --check
```

Expected: 세 명령 모두 출력이나 오류가 없다.

- [ ] **Step 9: 최종 문서·OpenAPI 변경을 커밋한다**

```powershell
git add README.md docs/local-development.md docs/local-smoke-test.md backend/scripts/smoke_two_users.py backend/openapi/slowtalk-v1.json
git commit -m "docs: add verified local full-stack workflow"
```

- [ ] **Step 10: 최종 완료 게이트를 기록한다**

```powershell
git status --short
git log -3 --oneline
```

Expected: 작업 트리가 clean이고 최근 세 커밋에 Task 8, Task 9, Task 10 제목이 있으며, backend 전체 검증, Android unit/lint/assemble, 두 사용자 E2E, 에뮬레이터 smoke test가 모두 통과했다.

## Completion Gate

다음 조건이 모두 충족되어야 local full-stack integration Task 8-10을 완료로 표시한다.

1. Task 1-7의 일곱 커밋과 집중 검증이 존재한다.
2. 채팅 room/message/read ID가 끝까지 UUID 문자열이고 모든 보호 REST/WS 요청이 실제 로그인 token을 사용한다.
3. 메시지는 REST 201로 저장된 뒤 확정되며 두 사용자에게 broadcast되고 재조회 후 유지된다.
4. 모임은 15분 유효 candidate UUID로 생성되고 응답의 실제 group room ID로 이동한다.
5. 편지 저장·목록·상세와 profile matching이 PostgreSQL에서 동작하며 room ID가 없을 때 고정 채팅으로 이동하지 않는다.
6. 회고는 AI analysis 없이도 저장·목록·상세가 가능하고 analysis가 있을 때 기존 source-bound single-use 규칙을 지킨다.
7. provider 미설정 환경의 AI/OCR는 503 `FEATURE_UNAVAILABLE`이며 core CRUD는 유지되고 가짜 결과는 없다.
8. Ruff, mypy, pytest, Alembic fresh migration, OpenAPI export, Android unit test, lint, debug assemble가 모두 성공한다.
9. 자동 두 사용자 E2E와 에뮬레이터 smoke test가 모두 성공한다.
10. 실행 문서와 OpenAPI가 실제 구현과 일치하고 작업 트리가 clean이다.
