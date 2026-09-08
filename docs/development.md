# Development Guide

## Current state

The repository is at Milestone M10. The backend is a Java 21 and Spring Boot 4.1.1 application with PostgreSQL, Flyway, JPA validation, Actuator, Testcontainers, GitHub App authentication, secure webhook/job ingestion, a leased worker, bounded exact-revision context, provider-neutral structured AI transport, candidate review analysis, and deterministic finding suppression. Local infrastructure contains PostgreSQL only. There is no review publication, tenant schema, frontend, OpenAPI specification, or CI/CD workflow.

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

Repository context is controlled by `REVIEW_CONTEXT_ENABLED`, `REVIEW_CONTEXT_MAX_FILES`, `REVIEW_CONTEXT_MAX_FILE_BYTES`, `REVIEW_CONTEXT_MAX_TOTAL_BYTES`, `REVIEW_CONTEXT_MAX_CHANGED_FILE_BYTES`, `REVIEW_CONTEXT_MAX_LINES_PER_FILE`, `REVIEW_CONTEXT_MAX_CANDIDATES`, and `REVIEW_CONTEXT_MAX_API_REQUESTS`. These are source-byte and request limits, not model-token limits. M8 records provider-reported token usage and M9 propagates it without calculating prices.

Common configuration is in `backend/src/main/resources/application.yml`. The local profile in `application-local.yml` imports the ignored root `.env` file. Non-local environments inject `DB_JDBC_URL`, `DB_USERNAME`, and `DB_PASSWORD` directly. Required values have no application defaults, so missing database configuration fails startup instead of selecting an embedded database. Secrets must never be committed, logged, exposed to the frontend, or passed to AI models.

GitHub App authentication additionally requires `GITHUB_APP_ID` and `GITHUB_PRIVATE_KEY_PATH`. `GITHUB_API_BASE_URL` defaults to GitHub's public API and is overridable for tests or compatible enterprise deployments. Installation ID is not global configuration; each internal operation supplies it because one App serves many installations.

Webhook ingestion separately requires `GITHUB_WEBHOOK_SECRET`, set to the same random, high-entropy value configured for the GitHub App webhook. It is not the App private key. `GITHUB_WEBHOOK_MAX_BODY_SIZE` defaults to `1MB`; values must be positive and no greater than GitHub's 25 MiB cap. Keep both credentials outside source control and do not print them. Actuator exposes only health and contains no GitHub credential details.

For local development, download a private key from the GitHub App settings, store it outside this repository with access restricted to your account, and set `GITHUB_PRIVATE_KEY_PATH` in the ignored `.env` to its absolute path. GitHub-generated PKCS#1 PEM and PKCS#8 PEM are accepted. Do not paste PEM content into YAML or `.env`, commit it, print it, or pass it to tests. The repository's `*.pem` ignore rule is defense in depth, not permission to store keys here.

Automated tests use generated ephemeral RSA keys and a loopback HTTP server; they never contact GitHub. A real GitHub smoke test is optional only when an operator has explicitly configured an App and installation. Never request or substitute a personal access token.

Review jobs use the following non-secret configuration. Defaults are shown in `.env.example`:

- `REVIEW_WORKER_ENABLED=false` keeps the scheduled retrieval worker off unless explicitly enabled;
- `REVIEW_WORKER_POLL_INTERVAL=5s` controls the fixed delay between completed polls;
- `REVIEW_WORKER_BATCH_SIZE=10` limits each claim to 1-100 jobs;
- `REVIEW_WORKER_LEASE_DURATION=2m` controls when abandoned `PROCESSING` work becomes reclaimable;
- `REVIEW_JOB_MAX_ATTEMPTS=3` is persisted on each new job and is limited to 1-100;
- `REVIEW_JOB_RETRY_BASE_DELAY=10s` and `REVIEW_JOB_RETRY_MAX_DELAY=5m` define deterministic capped exponential retry delay.

Use Spring duration syntax. All durations must be positive and the retry cap cannot be below the base delay. M4 placeholder creation remains test-only. Enabling the scheduler permits M6 read-only GitHub retrieval for target-bearing jobs; it still performs no analysis or publication.

Pull request retrieval uses these non-secret limits:

- `PR_FETCH_MAX_FILES=1000` (1-3000);
- `PR_FETCH_MAX_PATCH_BYTES_PER_FILE=256KB` (up to 5 MiB);
- `PR_FETCH_MAX_TOTAL_PATCH_BYTES=5MB` (at least the per-file value, up to 100 MiB);
- `PR_FETCH_MAX_PAGES=10` (1-30, 100 files requested per page);
- `PR_FETCH_MAX_RESPONSE_BODY_SIZE=8MB` (per files-page response, up to 32 MiB).

PR/repository metadata responses have a separate fixed 512 KiB bound. A missing patch is valid; an individual patch over its limit is marked unavailable, while file/page/total/response overflow makes the whole load explicitly `TOO_LARGE`. Do not increase limits without memory and workload evidence.

## Database evolution

Flyway is enabled and is the sole schema migration mechanism. V1 creates `github_webhook_deliveries`; V2 creates the leased `review_jobs` queue; V3 adds installation ID, numeric repository ID, PR number, and 40-64 character hexadecimal head object ID. The target columns are all null only for M4 infrastructure fixtures and all present for M5 jobs. Their database unique constraint is the authoritative same-revision idempotency guard. Earlier migrations are unchanged. Integration tests run the full chain against PostgreSQL 18.6 through Testcontainers.

Job creation persists `READY` with attempt zero, an explicit maximum, and `next_attempt_at` equal to the injected clock. A claim transaction first terminalizes expired final attempts, then selects due `READY` and reclaimable `PROCESSING` rows using `FOR UPDATE SKIP LOCKED`, ordered by `next_attempt_at`, `created_at`, and UUID. It increments attempts and assigns a UUID claim token plus lease before commit. The handler executes after claim commit. Completion, retry, and failure each use a separate conditional transaction requiring job ID, `PROCESSING`, and the current token. Retry clears ownership and schedules `base * 2^(attempt-1)` up to the cap. This is at-least-once execution; future external effects must be independently idempotent.

Only validated uppercase safe error codes up to 64 characters are stored. Do not persist or log exception messages, stack traces, payloads, source content, provider response bodies, or credentials. Redis/Kafka, distributed scheduler coordination, priorities, tenant mapping, snapshot/analysis persistence, final finding policy, publication, and cleanup/retention remain deferred.

The endpoint returns `202` for both new and duplicate valid deliveries, `400` for malformed metadata or JSON, `401` for any missing, malformed, or invalid signature, `413` for an oversized body, `415` for unsupported media types, and `5xx` when durable storage fails. Error bodies are empty. After M3 verification, M5 handles only `pull_request` actions `opened`, `reopened`, and `synchronize`. Other events/actions and signed but incomplete relevant payloads commit the webhook without a job and still return `202`. For reviewable payloads, webhook and job insertions share one transaction; job insertion failure rolls both back. Distinct deliveries for one revision remain distinct webhook rows but use one job through PostgreSQL `ON CONFLICT DO NOTHING`.

The production job-creation operation accepts only a validated `ReviewTarget`. M6 supplies its installation ID to the existing M2 token provider, resolves GitHub's mutable owner/name from `GET /repositories/{id}`, verifies that numeric identity, fetches the PR, and compares the returned head SHA before requesting files. `Link` only signals another page; the client increments its own bounded page number on the configured base URL, and redirects are disabled.

Keep `REVIEW_WORKER_ENABLED=false` until tenant policy and publication controls are ready. If enabled, retrieval and analysis run outside the claim transaction. Rate limits, timeouts, and transient GitHub/AI failures return bounded retryable codes; stale revisions, inaccessible resources, malformed responses, large PRs, and terminal provider failures terminate. With AI disabled, context ends at `REVIEW_AI_ANALYSIS_NOT_IMPLEMENTED`. With AI enabled, one M9 call is made and M10 deterministically suppresses candidates without another provider call. A successful validated result—including zero accepted findings—ends at `REVIEW_PUBLISHING_NOT_IMPLEMENTED`, never `COMPLETED`. Context, patches, prompts, outputs, findings, and suppressed content are never persisted.

## AI provider configuration

AI transport is off unless `REVIEW_AI_ENABLED=true`. Disabled startup does not require `OPENAI_API_KEY`; enabled OpenAI startup does. Keep the key in runtime secret configuration and never pass it as a command-line argument, print it, or commit it. `AI_PROVIDER`, `OPENAI_MODEL`, and `OPENAI_REASONING_EFFORT` are operator-controlled, not repository- or customer-controlled.

Defaults bound requests to 120,000 instruction-plus-input characters, a 64 KiB schema, 2,048 output tokens, and 60 seconds. Hard ceilings are 500,000 characters, 256 KiB of schema, 8,192 output tokens, and two minutes. `OPENAI_MAX_RETRIES` accepts only zero or one; with the SDK's initial attempt, the maximum billable attempts are one or two. The adapter uses Responses API strict JSON Schema output, `store=false`, and no tools. Normal tests use the deterministic `FakeAiProvider` and never need network access or credentials.

## Review analysis configuration

`REVIEW_AI_MAX_FINDINGS=5` controls the strict candidate array limit and accepts only 1–10. `REVIEW_AI_MINIMUM_CANDIDATE_CONFIDENCE=70` accepts 0–100; candidates below it are omitted before the in-memory analysis result. These are operator controls, not repository/customer prompt controls. The engine uses the M8 balanced profile, configured economical reasoning, and configured output-token ceiling without hard-coding a provider model.

The serializer emits deterministic JSON with a clear untrusted-data marker, compact PR metadata, canonical changed paths, line-numbered unified diff evidence, deduplicated auxiliary fragments, omission reasons, and bounded budget metadata. It performs no additional GitHub call. Strict schema output is revalidated for count, enum, confidence, field size, exact changed path, and real HEAD/new-side location. Invalid semantic candidates are excluded without invented replacement data; unsafe output structure terminates with `AI_INVALID_OUTPUT`.

Automated tests use `FakeAiProvider` and make no paid/network calls. Once publication exists, analysis and publishing should have a recoverable boundary so a publication-only retry does not pay for repeated AI generation. M9 deliberately does not introduce that persistence or job split.

## Finding suppression configuration

`REVIEW_SUPPRESSION_MINIMUM_CONFIDENCE=85`, `REVIEW_SUPPRESSION_MINIMUM_SEVERITY=MEDIUM`, and `REVIEW_SUPPRESSION_MAX_PUBLISHABLE_FINDINGS=3` define the initial conservative operator policy. Confidence is restricted to 0–100, severity is the controlled review enum, and the publication-candidate cap is restricted to 1–5. These values are not repository-controlled.

The fixed deterministic pipeline assigns the first failed gate as the one aggregate suppression reason. Same-category textual overlap requires an overlapping location, at least four shared normalized tokens, and Jaccard similarity of at least 0.60; there are no embeddings or external libraries. Model confidence is an input to policy, not proof that a finding is correct. Do not tune these defaults without beta evidence.

## Troubleshooting and completion

When a command or test fails, preserve the original error, isolate whether the cause is code, configuration, dependency, or environment, and document any unmet prerequisite. Before handing off work, inspect the diff and report exact changes, verification performed, skipped checks, unresolved issues, and architectural impact.
