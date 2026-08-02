# Stateless Shadow Moderation Design

## Goal

SlowTalk의 `shadow` moderation 모드는 콘텐츠 정책 판정을 관찰하되 사용자 요청의
성공 동작을 바꾸거나 원문·재실행 명령을 저장하지 않는다. Shadow에서 생성 가능한
영속 데이터는 비식별·저카디널리티 운영 지표뿐이다.

## Scope

이 변경은 `MODERATION_MODE=shadow`의 판정 경로에만 적용한다. `enforce` 모드의
암호화 격리, retry, manual review, command replay, `202` 및 `422` 응답 동작은
변경하지 않는다. 외부 metrics backend 도입과 replay 동시성 개선은 별도 작업이다.

## Architecture

Shadow 전용 orchestrator는 repository에 의존하지 않는다. 다음 네 구성요소만 사용한다.

1. 기존 `LocalRuleEngine`
2. 기존 `ModerationGateway`
3. 기존 assessment 검증 및 local/provider 결합 규칙
4. `ModerationMetrics`

공통 판정 로직은 저장 부작용과 분리한다. Enforce orchestrator는 판정 결과에 따라
repository에 pending 또는 blocked submission을 저장한다. Shadow orchestrator는 같은
판정 결과를 metrics label로 변환한 뒤 항상 immediate outcome을 반환한다.

## Data Flow

1. Router가 기존과 동일한 `ModerationCommand`를 shadow orchestrator에 전달한다.
2. 텍스트를 기존 규칙으로 정규화하고 local rule을 적용한다.
3. Local hard block이면 provider를 호출하지 않고 `BLOCK` 지표를 기록한다.
4. 그 외에는 provider를 한 번 호출하고 응답 schema를 검증한 뒤 local 결과와 결합한다.
5. 판정·category·content type·bounded latency bucket만 metrics에 기록한다.
6. Router에는 immediate outcome을 반환하고 기존 domain command를 정상 실행한다.

Shadow 경로는 `ContentSubmission`, `ModerationDecisionRecord`, retry outbox, encrypted
command를 생성하지 않는다. 따라서 mode를 나중에 `enforce`로 바꿔도 과거 shadow
요청은 조회·승인·replay할 수 없다.

## Provider Failure

Timeout, 연결 오류, 잘못된 schema 등 신뢰할 수 없는 provider 결과는 shadow 요청을
실패시키지 않는다. `provider_failure` 비식별 counter와 latency bucket만 기록하고
immediate outcome을 반환한다. 원문, exception message, provider raw response는 metrics나
로그에 포함하지 않으며 retry와 manual review를 예약하지 않는다.

## Metrics

허용 label은 아래의 enum 또는 bounded 값으로 제한한다.

- content type
- decision: `ALLOW`, `REVIEW`, `BLOCK`, `PROVIDER_FAILURE`
- public policy category
- bounded latency bucket
- configured model identifier는 허용하되 자유 입력값은 사용하지 않는다.

Owner ID, submission ID, idempotency key, content hash, 원문 길이, 원문 및 provider reason은
metrics에 포함하지 않는다. 현재 in-memory metrics의 다중 worker 한계는 유지하며 별도
외부 exporter 작업에서 해결한다.

## Dependency Construction

`get_moderation_orchestrator`는 mode에 따라 명시적으로 다른 구현을 생성한다.

- `shadow`: provider 설정이 완전하면 stateless shadow orchestrator를 반환한다.
- `shadow`: provider 설정이 없으면 기존처럼 `None`을 반환해 개발 fallback을 유지한다.
- `enforce`: repository와 cipher를 포함한 enforce orchestrator를 반환한다.

Shadow orchestrator 생성에는 encryption key와 content hash pepper가 필요하지 않다.
Provider key, model 및 confidence threshold는 판정 일관성을 위해 필요하다.

## Error Handling

- Local rule 오류와 programmer error는 숨기지 않고 요청 오류로 드러낸다.
- 예상된 provider unavailable 및 provider response validation failure만 fail-open 처리한다.
- Metrics 기록 실패가 사용자 요청을 실패시키지 않도록 metrics 구현은 예외를 발생시키지
  않는 bounded in-process 연산만 수행한다.
- Enforce 오류 처리와 공개 API 계약은 그대로 유지한다.

## Tests and Acceptance Criteria

다음 조건을 자동화 테스트로 증명한다.

1. Shadow allow, review, block, provider failure가 모두 immediate outcome을 반환한다.
2. 네 경우 모두 `ContentSubmission`과 moderation decision row가 0건이다.
3. Shadow block에서도 domain row는 기존 API와 동일하게 생성된다.
4. Shadow provider failure는 retry 또는 manual review outbox를 생성하지 않는다.
5. OCR shadow는 텍스트를 정상 반환하지만 암호화 결과를 저장하지 않는다.
6. Metrics에는 예상된 bounded label만 증가하며 원문·사용자 식별자가 없다.
7. Enforce의 기존 pending, blocked, replay 테스트가 모두 그대로 통과한다.
8. Ruff, strict mypy, 전체 pytest 및 OpenAPI snapshot 검사가 통과한다.

## Non-goals

- Shadow 판정 원문 샘플링 또는 별도 분석 저장소
- Shadow 결과에 대한 reviewer UI
- Prometheus/OpenTelemetry exporter 도입
- Replay `EXECUTING` claim 및 optimistic versioning
- Encryption key rotation 또는 per-submission AAD 변경
