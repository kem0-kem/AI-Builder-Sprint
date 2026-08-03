# Solar Matching and Moderation Plan Index

구현은 아래 순서로 진행한다. 각 계획은 독립적으로 검증하고 커밋할 수 있지만, 매칭 계획은 검열 계획이 제공하는 승인된 편지와 command replay 경계를 전제로 한다.

1. [Solar Content Moderation Implementation Plan](./2026-08-01-solar-moderation-plan.md)
   - Solar 검열 gateway
   - AES-GCM 격리 제출
   - 재시도 및 수동 판정
   - 편지·채팅·피드·댓글·회고·OCR 연결
   - 개인정보, shadow rollout, OpenAPI 검증
2. [Solar Profile and Semantic Matching Implementation Plan](./2026-08-01-solar-profile-semantic-matching-plan.md)
   - pgvector와 Embed 2 gateway
   - 공통 후보 제외 정책
   - 첫 프로필 매칭
   - 최근 발신 편지 5개 대표 벡터
   - 이후 의미 매칭과 프로필 폴백
   - backfill, shadow rollout, 최종 통합 검증

기준 설계는 [Solar 프로필·임베딩 매칭 및 콘텐츠 검열 설계](../specs/2026-08-01-solar-matching-moderation-design.md)다.
