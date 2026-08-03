# Solar 프로필·임베딩 매칭 및 콘텐츠 검열 설계

- 작성일: 2026-08-01
- 대상 서비스: 마음잇기 FastAPI backend
- 상태: 사용자 승인 설계
- 관련 브랜치: `codex/slowtalk-backend-api`

## 1. 목적

마음잇기의 단순 선착순 매칭을 두 단계 추천 방식으로 교체한다. 사용자의 첫 성공 매칭은 프로필의 관심사와 지역을 사용하고, 두 번째 성공 매칭부터는 Upstage Solar Embed 2 계열 임베딩으로 현재 편지와 후보자의 최근 편지 성향을 비교한다.

동시에 편지, 채팅, 피드, 댓글, 회고, OCR 결과를 저장하거나 다른 사용자에게 노출하기 전에 검열한다. 검열은 `ALLOW`, `REVIEW`, `BLOCK` 세 단계로 판정하며 Upstage 장애 시 미검열 콘텐츠를 공개하지 않고 격리한다.

## 2. 확정 요구사항

### 2.1 매칭

- 성공한 매칭 이력이 없는 사용자는 프로필 기반으로 첫 매칭을 수행한다.
- 첫 매칭 후보군은 같은 동, 같은 구, 같은 시·도, 전국 순서로 확장한다.
- 같은 지역 단계에서는 관심사 Jaccard 유사도가 높은 후보를 우선한다.
- 동점 후보는 최근 매칭 부하가 낮은 순서로 선택해 특정 사용자에게 매칭이 몰리지 않게 한다.
- 두 번째 성공 매칭부터는 현재 편지를 query로, 후보자가 직접 작성해 전달한 최근 매칭용 편지 최대 5개를 passage로 임베딩한다. 후보자가 받은 편지는 사용하지 않는다.
- `match: false`로 저장된 개인 편지는 후보자 대표 벡터에 포함하지 않는다.
- 한 번이라도 매칭된 상대는 영구적으로 재추천하지 않는다.
- 차단 관계, 비활성 계정, 검열 미완료 콘텐츠를 가진 후보는 제외한다.
- 의미 기반 후보가 부족하면 프로필 기반 매칭을 한 번 수행하고 전략을 `PROFILE_FALLBACK`으로 기록한다.

### 2.2 검열

- 매칭 편지, 개인 편지, 일반 채팅, 피드, 댓글, 회고, 편지·회고 OCR 결과를 검사한다.
- 검사 카테고리는 혐오, 괴롭힘, 성적 콘텐츠, 폭력, 자해, 개인정보 노출, 스팸이다.
- 안전한 콘텐츠는 `ALLOW`, 불확실하거나 운영자 검토가 필요한 콘텐츠는 `REVIEW`, 명백한 위반은 `BLOCK`으로 판정한다.
- Upstage 호출 장애는 `REVIEW`와 동일하게 처리한다.
- `REVIEW` 콘텐츠는 암호화해 격리하며 편지 전달, 채팅 전송, 피드·댓글 공개를 수행하지 않는다.
- 격리 콘텐츠는 지수 백오프로 최대 3회 재검사한다. 계속 실패하면 `MANUAL_REVIEW`로 전환한다.
- `BLOCK` 콘텐츠의 원문은 저장하지 않고 콘텐츠 해시와 판정 메타데이터만 보존한다.
- 수정된 피드와 댓글은 새 버전으로 다시 검열한다. 승인 전까지 기존 공개 버전을 유지하며, 기존 버전이 없는 생성 요청은 노출하지 않는다.

## 3. 기술 선택

### 3.1 권장 구조

- 벡터 저장·검색: PostgreSQL 16 + pgvector
- 임베딩 공급자: Upstage Embed 2 계열
- 검열 분류기: Upstage Solar 채팅 모델의 엄격한 JSON 결과
- 비동기 작업 전달: 기존 transactional outbox 확장
- 격리 원문 암호화: 애플리케이션 계층 AES-GCM

별도 벡터 데이터베이스는 현재 규모에서 운영 복잡도와 동기화 실패 지점을 늘리므로 사용하지 않는다. Python 프로세스에서 전체 후보 벡터를 순회하는 방식도 사용자 증가 시 선형 비용이 발생하므로 사용하지 않는다.

Upstage의 정확한 모델 식별자와 출력 차원은 환경 설정으로 관리한다. 배포 시 `UPSTAGE_EMBEDDING_MODEL`, `UPSTAGE_CHAT_MODEL`, `EMBEDDING_DIMENSIONS`를 반드시 제공한다. 애플리케이션 시작 시 임베딩 응답 차원을 검증하며 불일치하면 준비 상태 검사를 실패시킨다.

참고 자료:

- [Upstage Embed 2 가격 및 기존 Embed 전환 안내](https://www.upstage.ai/pricing/api)
- [Upstage API의 OpenAI 호환 호출 예시](https://console.upstage.ai/api-keys?api=chat)

## 4. 컴포넌트 경계

### `EmbeddingGateway`

- query와 passage 임베딩 호출을 제공한다.
- HTTP 인증, 타임아웃, 응답 파싱, 차원 검증만 담당한다.
- 매칭 정책이나 데이터베이스를 알지 못한다.

### `ModerationGateway`

- 원문과 콘텐츠 유형을 Solar 분류기로 전달한다.
- 엄격한 응답 스키마를 `decision`, `categories`, `severity`, `confidence`, `reason`으로 제한한다.
- 사용자 ID, 이메일, 지역, 인증 토큰을 공급자에게 전달하지 않는다.

### `ModerationOrchestrator`

- 로컬 고정 규칙 검사와 `ModerationGateway`를 순서대로 실행한다.
- `ALLOW`, `REVIEW`, `BLOCK` 상태 전이와 격리 저장을 소유한다.
- 공급자 장애를 `REVIEW`로 변환하고 outbox 재시도 작업을 생성한다.
- 명백한 위반이면서 신뢰도가 `MODERATION_BLOCK_CONFIDENCE` 이상이면 `BLOCK`, 안전 판정의 신뢰도가 `MODERATION_ALLOW_CONFIDENCE` 이상이면 `ALLOW`, 나머지는 `REVIEW`로 정규화한다.

### `MatchingEligibilityPolicy`

- 자기 자신, 차단 관계, 비활성 사용자, 기존 매칭 상대를 제외한다.
- 프로필과 임베딩 정책에서 같은 제외 규칙을 재사용한다.

### `ProfileMatchingPolicy`

- 첫 매칭과 임베딩 부족 폴백을 담당한다.
- 지역 단계를 하나씩 확장하며 후보가 존재하는 첫 단계에서만 점수를 계산한다.
- 관심사 Jaccard 유사도 내림차순, 최근 30일 매칭 수 오름차순, 마지막 매칭 시각 오름차순으로 정렬한다.

### `SemanticMatchingPolicy`

- 현재 편지를 query로 임베딩한다.
- 활성 모델 버전의 사용자 대표 벡터를 코사인 거리로 검색한다.
- 데이터베이스에서 상위 20명을 가져와 공통 자격 정책을 재검증한다.
- `MATCH_MIN_SIMILARITY` 이상인 가장 높은 후보를 반환한다.
- 후보가 없으면 `ProfileMatchingPolicy`를 한 번 호출한다.

### `EmbeddingWorker`

- 검열을 통과하고 실제 전달된 편지만 passage 임베딩한다.
- 사용자별 최신 5개 벡터의 산술 평균을 정규화해 대표 벡터를 갱신한다.
- 동일 콘텐츠 해시와 모델 버전 조합은 다시 호출하지 않는다.

### `LetterDeliveryService`

- 선택된 후보를 `FOR UPDATE SKIP LOCKED`로 잠근다.
- 잠금 이후 자격과 기존 매칭 이력을 다시 확인한다.
- 편지, 양쪽 편지함, 직접 채팅방, 첫 `LETTER` 메시지, 매칭 이력, outbox 이벤트를 하나의 트랜잭션으로 저장한다.

## 5. 데이터 흐름

### 5.1 즉시 검열 성공

1. API가 요청 형식과 멱등키를 검증한다.
2. `ModerationOrchestrator`가 로컬 규칙과 Solar 검열을 수행한다.
3. `ALLOW`이면 기존 생성 서비스로 전달한다.
4. 매칭 요청은 성공 매칭 이력 수에 따라 프로필 또는 의미 정책을 선택한다.
5. 편지 전달 트랜잭션이 완료되면 임베딩 outbox 이벤트를 저장한다.
6. worker가 passage 벡터와 사용자 대표 벡터를 갱신한다.

### 5.2 검열 보류

1. `REVIEW` 또는 공급자 장애가 발생하면 제목, 본문, 카테고리, 대상 ID 등 원래 작업을 재실행하는 데 필요한 정규화 명령 전체를 암호화해 `content_submissions`에 저장한다. Upstage에는 이 중 검열할 텍스트 필드만 보낸다.
2. API는 `202 Accepted`, `submissionId`, `PENDING_REVIEW`를 반환한다.
3. 공개 도메인 테이블에는 아직 콘텐츠를 생성하지 않는다.
4. outbox worker가 1분, 5분, 30분 간격으로 최대 3회 재검사한다.
5. 이후 `ALLOW`이면 원래 명령을 멱등하게 실행하고 `ALLOWED`로 전환한다.
6. `BLOCK`이면 격리 원문을 삭제하고 `BLOCKED`로 전환한다.
7. 재시도가 모두 실패하면 `MANUAL_REVIEW`로 전환한다.

### 5.3 OCR

- 편지와 회고 OCR만 유지한다. 피드 OCR은 제공하지 않는다.
- OCR 원본 이미지는 기존 정책대로 영구 저장하지 않는다.
- 추출 텍스트를 검열한 뒤 `ALLOW`일 때만 반환한다.
- `REVIEW`이면 추출 텍스트만 암호화해 격리하고 원본 이미지는 즉시 폐기한다.

## 6. 데이터 모델

### `match_history`

- `id`, `user_a_id`, `user_b_id`
- `strategy`: `PROFILE`, `SEMANTIC`, `PROFILE_FALLBACK`
- `similarity_score`: 의미 매칭에만 존재하며 일반 사용자 응답에는 노출하지 않는다.
- `model_name`, `model_version`, `created_at`
- 정렬된 사용자 쌍에 유니크 제약을 두어 재추천을 방지한다.

### `letter_embeddings`

- `letter_id`, `owner_id`, `embedding`, `content_hash`
- `model_name`, `model_version`, `dimensions`, `created_at`
- `(letter_id, model_version)` 유니크 제약
- 활성 차원에 맞는 pgvector HNSW cosine 인덱스

### `user_match_vectors`

- `user_id`, `embedding`, `source_letter_ids`, `source_count`
- `model_name`, `model_version`, `updated_at`
- `(user_id, model_version)` 유니크 제약과 HNSW cosine 인덱스

### `content_submissions`

- `id`, `owner_id`, `content_type`, `operation`, `target_id`
- `ciphertext`, `nonce`, `content_hash`, `idempotency_key`
- `status`: `PENDING_REVIEW`, `ALLOWED`, `BLOCKED`, `MANUAL_REVIEW`
- `attempt_count`, `next_attempt_at`, `created_at`, `resolved_at`
- `(owner_id, idempotency_key)` 유니크 제약

### `moderation_decisions`

- `id`, `submission_id`, `decision`, `categories`
- `severity`, `confidence`, `reason`
- `provider`, `model`, `prompt_version`, `created_at`
- 원문은 포함하지 않는다.

## 7. API 계약

### 콘텐츠 생성·수정

- `ALLOW`: 기존 성공 상태 코드와 응답을 유지한다.
- `REVIEW`: `202 Accepted`와 다음 데이터를 반환한다.

```json
{
  "submissionId": "UUID",
  "moderationStatus": "PENDING_REVIEW"
}
```

- `BLOCK`: `422 CONTENT_POLICY_VIOLATION`을 반환한다. 응답에는 정책 카테고리만 포함하고 모델의 내부 설명이나 확률은 노출하지 않는다.

### 편지 매칭 응답

`matching` 객체에 다음 필드를 추가한다.

```json
{
  "matched": true,
  "strategy": "SEMANTIC",
  "fallbackReason": null
}
```

프로필 폴백일 때 `strategy`는 `PROFILE_FALLBACK`, `fallbackReason`은 `INSUFFICIENT_EMBEDDINGS`이다. 유사도 점수는 사용자에게 반환하지 않는다.

### 검열 상태 조회

- `GET /api/v1/moderation-submissions/{submissionId}`
- 작성자 본인의 제출만 조회할 수 있다.
- 응답 상태: `PENDING_REVIEW`, `ALLOWED`, `BLOCKED`, `MANUAL_REVIEW`
- `ALLOWED`이면 생성된 리소스 ID를 함께 반환한다.
- OCR 제출이 `ALLOWED`이면 작성자에게 검열을 통과한 `text`를 반환한다. OCR 텍스트 암호문은 허용 판정 후 24시간 동안만 유지하고 만료 worker가 삭제한다.

### 내부 수동 판정

- `POST /api/v1/internal/moderation-submissions/{submissionId}/decision`
- 일반 사용자 JWT가 아닌 별도 관리자 인증을 사용한다.
- 관리자 ID, 판정, 시각을 감사 로그에 남긴다.

## 8. 오류와 복구

- `409 MATCH_NOT_FOUND`: 프로필 폴백까지 후보가 없음
- `422 CONTENT_POLICY_VIOLATION`: 명백한 정책 위반
- `202 PENDING_REVIEW`: 검토 필요 또는 Upstage 장애
- `503 MODERATION_STORAGE_UNAVAILABLE`: 격리 저장 자체가 실패해 안전하게 보류할 수 없음
- `503 EMBEDDING_SERVICE_UNAVAILABLE`: 이후 매칭의 query 임베딩이 실패하고 프로필 폴백도 수행할 수 없음

Upstage 호출은 짧은 연결·응답 타임아웃을 사용하며, 요청 경로에서 무한 재시도하지 않는다. 공급자 요청 ID와 지연 시간은 기록하지만 원문과 인증 헤더는 기록하지 않는다.

`MODERATION_BLOCK_CONFIDENCE`와 `MODERATION_ALLOW_CONFIDENCE`는 필수 배포 설정이다. shadow 모드에서 수집한 검증 데이터로 값을 결정하고 두 값이 없으면 검열 활성 모드의 준비 상태 검사를 실패시킨다. `MATCH_MIN_SIMILARITY`도 같은 방식으로 활성화 전에 확정하며 설정 누락 시 의미 매칭을 켜지 않는다.

## 9. 보안과 개인정보

- Upstage API 키는 환경 변수 또는 비밀 저장소에서 주입하며 저장소에 커밋하지 않는다.
- 격리 암호화 키는 API 키와 분리한 `MODERATION_ENCRYPTION_KEY`로 관리한다.
- AES-GCM nonce는 매 암호화마다 새로 생성한다.
- 콘텐츠 원문, OCR 이미지, 임베딩 배열, API 키는 로그에 기록하지 않는다.
- 계정 삭제 시 편지 임베딩, 사용자 대표 벡터, 미처리 격리 콘텐츠를 함께 삭제한다.
- 차단 원문의 해시는 서비스 전용 pepper와 함께 계산해 짧은 문자열의 사전 대입을 어렵게 한다.
- 관리자 판정 API는 네트워크 제한, 별도 자격 증명, 감사 로그를 모두 요구한다.

## 10. 관측 가능성

- 매칭 전략별 성공·실패 수
- 지역 확장 단계별 후보 발견 비율
- 의미 유사도 분포와 프로필 폴백 비율
- 임베딩 생성 지연, 실패율, 모델별 비용 추정치
- 검열 판정 비율, 카테고리별 비율, 격리 대기 시간
- 재시도 횟수와 `MANUAL_REVIEW` 전환 수

메트릭 라벨에 사용자 ID나 원문을 포함하지 않는다.

## 11. 테스트 기준

### 단위 테스트

- 지역 단계 확장과 관심사 Jaccard 정렬
- 매칭 부하 동점 처리
- 첫 매칭과 이후 매칭 정책 선택
- 최근 매칭 편지 5개만 대표 벡터에 포함
- 개인 편지 제외
- 차단·비활성·기존 상대 제외
- 최소 유사도와 프로필 폴백
- 로컬 검열 규칙 및 Solar 응답 스키마 검증
- `ALLOW`, `REVIEW`, `BLOCK` 상태 전이
- AES-GCM 암호화·복호화와 변조 거부

### PostgreSQL 통합 테스트

- pgvector cosine 검색 순위
- 활성 모델 버전과 차원 제약
- 동시 매칭에서 후보 중복 점유 방지
- 편지 전달 전체 트랜잭션 롤백
- outbox 이벤트와 도메인 쓰기의 원자성
- 격리 콘텐츠가 공개 조회에 나타나지 않음

### API·계약 테스트

- 콘텐츠별 `201/202/422` 응답
- 멱등키 재요청이 같은 제출 또는 리소스를 반환
- 검열 상태 소유권
- 수정 콘텐츠 재검열
- OCR 결과 검열과 원본 비보존
- 사용자 응답과 로그에 유사도, 임베딩, 개인정보가 포함되지 않음
- OpenAPI 스냅샷 갱신

Upstage 네트워크 호출은 단위·계약 테스트에서 가짜 gateway로 대체한다. 별도 opt-in 통합 테스트만 실제 자격 증명을 사용한다.

## 12. 배포 순서

1. PostgreSQL에 pgvector 확장과 새 테이블을 추가한다.
2. 검열을 기록 전용 shadow 모드로 실행해 판정 분포를 확인한다. shadow 결과는 공개 동작을 바꾸지 않지만 원문은 로그에 남기지 않는다.
3. 검열 차단과 격리를 활성화한다.
4. 기존 검열 통과 매칭 편지를 최신순으로 임베딩한다.
5. 사용자 대표 벡터가 준비된 비율을 확인한다.
6. 첫 매칭 프로필 정책을 활성화한다.
7. 이후 매칭 의미 정책을 점진적으로 활성화한다.

모델명, 차원, 프롬프트 버전, 임계값 변경은 배포 설정으로 추적하며 즉시 이전 값으로 되돌릴 수 있게 한다.

## 13. 범위 제외

- Android 코드 변경
- 별도 운영자 웹 화면
- 자체 임베딩 모델 학습 또는 미세조정
- 외부 벡터 데이터베이스 도입
- 사용자에게 유사도 점수나 검열 신뢰도 노출
