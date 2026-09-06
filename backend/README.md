# Backend

This directory contains the Java 21 and Spring Boot 4.1.1 backend. It provides application startup, PostgreSQL connectivity, Flyway migrations, Hibernate schema validation, Actuator health, GitHub App authentication, secure webhook/job ingestion, a durable worker, and bounded exact-revision pull request retrieval. It contains no review analysis, publishing, tenant persistence, arbitrary repository-context retrieval, or AI integration.

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

Flyway owns schema changes. V1 creates `github_webhook_deliveries`; V2 creates `review_jobs` and its polling indexes and state constraints; M5 migration V3 adds the optional all-or-none GitHub review target and its unique constraint. The nullable shape preserves M4 infrastructure-only placeholder jobs, while every M5 production job has all four target fields. Hibernate uses `ddl-auto: validate` and never creates or updates the schema.

## GitHub App authentication

M2 requires these server-side values:

- `GITHUB_APP_ID`: the GitHub App ID (GitHub also permits a client ID as the JWT issuer, but M2 names and documents the App ID);
- `GITHUB_PRIVATE_KEY_PATH`: path to a mounted or otherwise protected PEM private-key file;
- `GITHUB_API_BASE_URL`: optional override, defaulting to `https://api.github.com`, primarily for controlled tests and GitHub Enterprise compatibility.

GitHub downloads App keys as PKCS#1 RSA PEM files; both that format and PKCS#8 PEM are supported. Keep the PEM outside the repository, restrict filesystem access to the backend identity, and point `.env` at it for local development. The key file is loaded only when a JWT is needed and its content is never logged or included in errors.

The backend signs a short-lived RS256 App JWT, exchanges it for an installation token, and caches that opaque token by operation-supplied installation ID. A five-minute refresh window prevents use near expiry, and concurrent refreshes for the same installation share one request. The cache is process-local; distributed coordination and Redis are intentionally deferred until multi-instance requirements demonstrate a need.

M2's read-only accessible-repository operation remains. M6 adds internal read-only repository metadata, pull request metadata, and changed-file operations. All use operation-supplied installation IDs and the same cached opaque installation-token boundary; there is no public controller or live-GitHub dependency in automated tests.

## Pull request retrieval

M6 first calls `GET /repositories/{repository_id}` to resolve the authenticated owner/name required by GitHub's pull endpoints, verifies the numeric ID, then calls `GET /repos/{owner}/{repo}/pulls/{number}` and verifies the returned PR number, base-repository ID, and exact expected head SHA. A changed head returns `STALE` before any file request. Files come from `GET /repos/{owner}/{repo}/pulls/{number}/files?per_page=100&page=N`. The client reads only whether the `Link` header contains `rel="next"`; it derives the next request itself and never follows a response-provided URL. HTTP redirects are disabled.

The default ceilings are 1,000 files, 10 pages, 256 KiB per retained patch, 5 MiB total patch input, and 8 MiB per files response. Metadata responses are capped at 512 KiB. Configure these with `PR_FETCH_MAX_FILES`, `PR_FETCH_MAX_PAGES`, `PR_FETCH_MAX_PATCH_BYTES_PER_FILE`, `PR_FETCH_MAX_TOTAL_PATCH_BYTES`, and `PR_FETCH_MAX_RESPONSE_BODY_SIZE`. Exceeding a whole-PR/response limit returns `TOO_LARGE`; an individual oversized patch is explicitly unavailable and never silently truncated. Missing patches, including binary files, are valid metadata-only entries.

Snapshots live only in memory and contain numeric identity, head/base SHAs, draft state, and bounded changed-file metadata. Repository paths remain opaque strings: they are never resolved or opened locally. Patches are untrusted data, never logged or placed in errors, and are not interpreted as instructions. No raw blobs or surrounding files are fetched.

## GitHub webhook ingestion

`POST /api/webhooks/github` accepts only `application/json` and requires `X-Hub-Signature-256`, `X-GitHub-Delivery`, and `X-GitHub-Event`. Configure the same random, high-entropy secret in the GitHub App and `GITHUB_WEBHOOK_SECRET`; the App private key and webhook secret are independent credentials. `GITHUB_WEBHOOK_MAX_BODY_SIZE` defaults to `1MB` and may not exceed GitHub's 25 MiB payload cap.

The endpoint reads at most the configured limit plus one byte, verifies HMAC-SHA256 over the exact received bytes, validates bounded opaque delivery/event headers, validates one syntactically valid UTF-8 JSON value, and only then inserts a delivery. It stores the accepted payload as raw UTF-8 `TEXT`, preserving whitespace and key order for audit and future parsing. It never logs or echoes payloads, secrets, or signatures.

New and duplicate valid deliveries return `202 Accepted`. PostgreSQL uniqueness on `github_delivery_id` plus `INSERT ... ON CONFLICT DO NOTHING` makes concurrent redelivery idempotent. M5 interprets only `pull_request` actions `opened`, `reopened`, and `synchronize`; other events and actions are accepted without jobs. A relevant event uses GitHub's top-level `number` plus `installation.id`, numeric `repository.id`, and `pull_request.head.sha`. Signed but incomplete relevant events are retained without jobs and acknowledged because redelivery cannot repair their schema.

For a new valid reviewable event, webhook insertion and conflict-safe review-job insertion share one short transaction. A job failure rolls back the webhook, so GitHub receives a server failure and can redeliver. Duplicate deliveries do not reprocess; distinct deliveries for the same target still persist while the unique `(installation, repository, PR number, head SHA)` key creates only one job. Signature verification and JSON validation still occur before this transaction, and no GitHub API call or review work runs in the request.

## Review-job worker foundation

M4 stores review work in PostgreSQL with `READY`, `PROCESSING`, `COMPLETED`, and `FAILED` states. A JDBC claim transaction selects a bounded due batch in `next_attempt_at`, `created_at`, `id` order using `FOR UPDATE SKIP LOCKED`, increments attempts, and assigns each row a fresh UUID claim token and expiring lease. The transaction commits before retrieval runs. Completion, retry, and failure are separate short transactions whose conditional updates require both job ID and the current claim token.

Expired `PROCESSING` leases are reclaimed by a later poll when attempts remain. An expired final attempt becomes `FAILED`. Retryable failures return to `READY` with deterministic `baseDelay * 2^(attempt-1)` backoff capped by the configured maximum; only a bounded safe error code is stored. Terminal failures and exhausted attempts become `FAILED` immediately. No payload, stack trace, external response, credential, or source content is stored in M4 jobs.

The scheduler is disabled by default and invokes a separately testable poll-once worker. Configure it with `REVIEW_WORKER_ENABLED`, `REVIEW_WORKER_POLL_INTERVAL`, `REVIEW_WORKER_BATCH_SIZE`, `REVIEW_WORKER_LEASE_DURATION`, `REVIEW_JOB_MAX_ATTEMPTS`, `REVIEW_JOB_RETRY_BASE_DELAY`, and `REVIEW_JOB_RETRY_MAX_DELAY`. Rate-limit and transient GitHub failures retry; stale revisions, inaccessible resources, malformed responses, and excessive PRs terminate with bounded codes. Successful retrieval ends as `REVIEW_ANALYSIS_NOT_IMPLEMENTED`, never `COMPLETED`. Redis/Kafka, analysis, surrounding-file context, snapshot persistence, and review publication remain deferred.
