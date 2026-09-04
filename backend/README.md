# Backend

This directory contains the Java 21 and Spring Boot 4.1.1 backend. It provides application startup, PostgreSQL connectivity, Flyway migrations, Hibernate schema validation, Actuator health, an internal GitHub App authentication/client boundary, secure GitHub webhook ingestion, and a durable review-job worker foundation. It contains no pull request processing, review publishing, tenant persistence, or AI integration.

## Requirements

- Java 21 JDK
- Docker with Docker Compose for local PostgreSQL and integration tests
- No global Maven installation; use Maven Wrapper 3.3.4, pinned to Maven 3.9.16

## Run locally

From the repository root, create a private local configuration and start PostgreSQL:

```sh
cp .env.example .env
docker compose --env-file .env -f infra/docker-compose.yml up -d
```

Then start the backend:

```sh
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Windows PowerShell equivalents are:

```powershell
Copy-Item .env.example .env
docker compose --env-file .env -f infra/docker-compose.yml up -d
Set-Location backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

The local profile imports the ignored root `.env` file. Other environments provide `DB_JDBC_URL`, `DB_USERNAME`, and `DB_PASSWORD` directly. Missing required database configuration causes startup to fail; there is no embedded database fallback.

Check readiness at `http://localhost:8080/actuator/health`. Only the health Actuator endpoint is exposed over HTTP, and health details are hidden.

## Test

```sh
./mvnw clean verify
```

On Windows, run `.\mvnw.cmd clean verify`. Integration tests start their own pinned PostgreSQL container and do not use the local Compose database.

Flyway owns schema changes. V1 creates `github_webhook_deliveries`; M4 migration V2 creates `review_jobs` and its polling indexes and state constraints. Hibernate uses `ddl-auto: validate` and never creates or updates the schema.

## GitHub App authentication

M2 requires these server-side values:

- `GITHUB_APP_ID`: the GitHub App ID (GitHub also permits a client ID as the JWT issuer, but M2 names and documents the App ID);
- `GITHUB_PRIVATE_KEY_PATH`: path to a mounted or otherwise protected PEM private-key file;
- `GITHUB_API_BASE_URL`: optional override, defaulting to `https://api.github.com`, primarily for controlled tests and GitHub Enterprise compatibility.

GitHub downloads App keys as PKCS#1 RSA PEM files; both that format and PKCS#8 PEM are supported. Keep the PEM outside the repository, restrict filesystem access to the backend identity, and point `.env` at it for local development. The key file is loaded only when a JWT is needed and its content is never logged or included in errors.

The backend signs a short-lived RS256 App JWT, exchanges it for an installation token, and caches that opaque token by operation-supplied installation ID. A five-minute refresh window prevents use near expiry, and concurrent refreshes for the same installation share one request. The cache is process-local; distributed coordination and Redis are intentionally deferred until multi-instance requirements demonstrate a need.

The only M2 GitHub operation is the internal, read-only accessible-repository count used to prove the boundary. There is no public controller or live-GitHub dependency in automated tests.

## GitHub webhook ingestion

`POST /api/webhooks/github` accepts only `application/json` and requires `X-Hub-Signature-256`, `X-GitHub-Delivery`, and `X-GitHub-Event`. Configure the same random, high-entropy secret in the GitHub App and `GITHUB_WEBHOOK_SECRET`; the App private key and webhook secret are independent credentials. `GITHUB_WEBHOOK_MAX_BODY_SIZE` defaults to `1MB` and may not exceed GitHub's 25 MiB payload cap.

The endpoint reads at most the configured limit plus one byte, verifies HMAC-SHA256 over the exact received bytes, validates bounded opaque delivery/event headers, validates one syntactically valid UTF-8 JSON value, and only then inserts a delivery. It stores the accepted payload as raw UTF-8 `TEXT`, preserving whitespace and key order for audit and future parsing. It never logs or echoes payloads, secrets, or signatures.

New and duplicate valid deliveries return `202 Accepted`. PostgreSQL uniqueness on `github_delivery_id` plus `INSERT ... ON CONFLICT DO NOTHING` makes concurrent redelivery idempotent. A narrow insert transaction commits before acknowledgment. M3 deliberately performs no GitHub API call, event-specific parsing, review job creation, or other downstream work.

## Review-job worker foundation

M4 stores review work in PostgreSQL with `READY`, `PROCESSING`, `COMPLETED`, and `FAILED` states. A JDBC claim transaction selects a bounded due batch in `next_attempt_at`, `created_at`, `id` order using `FOR UPDATE SKIP LOCKED`, increments attempts, and assigns each row a fresh UUID claim token and expiring lease. The transaction commits before the no-op M4 handler runs. Completion, retry, and failure are separate short transactions whose conditional updates require both job ID and the current claim token.

Expired `PROCESSING` leases are reclaimed by a later poll when attempts remain. An expired final attempt becomes `FAILED`. Retryable failures return to `READY` with deterministic `baseDelay * 2^(attempt-1)` backoff capped by the configured maximum; only a bounded safe error code is stored. Terminal failures and exhausted attempts become `FAILED` immediately. No payload, stack trace, external response, credential, or source content is stored in M4 jobs.

The scheduler is disabled by default and invokes a separately testable poll-once worker. Configure it with `REVIEW_WORKER_ENABLED`, `REVIEW_WORKER_POLL_INTERVAL`, `REVIEW_WORKER_BATCH_SIZE`, `REVIEW_WORKER_LEASE_DURATION`, `REVIEW_JOB_MAX_ATTEMPTS`, `REVIEW_JOB_RETRY_BASE_DELAY`, and `REVIEW_JOB_RETRY_MAX_DELAY`. Spring duration syntax such as `5s` and `2m` is accepted. Redis/Kafka, distributed scheduling coordination, webhook-to-job creation, PR fields, and real review handling are intentionally deferred.
