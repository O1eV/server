# AGENTS.md

This file provides guidance to AI coding agents (e.g. OpenAI Codex) when working with code in this repository. Keep it in sync with `.claude/CLAUDE.md`.

## Status

This directory has no source code yet (as of 2026-10-19). There are no build, lint, or test commands to run. When code is added, update this file with the real commands (Spring Boot build/test, React dev server, FastAPI model server, single-test invocations).

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
