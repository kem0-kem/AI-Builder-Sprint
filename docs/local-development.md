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
