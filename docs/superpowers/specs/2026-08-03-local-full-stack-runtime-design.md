# SlowTalk Local Full-Stack Runtime Design

## 목적

최신 Android 앱과 저장소의 FastAPI 백엔드를 하나의 로컬 실행 환경으로 통합한다. Android 에뮬레이터에서 회원가입과 로그인부터 프로필, 피드, 편지, 회고, 모임, 채팅까지 실제 PostgreSQL 데이터를 사용하며, 앱 재시작 후에도 데이터가 유지되어야 한다.

## 범위

포함 범위는 로컬 PostgreSQL/FastAPI 실행, Android 네트워크 설정, 인증 세션, 공통 응답 envelope, UUID 계약, 프로필·지역·관심사, 피드, 채팅·모임, 편지·회고 통합이다. 배포 인프라, 운영용 비밀 관리, 프로덕션 HTTPS 인증서와 앱 디자인 개편은 포함하지 않는다.

외부 AI 키가 없는 로컬 환경에서도 인증과 CRUD, 채팅은 정상 동작한다. AI 피드백과 OCR은 가짜 결과를 반환하지 않고 기능을 사용할 수 없는 이유를 화면에 표시한다. Upstage 키가 제공된 경우에만 실제 AI 통합 검증을 수행한다.

## 기준선과 통합 전략

- Android 기준선: 구현 시작 시점의 `origin/main`
- 백엔드 기준선: 구현 시작 시점의 `origin/codex/semantic-matching-foundation/backend`
- 통합 브랜치: `integration/local-full-stack`

`origin/main`에서 통합 브랜치를 만들고 백엔드 브랜치의 `backend/` 디렉터리만 가져온다. 이 방식은 백엔드 브랜치에 남은 오래된 Android 코드를 다시 도입하지 않는다. FastAPI의 `/api/v1` OpenAPI 계약을 클라이언트와 서버 사이의 단일 진실 공급원으로 사용한다.

## 로컬 아키텍처

```text
Android Emulator
  Retrofit/OkHttp  ── http://10.0.2.2:8000/api/v1/ ──> FastAPI
  WebSocket        ── ws://10.0.2.2:8000/api/v1/ ────> Chat Router
                                                        │
                                                        ▼
                                              PostgreSQL + pgvector
                                              localhost:5432
```

FastAPI는 Windows 호스트의 `0.0.0.0:8000`에서 실행한다. Android 에뮬레이터는 호스트 loopback을 가리키는 `10.0.2.2`를 사용한다. 실제 기기는 같은 네트워크의 개발 PC LAN 주소를 Gradle 속성으로 전달한다. HTTP cleartext 허용은 debug manifest에만 둔다.

## Android 통신 계층

`RetrofitClient`는 하드코딩한 `api.example.com`을 제거하고 `BuildConfig.API_BASE_URL`을 사용한다. 모든 성공 응답은 다음 공통 형태로 역직렬화한다.

```kotlin
ApiEnvelope<T>(
    ok: Boolean,
    data: T?,
    error: ApiErrorDto?,
    meta: ApiMeta?
)
```

백엔드가 UUID를 사용하는 모든 리소스 ID는 Android에서 `String`으로 표현한다. Retrofit interface는 FastAPI의 HTTP method, path, query, request body와 정확히 일치해야 한다. 화면 모델 변환은 repository 또는 기존 facade에서 담당하고 Retrofit DTO가 Compose 화면으로 직접 새지 않게 한다.

## 인증과 세션

로그인 응답의 access/refresh token을 앱 전용 저장소와 프로세스 메모리에 보관한다. OkHttp interceptor가 보호 API에 `Authorization: Bearer <token>`을 추가한다. 로그인 전 공개 API에는 헤더를 추가하지 않는다. 로그아웃은 서버 요청 결과와 관계없이 로컬 토큰을 지워 다음 보호 요청이 인증 없이 전송되지 않도록 한다.

앱 시작 시 저장된 세션을 복원하고 로그인 화면 또는 메인 화면을 결정한다. 토큰 값을 BODY 로그, 오류 메시지, Git 추적 파일에 출력하지 않는다.

## 도메인 통합

프로필, 지역, 관심사 repository의 `MOCK_MODE`를 제거한다. 지역은 표시 이름과 서버 코드를 구분하며, 관심사는 UUID 목록으로 교체한다.

피드는 `/feeds?scope=...` pagination, UUID 카테고리, `PUT` 공감, 복수형 신고 경로, 독립 댓글 경로를 사용한다. 삭제와 신고는 서버 성공 이후 UI에 확정 반영한다.

채팅 REST와 WebSocket은 같은 UUID room ID를 사용한다. WebSocket 주소는 `/ws/chat-rooms/{roomId}?token=...`이며, `send()` 큐잉 성공을 서버 저장 성공으로 간주하지 않는다. 서버 ack 또는 REST 성공을 받은 메시지만 확정한다. 모임 생성은 사용자 ID가 아니라 유효기간이 있는 초대 후보 UUID를 전달한다.

편지와 회고의 저장·목록·상세는 AI 설정과 분리한다. AI/OCR 제공자가 없으면 503 `FEATURE_UNAVAILABLE`을 일관되게 처리하되, 일반 저장과 조회는 계속 사용할 수 있어야 한다. 편지 매칭으로 생성된 실제 `chatRoomId`가 있을 때만 채팅 화면으로 이동한다.

## 오류 처리

- 네트워크 연결 실패: 사용자가 재시도할 수 있는 화면 상태로 변환
- 401: 세션 제거 후 로그인 화면 이동
- 400/409/422: 서버 오류 code/message를 해당 입력 화면에 표시
- 503 `FEATURE_UNAVAILABLE`: AI 영역만 비활성화하고 핵심 기능 유지
- WebSocket 끊김: REST 메시지 전송 fallback 및 재연결 가능 상태 제공
- 서버 실패 전 UI 상태를 영구 변경하지 않으며, 필요한 선반영에는 명시적 rollback을 둔다

## 테스트 전략

백엔드는 ruff, mypy, pytest, Alembic 신규 DB 마이그레이션과 OpenAPI export를 통과해야 한다. Android는 kotlinx.serialization fixture 테스트, MockWebServer 경로/헤더 테스트, repository 단위 테스트, lint와 debug build를 통과해야 한다.

수동 E2E는 서로 다른 두 계정을 사용한다. 회원가입·세션 복원, 프로필 영속성, 피드 CRUD/공감/댓글, 편지와 회고 저장, 모임 생성, 양방향 채팅, 읽음 처리, 앱 재시작 후 재조회까지 검증한다.

## 완료 조건

1. 새 PostgreSQL 볼륨에서 로컬 백엔드가 재현 가능하게 기동한다.
2. Android debug 앱이 `api.example.com`을 참조하지 않는다.
3. 출시 경로에 활성화된 목업 분기가 없다.
4. Android와 FastAPI의 UUID, endpoint, HTTP method, envelope가 일치한다.
5. 앱 재시작 후 핵심 데이터가 유지된다.
6. 두 계정의 채팅과 읽음 상태가 동기화된다.
7. AI 키가 없어도 핵심 기능이 동작하고 가짜 AI 결과가 노출되지 않는다.
8. 백엔드와 Android의 자동 검사 및 수동 smoke test가 모두 통과한다.
