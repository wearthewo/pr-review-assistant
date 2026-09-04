# Development Guide

## Current state

The repository is at Milestone M4. The backend is a Java 21 and Spring Boot 4.1.1 application with PostgreSQL connectivity, Flyway, JPA schema validation, Actuator health, Testcontainers tests, internal GitHub App authentication, secure durable webhook ingestion, and a PostgreSQL review-job worker foundation. Local infrastructure contains PostgreSQL only. There is no PR review processing, tenant schema, frontend, OpenAPI specification, AI integration, or CI/CD workflow.

## Prerequisites

- Java 21 JDK available through `JAVA_HOME` or `PATH`
- Docker with Docker Compose
- Git

The backend includes Maven Wrapper 3.3.4 pinned to Maven 3.9.16, so a global Maven installation is unnecessary. The local and test database image is PostgreSQL `18.6-bookworm`.

## Working in the monorepo

- Start with [AGENTS.md](../AGENTS.md), then read architecture, security, testing, and all ADRs.
- Work only within the current milestone and keep changes focused.
- Use repository-owned wrappers and scripts once introduced; do not require undocumented global tools.
- Keep local secrets in ignored environment files or an approved secret mechanism. `.env.example` must contain safe placeholders only.
- Use synthetic data and controlled external-service doubles for routine development.
- Update documentation alongside behavior and record material architecture changes with a superseding ADR.

## Local backend workflow

From the repository root:

```sh
cp .env.example .env
docker compose --env-file .env -f infra/docker-compose.yml up -d
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

On Windows PowerShell:

```powershell
Copy-Item .env.example .env
docker compose --env-file .env -f infra/docker-compose.yml up -d
Set-Location backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

The health endpoint is `http://localhost:8080/actuator/health`. The database health contributor is enabled, only the health endpoint is exposed over HTTP, and details are hidden.

Stop local infrastructure from the repository root without deleting the named volume:

```sh
docker compose --env-file .env -f infra/docker-compose.yml down
```

## Running tests

From `backend`:

```sh
./mvnw clean verify
```

On Windows, use `.\mvnw.cmd clean verify`. The test suite starts a pinned PostgreSQL Testcontainer, connects Spring to it, runs Flyway, validates the empty schema through Hibernate, and shuts the container down through the normal Testcontainers lifecycle. It does not require the Compose service to be running.

## Configuration principles

Common configuration is in `backend/src/main/resources/application.yml`. The local profile in `application-local.yml` imports the ignored root `.env` file. Non-local environments inject `DB_JDBC_URL`, `DB_USERNAME`, and `DB_PASSWORD` directly. Required values have no application defaults, so missing database configuration fails startup instead of selecting an embedded database. Secrets must never be committed, logged, exposed to the frontend, or passed to AI models.

GitHub App authentication additionally requires `GITHUB_APP_ID` and `GITHUB_PRIVATE_KEY_PATH`. `GITHUB_API_BASE_URL` defaults to GitHub's public API and is overridable for tests or compatible enterprise deployments. Installation ID is not global configuration; each internal operation supplies it because one App serves many installations.

Webhook ingestion separately requires `GITHUB_WEBHOOK_SECRET`, set to the same random, high-entropy value configured for the GitHub App webhook. It is not the App private key. `GITHUB_WEBHOOK_MAX_BODY_SIZE` defaults to `1MB`; values must be positive and no greater than GitHub's 25 MiB cap. Keep both credentials outside source control and do not print them. Actuator exposes only health and contains no GitHub credential details.

For local development, download a private key from the GitHub App settings, store it outside this repository with access restricted to your account, and set `GITHUB_PRIVATE_KEY_PATH` in the ignored `.env` to its absolute path. GitHub-generated PKCS#1 PEM and PKCS#8 PEM are accepted. Do not paste PEM content into YAML or `.env`, commit it, print it, or pass it to tests. The repository's `*.pem` ignore rule is defense in depth, not permission to store keys here.

Automated tests use generated ephemeral RSA keys and a loopback HTTP server; they never contact GitHub. A real GitHub smoke test is optional only when an operator has explicitly configured an App and installation. Never request or substitute a personal access token.

Review jobs use the following non-secret configuration. Defaults are shown in `.env.example`:

- `REVIEW_WORKER_ENABLED=false` keeps the M4 no-op scheduler off unless explicitly enabled;
- `REVIEW_WORKER_POLL_INTERVAL=5s` controls the fixed delay between completed polls;
- `REVIEW_WORKER_BATCH_SIZE=10` limits each claim to 1-100 jobs;
- `REVIEW_WORKER_LEASE_DURATION=2m` controls when abandoned `PROCESSING` work becomes reclaimable;
- `REVIEW_JOB_MAX_ATTEMPTS=3` is persisted on each new job and is limited to 1-100;
- `REVIEW_JOB_RETRY_BASE_DELAY=10s` and `REVIEW_JOB_RETRY_MAX_DELAY=5m` define deterministic capped exponential retry delay.

Use Spring duration syntax. All durations must be positive and the retry cap cannot be below the base delay. M4 job creation is an internal service used by tests only; enabling the scheduler has no GitHub or webhook integration and runs a no-op handler.

## Database evolution

Flyway is enabled and is the sole schema migration mechanism. M3's V1 migration creates the minimal `github_webhook_deliveries` table with a database-unique GitHub delivery ID. M4's V2 migration creates `review_jobs`, state-shape and attempt constraints, and partial ready/processing polling indexes; V1 is unchanged. Hibernate is configured with `ddl-auto: validate`; it cannot create or update the schema. Add a new versioned migration for every future schema change and never edit a migration that may have run outside a disposable local environment. Integration tests run Flyway against PostgreSQL through Testcontainers.

Job creation persists `READY` with attempt zero, an explicit maximum, and `next_attempt_at` equal to the injected clock. A claim transaction first terminalizes expired final attempts, then selects due `READY` and reclaimable `PROCESSING` rows using `FOR UPDATE SKIP LOCKED`, ordered by `next_attempt_at`, `created_at`, and UUID. It increments attempts and assigns a UUID claim token plus lease before commit. The handler executes after claim commit. Completion, retry, and failure each use a separate conditional transaction requiring job ID, `PROCESSING`, and the current token. Retry clears ownership and schedules `base * 2^(attempt-1)` up to the cap. This is at-least-once execution; future external effects must be independently idempotent.

Only validated uppercase safe error codes up to 64 characters are stored. Do not persist or log exception messages, stack traces, payloads, source content, provider response bodies, or credentials. Redis/Kafka, distributed scheduler coordination, priorities, webhook-to-job wiring, tenant/installation ownership, PR reference fields, and cleanup/retention policy remain deferred.

The endpoint returns `202` for both new and duplicate valid deliveries, `400` for malformed metadata or JSON, `401` for any missing, malformed, or invalid signature, `413` for an oversized body, `415` for unsupported media types, and `5xx` when durable storage fails. Error bodies are empty. PostgreSQL `ON CONFLICT DO NOTHING`, backed by the unique constraint, handles concurrent redelivery without a select/insert race.

## Troubleshooting and completion

When a command or test fails, preserve the original error, isolate whether the cause is code, configuration, dependency, or environment, and document any unmet prerequisite. Before handing off work, inspect the diff and report exact changes, verification performed, skipped checks, unresolved issues, and architectural impact.
