# Claude Code Guide for SlowTalk

Read [AGENTS.md](AGENTS.md) first; it is the authoritative repository-wide instruction file. For implementation or diagnosis, also load the repository skill at [.agents/skills/slowtalk-development/SKILL.md](.agents/skills/slowtalk-development/SKILL.md).

## Project map

- `app/`: Kotlin and Jetpack Compose Android application
- `backend/app/`: FastAPI application and domain modules
- `backend/tests/`: backend unit, contract, integration, AI, matching, and moderation tests
- `docs/superpowers/specs/`: approved design specifications
- `docs/superpowers/plans/`: implementation plans and handoff notes
- `AI_USAGE_EVIDENCE.md`: Upstage API and development-process evidence

## Working agreement

- Inspect the working tree before editing and do not include unrelated emulator artifacts.
- Keep the mobile/backend contract synchronized and add regression coverage for fixes.
- Prefer targeted searches and tests before full suites.
- Do not read, echo, stage, or generate secret-bearing local files.
- Never claim a real Upstage call was made when a mock or development fallback was used.

Use the commands and deployment safeguards in [AGENTS.md](AGENTS.md). Shared Claude Code permissions are stored in [.claude/settings.json](.claude/settings.json); personal overrides belong in the ignored `.claude/settings.local.json`.
