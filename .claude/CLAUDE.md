# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

Spring Boot backend (Java 21, Gradle). Build: `./gradlew build`. Tests: `./gradlew test`. Watchlist integration tests (H2 and mocked Redis token storage, no Docker required): `./gradlew test --tests "com.ove.watchlist.*"`. Existing auth integration and collector tests require Docker.

## Project: OvE (AI 의사결정 기반 실시간 투자 보조 서비스)

Real-time coin/stock prices go to a fast-decision AI model. The model returns structured BUY / HOLD / SELL probabilities and a confidence value. Web (React) and app clients show the result as charts and as on-device TTS. Real trading is out of scope. The service offers paper trading only.

Source of truth lives in the Notion "OvE" project page:
- OvE 프로젝트 계획서: scope, architecture, schedule (12-week plan, milestones M1–M4)
- 팀 컨벤션: branch, commit, PR, issue, naming, code style, API response format
- 기능 명세서 (functionalSpecification DB): one row per feature
- API 명세서 (APISpec DB): one row per endpoint, related to the feature rows

Swagger (springdoc) is the final API reference once the backend exists. When an API changes, update the Notion API spec in the same PR.

## Planned architecture

```
Upbit WebSocket / KIS API
  → Spring Boot (ingest, auth, REST, STOMP WebSocket)
      ↔ FastAPI + PyTorch/ONNX model server (internal only, POST /predict, X-API-KEY)
  → PostgreSQL + TimescaleDB (candles, signals), Redis (latest price/signal cache, Pub/Sub, rate limit, refresh tokens)
  → React (Vite, Lightweight Charts, Zustand) / mobile app (React Native or Flutter, undecided) with device TTS
```

- Spring Boot aggregates ticks into 1-minute candles and calls `/predict` each time a candle closes. It stores the result in `signals` and publishes signal changes over Redis Pub/Sub to STOMP topics (`/topic/prices/{symbol}`, `/topic/signals/{symbol}`, `/user/queue/alerts`).
- Model call: 500ms timeout and 1 retry. On failure, keep serving the last signal and mark it `stale` (error `MODEL_UNAVAILABLE`). Latency targets: inference ≤100ms, price-to-screen ≤1s.
- The model server is never public. User-facing API keys (`ove_` prefix, SHA-256 hashed) are separate from the internal model key.
- MVP: 1–3 coin markets (Upbit). Stocks (KIS) come later behind an ingest abstraction.
- Deploy target: GitHub Actions → ECR → EC2 (Docker). Model files and 3D assets go in S3, not Git.

## API conventions (from 팀 컨벤션)

- Base path `/api`. Notion spec URIs omit it, e.g. `/signals/{symbol}`.
- Every response uses the same envelope:
  `{statusCode, timestamp, path, message, data, error}`. Success has `error: null`. Failure has `data: null` and `error` set to an UPPER_SNAKE_CASE code (`INVALID_CREDENTIALS`, `MARKET_NOT_FOUND`, `RATE_LIMIT_EXCEEDED`, ...).
- REST paths use plural nouns in kebab-case. JSON fields are camelCase. DB columns are snake_case. Timestamps are ISO-8601 UTC. Pagination is `page` (0-based) + `size`.
- Auth: `Authorization: Bearer {JWT}` (access 30 min, refresh 14 days with rotation; both values are assumptions).
- Signal values `BUY` / `HOLD` / `SELL` are provisional. The team has not settled HOLD vs 관망.

## Backend code rules (Spring Boot)

- Naming: classes PascalCase, methods/variables camelCase, constants UPPER_SNAKE_CASE. Entity names are singular nouns. Other names follow `{Entity}Repository`, `{기능}Request` / `{기능}Response`, `{도메인}Controller`.
- Use domain packages: `com.ove.{domain}.{controller|service|repository|domain|dto}`.
- Controllers only validate requests and convert responses. Business logic goes in Services. `@Transactional` belongs on the Service layer only.
- Never return Entities from the API. Convert to DTOs.
- Throw custom exceptions (`BusinessException` etc.) and map them to the common envelope in `@ControllerAdvice`.
- Lombok: no `@Data`. Use `@Getter` + `@Builder`.
- Comments may be in Korean. Explain what the code does and why it was written this way.

## Other code style

- FE: Prettier + ESLint, semicolons, single quotes, 4-space indent. Components in `PascalCase.jsx`, hooks in `useXxx.js`, Zustand stores named `use{Domain}Store`.
- AI/Python: PEP 8, Black + isort + Ruff. Training code lives in `.py` files. Notebooks are for experiments only. Split train/validation/test in time order to avoid leakage.

## Git workflow

- Branches: `main` (deploy, no direct push), `dev` (integration), `feature/{domainName}-{detail}`, `fix/{domainName}-{detail}`. Open PRs against `dev`. Each PR needs at least one review before merge.
- Commit format: `type: 작업 내용`. Types: feat, fix, docs, style, refactor, test, chore, design, comment, rename, remove, !HOTFIX.
- PR title format: `[타입] #이슈번호 제목`. Create a GitHub issue before starting work. Labels: FE / BE / AI / APP / 3D / DOC.

## PR review with Claude

- Claude runs only when someone mentions `@claude` in a PR comment, review comment, or review (`.github/workflows/claude-review.yml`). There is no automatic review on PR open or push.
- Each run uses the user's Claude subscription quota. Call it only when needed.
- The bot review is for reference only. It does not replace the one teammate review required before merge.
- Before merging, confirm the `Build and Test` check passed. There is no branch protection; this is a team rule.

## Stock quotes (Kiwoom, optional)

- Demo-only and off by default (`collector.kiwoom.enabled=false`). Uses Kiwoom REST/WebSocket quote APIs only (realtime 0B/0s, token au10001/au10002, minute chart ka10080). Order and account TRs must never be added; `KiwoomTrAllowlist` blocks them.
- App Key/Secret come only from env vars `KIWOOM_APP_KEY` and `KIWOOM_APP_SECRET`. Never put keys or tokens in the repo, tests, or logs. Tests use fake servers and never connect to real Kiwoom.
- Personal-key local demo only. Kiwoom's terms forbid providing quotes to third parties, so the collector refuses to start under the `prod` profile and stock data must not be exposed through public APIs or STOMP.
- Kiwoom Redis keys and channels use the `demo:` prefix (`demo:price:{code}`, `demo:prices:{code}`, `demo:candles.closed:{code}`) so they never mix with coin keys. Stock candles are stored in the `candles` table by 6-digit code.
