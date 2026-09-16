# Development Guide

## Current state

The backend is complete through M15. M13E adds real read-only review history to the Node.js 24 LTS / Next.js 16.3.5 dashboard. It contains no review detail/retry, repository mutation, usage/settings feature, organization ownership flow, billing, CI/CD workflow, or deployment definition.

## Prerequisites

- Java 21 JDK available through `JAVA_HOME` or `PATH`
- Docker with Docker Compose
- Git
- Node.js 24 LTS and npm 11.19.1 for frontend work

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

## Local frontend workflow

From `frontend`, use the committed lockfile:

```sh
npm ci
npm run dev
```

Run `npm run lint`, `npm run typecheck`, `npm test`, and `npm run build` before claiming frontend completion. `BACKEND_BASE_URL` is server-only; development/test default to `http://127.0.0.1:8080`, while production requires an explicit HTTPS origin. Configure Auth0 using the server-only variables in `.env.example`; generate `AUTH0_SECRET` with 32 random bytes encoded as 64 hexadecimal characters. No auth or product secret may use `NEXT_PUBLIC_*`.

Production CSP uses a fresh request nonce and permits neither `unsafe-inline` nor `unsafe-eval`. Development permits `unsafe-eval` only for Next.js tooling. The same proxy dispatches the restricted Auth0 routes, rejects non-fixed login return targets, and preserves the M13A headers. `/dashboard` uses the server session to call Spring; tokens must stay in server-only modules.

The backend requires `DASHBOARD_AUTH_ISSUER`, `DASHBOARD_AUTH_AUDIENCE`, and `DASHBOARD_AUTH_JWK_SET_URI`. The issuer and JWK URI must be same-origin HTTPS (loopback HTTP is test/local only). Keep the configured audience identical to `AUTH0_AUDIENCE`. Blank auth configuration leaves dashboard requests closed; partial configuration prevents startup. The browser must not call Spring directly and no CORS allowance is needed.

For local dashboard checks, `/dashboard` renders sign-in when no session exists. An authenticated membership-less user gets the closed GitHub-connection onboarding state. Real memberships render the Overview shell. Multiple memberships may be selected with the native workspace form; the resulting `tenant` query value is matched against the fresh authenticated DTO on the server and is never stored in browser storage or treated as authorization. `/dashboard/repositories` and `/dashboard/reviews` make separate server-only calls to protected endpoints that repeat membership authorization. Reviews uses bounded keyset navigation with `cursor`; duplicate, malformed, overlong, or cross-tenant cursors fail closed. Neither page calls GitHub or an AI provider. Usage and Settings remain disabled labels.

For M13D1, register the exact `${APP_BASE_URL}/github/callback` URL on the existing GitHub App and configure `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET`, and `GITHUB_OAUTH_CALLBACK_URL`. The client ID differs from `GITHUB_APP_ID`. Production callback and OAuth origins require HTTPS; local loopback HTTP is accepted for development. Never bootstrap with email/domain matching or a submitted tenant, installation, repository, organization, username, or role. The setup URL may point to `/github/setup`, but its `installation_id` is deliberately ignored.

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

`REVIEW_USAGE_MONTHLY_LIMIT` is the operator-owned M15 AI-analysis quota per tenant and UTC calendar month. It defaults to `50` and must be between 1 and 100000. Repository configuration, webhook payloads, model output, and future tenant clients cannot override it. Changing the value affects subsequent reservation decisions; it does not rewrite ledger history.

Common configuration is in `backend/src/main/resources/application.yml`. The local profile in `application-local.yml` imports the ignored root `.env` file. Non-local environments inject `DB_JDBC_URL`, `DB_USERNAME`, and `DB_PASSWORD` directly. Required values have no application defaults, so missing database configuration fails startup instead of selecting an embedded database. Secrets must never be committed, logged, exposed to the frontend, or passed to AI models.

### Repository-owned review policy

The only repository configuration filename is `.reviewbot.yml`. It is fetched through the existing installation-token client from the authenticated base repository at the exact target base SHA—not the head SHA, a branch, default branch, local checkout, URL, or included file. Changed code continues to use the exact head SHA. For fork PRs, the contributor's fork is never consulted for policy. Consequently, a PR that edits configuration is reviewed under the pre-change base policy; after merge, later PRs based on that revision use the new policy. `REPOSITORY_CONFIG_MAX_SIZE` defaults to `32KB` and is constrained to 64 KiB or less. Operators can lower that ceiling but repositories cannot raise it.

The canonical version-1 schema and example are documented in the root README. Keys are lowercase and strict: `version`, `review.mode`, `ignore`, and the seven existing `categories` booleans. YAML booleans must be booleans; enum values are exactly `fast`, `balanced`, or `deep`. Unknown keys, duplicate keys, custom tags, aliases, malformed/empty YAML, wrong types, unknown categories, or unsupported versions never become partially trusted policy. Missing and invalid configuration use centralized balanced/all-enabled defaults. Existing GitHub authentication, access, rate-limit, and transient job classifications continue to propagate.

Ignore syntax is a deliberately small logical-path matcher: literals, `*`, `**`, and `?`, using `/` only. Blank, absolute-like, control-bearing, backslash, empty-segment, dot-segment, and malformed `**` patterns are rejected. Limits are 50 patterns, 256 characters per pattern, and 4,096 total pattern characters. Matching does not use filesystem paths, regex translation, shell commands, network access, or repository traversal.

`FAST` uses half the configured M7 file/byte/candidate/request ceilings; `BALANCED` preserves them; `DEEP` currently equals those operator ceilings rather than increasing cost. All modes retain the same validation, suppression, one-call maximum, model, output cap, and publication cap. If all categories are false or filtering leaves no reviewable files, processing succeeds without an AI call or publication. Once M11 handoff exists, publication retries do not load this file.

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

Flyway is enabled and is the sole schema migration mechanism. V1 creates `github_webhook_deliveries`; V2 creates the leased `review_jobs` queue; V3 adds the exact GitHub review target and its same-revision uniqueness guard; V4 adds immutable publication records and the separate leased publication queue; V5 creates tenant, installation, and repository ownership and adds tenant associations to jobs/publications; V6 creates tenant usage accounting and its same-tenant job constraint. Earlier migrations are unchanged. Integration tests run the full chain against PostgreSQL 18.6 through Testcontainers and verify populated V5 state upgrades without fabricated usage.

M14 uses controlled lazy provisioning because no user-authenticated installation lifecycle exists yet. After signature and payload validation, a reviewable webhook supplies authoritative numeric installation/repository IDs. The internal service obtains transaction-scoped advisory locks, resolves or creates the installation's tenant and repository mapping, then creates the tenant-associated job inside the same acceptance transaction. Concurrent first use converges on one mapping. Repository owner/name is never consulted, so rename has no ownership effect; unexpected transfer/reassignment fails closed until an authenticated reconciliation workflow is designed.

V5's composite keys enforce same-tenant installation/repository, review-job/repository, publication/repository, and publication/analysis-job ownership. New production review jobs and publications always populate both tenant columns. Historical rows keep both nullable as a deliberate expand step because M14 cannot infer their SaaS account owner; worker/publication services return `TENANT_NOT_RESOLVED` before external calls. RLS is deferred because there is no user/session request context yet, and tenant-wide status/suspension is deferred because there is no management workflow.

Job creation persists `READY` with attempt zero, an explicit maximum, and `next_attempt_at` equal to the injected clock. A claim transaction first terminalizes expired final attempts, then selects due `READY` and reclaimable `PROCESSING` rows using `FOR UPDATE SKIP LOCKED`, ordered by `next_attempt_at`, `created_at`, and UUID. It increments attempts and assigns a UUID claim token plus lease before commit. The handler executes after claim commit. Completion, retry, and failure each use a separate conditional transaction requiring job ID, `PROCESSING`, and the current token. Retry clears ownership and schedules `base * 2^(attempt-1)` up to the cap. This is at-least-once execution; future external effects must be independently idempotent.

Only validated uppercase safe error codes up to 64 characters are stored. Publication V1 payloads retain only sanitized accepted output and trusted routing/target metadata. Do not persist or log exception messages, stack traces, webhook payloads, source, patches, prompts, raw provider output, rejected findings, provider response bodies, or credentials. Redis/Kafka, distributed scheduler coordination, priorities, tenant lifecycle/transfer reconciliation, cleanup/retention, and customer deletion controls remain deferred.

The endpoint returns `202` for both new and duplicate valid deliveries, `400` for malformed metadata or JSON, `401` for any missing, malformed, or invalid signature, `413` for an oversized body, `415` for unsupported media types, and `5xx` when durable storage fails. Error bodies are empty. After M3 verification, M5 handles only `pull_request` actions `opened`, `reopened`, and `synchronize`. Other events/actions and signed but incomplete relevant payloads commit the webhook without a job and still return `202`. For reviewable payloads, webhook and job insertions share one transaction; job insertion failure rolls both back. Distinct deliveries for one revision remain distinct webhook rows but use one job through PostgreSQL `ON CONFLICT DO NOTHING`.

The production job-creation operation accepts only a validated `ReviewTarget`. M6 supplies its installation ID to the existing M2 token provider, resolves GitHub's mutable owner/name from `GET /repositories/{id}`, verifies that numeric identity, fetches the PR, and compares the returned head SHA before requesting files. `Link` only signals another page; the client increments its own bounded page number on the configured base URL, and redirects are disabled.

Keep `REVIEW_WORKER_ENABLED=false` and `REVIEW_PUBLICATION_ENABLED=false` until tenant policy and production permission controls are ready. Analysis and publication work execute outside claim transactions. Zero accepted findings complete with no publication record or GitHub call. Non-empty output is handed to a separate durable publication queue; publication retries never rerun AI. Uncertain POST outcomes reconcile the exact marker through bounded review-list pages before another POST.

M15's `REVIEW_USAGE_MONTHLY_LIMIT` defaults to `50` and permits 1 through 100000. Before AI, a short transaction obtains a tenant/month PostgreSQL advisory lock, counts `RESERVED` plus `CONSUMED` events in the UTC half-open month, and inserts one reservation keyed by review job and usage type. The AI request runs after commit. A separate transaction consumes the event and records only bounded optional provider-reported metadata. Publication retries, no-AI policy paths, and stale work do not touch usage.

An existing `CONSUMED`, `RELEASED`, or unresolved `RESERVED` event prevents another provider invocation. Active ambiguous reservations count against quota because the service cannot prove whether an interrupted external call was billed. No automated expiry is safe until reconciliation can distinguish pre-call crashes from lost post-call responses. Manual/automated reconciliation, stale release policy, pricing, invoices, plans, and tenant-facing reporting are deferred.

## AI provider configuration

AI transport is off unless `REVIEW_AI_ENABLED=true`. Disabled startup does not require `OPENAI_API_KEY`; enabled OpenAI startup does. Keep the key in runtime secret configuration and never pass it as a command-line argument, print it, or commit it. `AI_PROVIDER`, `OPENAI_MODEL`, and `OPENAI_REASONING_EFFORT` are operator-controlled, not repository- or customer-controlled.

Defaults bound requests to 120,000 instruction-plus-input characters, a 64 KiB schema, 2,048 output tokens, and 60 seconds. Hard ceilings are 500,000 characters, 256 KiB of schema, 8,192 output tokens, and two minutes. `OPENAI_MAX_RETRIES` accepts only zero or one; with the SDK's initial attempt, the maximum billable attempts are one or two. The adapter uses Responses API strict JSON Schema output, `store=false`, and no tools. Normal tests use the deterministic `FakeAiProvider` and never need network access or credentials.

## Review analysis configuration

`REVIEW_AI_MAX_FINDINGS=5` controls the strict candidate array limit and accepts only 1–10. `REVIEW_AI_MINIMUM_CANDIDATE_CONFIDENCE=70` accepts 0–100; candidates below it are omitted before the in-memory analysis result. These are operator controls, not repository/customer prompt controls. The engine uses the M8 balanced profile, configured economical reasoning, and configured output-token ceiling without hard-coding a provider model.

The serializer emits deterministic JSON with a clear untrusted-data marker, compact PR metadata, canonical changed paths, line-numbered unified diff evidence, deduplicated auxiliary fragments, omission reasons, and bounded budget metadata. It performs no additional GitHub call. Strict schema output is revalidated for count, enum, confidence, field size, exact changed path, and real HEAD/new-side location. Invalid semantic candidates are excluded without invented replacement data; unsafe output structure terminates with `AI_INVALID_OUTPUT`.

Automated tests use `FakeAiProvider` and a loopback GitHub server, make no paid calls, and perform no real GitHub writes. M11's durable handoff and separate publication queue ensure publication-only retries do not repeat paid generation.

## Finding suppression configuration

`REVIEW_SUPPRESSION_MINIMUM_CONFIDENCE=85`, `REVIEW_SUPPRESSION_MINIMUM_SEVERITY=MEDIUM`, and `REVIEW_SUPPRESSION_MAX_PUBLISHABLE_FINDINGS=3` define the initial conservative operator policy. Confidence is restricted to 0–100, severity is the controlled review enum, and the publication-candidate cap is restricted to 1–5. These values are not repository-controlled.

The fixed deterministic pipeline assigns the first failed gate as the one aggregate suppression reason. Same-category textual overlap requires an overlapping location, at least four shared normalized tokens, and Jaccard similarity of at least 0.60; there are no embeddings or external libraries. Model confidence is an input to policy, not proof that a finding is correct. Do not tune these defaults without beta evidence.

## Review publication configuration

GitHub review writes require the GitHub App repository permission `Pull requests: write`. `REVIEW_PUBLICATION_ENABLED` defaults to `false`. Non-empty validated output is still handed off durably while disabled; only the scheduler and GitHub write are withheld, so later enablement cannot require another AI call. Summary, comment, encoded-payload, reconciliation-page, attempt, backoff, polling, batch, and lease limits use the `REVIEW_PUBLICATION_*` variables in `.env.example`. Enabling analysis alone does not enable GitHub writes.

The publisher sends one `COMMENT` review with `commit_id` equal to the immutable job SHA. Single-line comments use `line` and `side=RIGHT`; multiline comments also use `start_line` and `start_side=RIGHT`. Do not use deprecated diff `position`. File-level findings are summary items. A local or automated test must use the mock GitHub server; a real smoke test is optional and must use explicitly supplied GitHub App credentials.

## Troubleshooting and completion

When a command or test fails, preserve the original error, isolate whether the cause is code, configuration, dependency, or environment, and document any unmet prerequisite. Before handing off work, inspect the diff and report exact changes, verification performed, skipped checks, unresolved issues, and architectural impact.
