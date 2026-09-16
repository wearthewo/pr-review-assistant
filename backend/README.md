# Backend

This directory contains the Java 21 and Spring Boot 4.1.1 backend. In addition to the review pipeline and authenticated ownership boundary, M13D2 provides a tenant-repository API and M13E provides a bounded read-only review-history API. It contains no repository mutation, review detail, usage, billing, or tenant-management dashboard API.

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

Flyway owns schema changes. V1-V7 retain their accepted responsibilities; V8 adds only short-lived `github_connection_states` with hashed state, application-user binding, PKCE verifier, expiry, and atomic consumption metadata. It stores no GitHub user token. Earlier migrations remain immutable. Hibernate uses `ddl-auto: validate` and never creates or updates the schema.

## Dashboard authentication and authorization

`GET /api/dashboard/session` is the only M13B dashboard API. It requires an Auth0 API access JWT. Configure `DASHBOARD_AUTH_ISSUER`, `DASHBOARD_AUTH_AUDIENCE`, and the same-origin HTTPS `DASHBOARD_AUTH_JWK_SET_URI`. Spring Security Resource Server accepts RS256 only and validates signature, expiry, not-before, issuer, and audience. Blank configuration fails closed for dashboard requests; partial or unsafe configuration fails startup. The endpoint returns only the internal application-user UUID, controlled membership roles, tenant UUIDs for actual memberships, and whether secure onboarding is required.

External human identity is the bounded `(auth_issuer, auth_subject)` pair, never email. First-login provisioning uses PostgreSQL uniqueness and conflict-safe insertion, so concurrent requests converge on one user. Authorization resolves the verified identity to that internal user and then requires a `(tenant_id, user_id)` membership. Knowing a tenant UUID, GitHub installation/repository identifier, organization, or email never creates access. Historical tenants receive no fabricated membership and remain inaccessible unless M13D1 independently verifies exact personal-installation ownership; organization ownership proof remains deferred.

The API is stateless bearer-token authenticated. It does not read browser cookies, so CSRF is disabled for this backend boundary; CORS is not enabled. Browser requests go through Next.js rather than directly to Spring. Tokens, issuer/subject claims, and SQL details are absent from response DTOs and safe authorization errors.

`GET /api/dashboard/tenants/{tenantId}/repositories` requires the same bearer authentication and independently invokes membership authorization for the path tenant before querying. The UUID identifies the requested workspace but never proves access. Unknown, foreign, and historical unowned tenants receive the same non-enumerating denial. The query reads at most 101 rows ordered by numeric GitHub repository ID, returns no more than 100, and exposes an explicit `truncated` flag. Each repository DTO contains only `repositoryId` and `connectedAt`; names, account, visibility, activation, configuration, and metrics are not present in M14 storage. The endpoint performs no GitHub request and no AI call.

`GET /api/dashboard/tenants/{tenantId}/reviews` repeats the same exact membership authorization before one tenant-scoped JDBC query. It left-joins the unique publication for each target-bearing review job, orders by `review_jobs.created_at DESC, review_jobs.id DESC`, and uses the existing `(tenant_id, created_at, id)` index. `limit` defaults to 20 and is capped at 50; the store reads one extra row to determine `hasMore`. The optional opaque cursor is a canonical, maximum-160-character base64url encoding of tenant UUID, boundary timestamp, and job UUID. It is tenant-bound and strictly validated but never authorizes access.

Each review DTO exposes only numeric repository ID, PR number, exact head SHA, a controlled dashboard state, optional accepted/publishable finding count, and created/aggregate-updated timestamps. Publication counts exist only for durable publication rows. `COMPLETED` without a publication remains the broad `COMPLETED_WITHOUT_PUBLICATION`; it is not called zero findings because the schema cannot distinguish every silent-completion reason. Claim tokens, retries, safe error codes, routing metadata, payloads, prompts, provider output, usage records, and internal IDs are omitted. The endpoint is observational: no GitHub/OpenAI call, token generation, worker execution, publication retry, or state mutation occurs.

## GitHub ownership bootstrap

`POST /api/dashboard/github-connection/start` and `POST /api/dashboard/github-connection/callback` require the same independently validated Auth0 bearer token as the session API. Configure the existing GitHub App's client ID/secret and exact frontend callback URL with `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET`, and `GITHUB_OAUTH_CALLBACK_URL`. The start operation creates high-entropy state plus S256 PKCE; only its SHA-256 hash and the necessary verifier are stored for ten minutes. Callback consumption is atomic and bound to the same internal application user.

The backend exchanges the code without redirects, verifies `GET /user`, and derives bounded pagination locally for `GET /user/installations`. Response bodies and installation counts are bounded. The user access token remains an opaque callback-local value. M13D1 maps only a personal installation whose numeric account ID equals the verified GitHub user ID; mere organization membership or repository collaboration is not translated to tenant ownership. The installation must already exist in M14. PostgreSQL serializes first-owner binding, same-user repeats are idempotent, and an existing other owner causes a non-enumerating conflict.

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

## Finding suppression

M10 converts M9 candidates into an immutable `ValidatedReview` for future publication. The initial operator-owned policy requires confidence 85, severity MEDIUM or higher, and retains at most three findings. Line findings must cover bounded, real new-side evidence and intersect an added line; patch-unavailable line findings instead require exact HEAD coordinates from M7 changed-file context. File-level findings are accepted only conservatively for removed files with explicit deletion relevance or patch-unavailable files with trustworthy changed-file context.

Obvious generic advice, insufficient evidence, inflated CRITICAL impact, low-confidence/LOW-severity findings, unsupported locations, same-category exact/near duplicates, and candidates beyond the final cap are suppressed. A fixed pipeline assigns one primary aggregate reason to each rejected candidate. Normalized token-set overlap is bounded and deterministic; it is not semantic proof. Accepted findings have stable severity/confidence/path/line/ID ordering.

Suppression is memory-only and has zero provider cost: it does not call AI, GitHub, URLs, tools, the filesystem, or persistence. Finding text remains untrusted inert text and is absent from logs and string representations. Zero accepted findings is successful and creates no publication or GitHub request.

## GitHub review publication

M11 uses the installation token boundary from M2 to call `POST /repos/{owner}/{repo}/pulls/{number}/reviews`. The request is one `COMMENT` review with the immutable job head SHA as `commit_id`; inline findings use `line` and `side=RIGHT`, with `start_line` and `start_side=RIGHT` for multiline findings. File-level findings remain in the summary and never receive invented coordinates. The GitHub App needs the `Pull requests: write` repository permission.

After suppression, non-empty output is rendered and sanitized, then the version-1 payload and a publication job are inserted atomically. Only accepted output, the trusted authenticated repository route, exact target, and bounded operational metadata are persisted. Prompts, raw model output, rejected findings, patches, context, credentials, and GitHub response bodies are not persisted. The analysis job completes only after durable handoff; publication-only retries do not call AI or rebuild context.

Publication jobs use PostgreSQL `FOR UPDATE SKIP LOCKED`, leases, claim tokens, bounded attempts, and deterministic exponential backoff. Before the POST, the publication becomes `AMBIGUOUS`. If the outcome is uncertain, the next attempt first lists reviews in bounded, locally derived pages and searches for the exact application-owned marker. Finding text is sanitized so it cannot forge that marker. A match records the GitHub review ID without another POST. Publication is disabled by default with `REVIEW_PUBLICATION_ENABLED=false`: non-empty analysis still completes its durable handoff, but the disabled scheduler performs no GitHub writes. Zero findings complete without any publication record or GitHub call.

## Repository context

M7 selects minimum sufficient context from M6 patches. It fetches a changed file only when its patch is unavailable and considers confidently local Java, JavaScript/TypeScript, and Python imports plus bounded companion candidates. Documentation, generated/vendor output, lockfiles, package imports, and unsupported-language guesses cause no extra request. Candidates are ranked by controlled reason, estimated cost, and path; duplicate path/revision pairs are fetched once.

Source uses `GET /repos/{owner}/{repo}/contents/{path}?ref={immutable_sha}` with M2 installation authentication. HEAD is normal and BASE is explicit for deleted files. Download URLs, redirects, repository cloning, filesystem paths, branch names, and recursive trees are never used. UTF-8 text is retained whole when it fits. A larger file is retained only as an explicitly incomplete window around one uniquely located declaration anchor; without that evidence it is omitted as `NO_RELEVANT_FRAGMENT`. Binary/NUL, invalid UTF-8, missing, and oversized optional context also become omissions. Relevant omission is preferable to irrelevant inclusion because arbitrary context has negative request, token, and distraction cost.

Defaults are 12 retained files, 128 KiB per file, 512 KiB total, 200 retained lines, 100 candidates, and 20 GitHub requests. `REVIEW_CONTEXT_*` variables configure bounded ceilings. These are source-byte limits, not model-token limits. More context is not automatically better context. M7 uses deterministic heuristics and does not invoke AI.

## Repository configuration

For each new analysis job, M12 makes at most one authenticated contents request for `.reviewbot.yml` from the authenticated base repository with `ref` equal to the immutable PR base SHA. Changed code remains pinned to the exact head SHA. A fork's head repository is never a policy source, and a policy change in a PR cannot weaken that same PR's review; after merge, later PRs whose base includes the change use it. `REPOSITORY_CONFIG_MAX_SIZE` defaults to `32KB` and cannot exceed the 64 KiB application hard ceiling. Content must be NUL-free UTF-8 and strict version-1 YAML. The safe parser rejects duplicate keys, custom tags, aliases, unknown fields, wrong scalar types, and excessive nesting. A missing file uses defaults. Invalid, oversized, unsupported-version, or unsupported-encoding content also uses defaults with a safe status; authentication, permission, rate-limit, and transient GitHub failures retain the normal job classification.

The supported options are the canonical example in the root README. Ignore matching supports only `/`-separated literal segments, `*`, `**`, and `?`; it permits at most 50 patterns, 256 characters each, and 4,096 characters total. It performs no filesystem resolution. `FAST` halves context file, byte, candidate, and request ceilings. `BALANCED` preserves the current configured ceilings. `DEEP` currently uses those same ceilings—never more—until a separate operator envelope justifies additional context. Every mode still permits at most one AI call.

Filtering happens before M7 candidate discovery and M9 serialization. The application-generated M9 instructions and JSON Schema contain only enabled categories; disabled categories are also rejected deterministically from returned candidates. All-disabled, all-ignored, config-only, or otherwise empty reviews skip AI and publication successfully. Raw configuration and pattern values are not logged, persisted, added to errors, or included in the M11 payload. M11 retries therefore never fetch configuration or repeat analysis.

## AI provider transport

M8's `AiProvider` accepts separate application instructions, untrusted input data, a caller-owned JSON Schema, and a controlled generation profile. `OpenAiProvider` is the first adapter and uses the official OpenAI Java SDK 4.58.0 Responses API with strict Structured Outputs, `store=false`, and no tools, functions, web search, file search, computer use, or code execution. AI provider transport does not decide what constitutes a review finding. Model capability is not a substitute for deterministic context selection and finding validation.

AI is disabled by default. To enable the adapter, set `REVIEW_AI_ENABLED=true` and supply `OPENAI_API_KEY` through the runtime secret store. Operator-controlled defaults are `AI_PROVIDER=openai`, `OPENAI_MODEL=gpt-5.6-terra`, `OPENAI_REASONING_EFFORT=low`, `OPENAI_MAX_OUTPUT_TOKENS=2048`, `OPENAI_TIMEOUT=60s`, and `OPENAI_MAX_RETRIES=1`. `AI_MAX_INPUT_CHARS` and `AI_MAX_SCHEMA_BYTES` bound transport at 120,000 characters and 64 KiB by default. Configuration rejects more than 8,192 output tokens, a timeout over two minutes, or more than one SDK retry; the maximum is therefore two provider attempts per logical call.

The API key, instructions, untrusted input, schema body, structured output, and raw provider response are never logged or persisted. Responses expose only structured JSON to the caller plus safe provider/model/request metadata, duration, and provider-reported token counts (including cached input and reasoning counts when available). Provider and model are cost-accounting dimensions; prices and billing are intentionally absent.

## Review engine

M9 serializes the exact M7 context deterministically as JSON marked `UNTRUSTED_REPOSITORY_DATA_ONLY`. Changed patches become line records with explicit old/new coordinates; auxiliary fragments retain their real line ranges and omissions remain visible. A small linear unified-diff mapper handles multiple hunks, additions, deletions, context, omitted counts, and no-newline markers. Malformed hunks lose location precision rather than receiving inferred coordinates.

The application-owned policy asks for at most five candidate findings (hard ceiling ten) at confidence 70 or above. Supported categories are correctness, security, concurrency, transactional integrity, reliability, API misuse, and significant performance. Review findings must describe a concrete failure mode, not a code-quality preference. The absence of sufficient evidence is a reason to omit a finding, and an empty finding list is successful analysis.

Strict Structured Output schema validation is followed by deterministic domain checks. Candidate paths must exactly match a current changed-file path; auxiliary-only and previous rename paths are excluded. Supplied lines must be bounded HEAD/new-side evidence. Removed files support only file-level candidates, while a patch-unavailable changed file can use real HEAD coordinates only when M7 retained changed-file context. Candidate findings and token metadata are in-memory only. M10 applies stronger suppression/ranking; M11 owns publication.

## GitHub webhook ingestion

`POST /api/webhooks/github` accepts only `application/json` and requires `X-Hub-Signature-256`, `X-GitHub-Delivery`, and `X-GitHub-Event`. Configure the same random, high-entropy secret in the GitHub App and `GITHUB_WEBHOOK_SECRET`; the App private key and webhook secret are independent credentials. `GITHUB_WEBHOOK_MAX_BODY_SIZE` defaults to `1MB` and may not exceed GitHub's 25 MiB payload cap.

The endpoint reads at most the configured limit plus one byte, verifies HMAC-SHA256 over the exact received bytes, validates bounded opaque delivery/event headers, validates one syntactically valid UTF-8 JSON value, and only then inserts a delivery. It stores the accepted payload as raw UTF-8 `TEXT`, preserving whitespace and key order for audit and future parsing. It never logs or echoes payloads, secrets, or signatures.

New and duplicate valid deliveries return `202 Accepted`. PostgreSQL uniqueness on `github_delivery_id` plus `INSERT ... ON CONFLICT DO NOTHING` makes concurrent redelivery idempotent. M5 interprets only `pull_request` actions `opened`, `reopened`, and `synchronize`; other events and actions are accepted without jobs. A relevant event uses GitHub's top-level `number` plus `installation.id`, numeric `repository.id`, and `pull_request.head.sha`. Signed but incomplete relevant events are retained without jobs and acknowledged because redelivery cannot repair their schema.

For a new valid reviewable event, webhook insertion and conflict-safe review-job insertion share one short transaction. A job failure rolls back the webhook, so GitHub receives a server failure and can redeliver. Duplicate deliveries do not reprocess; distinct deliveries for the same target still persist while the unique `(installation, repository, PR number, head SHA)` key creates only one job. Signature verification and JSON validation still occur before this transaction, and no GitHub API call or review work runs in the request.

## Tenant ownership

M14 resolves tenant ownership only after M3 signature verification and M5 schema validation. The signed `installation.id` and numeric `repository.id` are authoritative inputs to a narrow internal provisioning service. On first use, one transaction creates an application-owned tenant UUID, a globally unique GitHub installation mapping, and a repository mapping owned by that installation. Transaction-scoped PostgreSQL advisory locks serialize competing first-use operations; database uniqueness remains the durable identity guard.

`tenant_repositories` uses a composite foreign key to prove its installation belongs to the same tenant. New target-bearing `review_jobs` persist `tenant_id` and `tenant_repository_id`; new `review_publications` persist the same association and additionally use a composite foreign key to their analysis job's tenant. Publication queue rows reference exactly one tenant-owned publication. Repository owner/name remains authenticated routing metadata and does not affect ownership, so rename does not change identity. An attempted transfer or reassignment is rejected with a bounded ownership code until a later reconciliation workflow exists.

No caller-supplied tenant UUID is accepted. Workers stop unresolved or mismatched target ownership before any GitHub, configuration, AI, or publication call. Publication retries load their immutable tenant association and never reprovision it. V5 leaves pre-M14 job/publication ownership nullable rather than fabricating unknown customer ownership; these historical rows cannot enter the tenant-required review/publication paths. Tenant status, uninstall handling, transfer reconciliation, RLS, product tenant endpoints, organization ownership bootstrap, and billing are intentionally deferred.

## Usage accounting and quotas

M15 reserves one tenant usage event immediately before the first logical AI analysis invocation. The event key is the review-job ID plus `REVIEW_ANALYSIS`, so worker retries cannot create another unit. The reservation transaction commits before the provider call; consumption is a separate short transaction afterward. A successful provider response is consumed before suppression or publication, including zero-finding and all-suppressed results. Stale/config-only/all-disabled/all-ignored paths and publication retries do not reserve usage.

`REVIEW_USAGE_MONTHLY_LIMIT` defaults to `50` and is validated from 1 through 100000. Quota windows are half-open UTC calendar months. `RESERVED` and `CONSUMED` rows count toward the limit; `RELEASED` rows remain auditable but do not. PostgreSQL transaction advisory locks serialize decisions for a tenant and month, and the database uniqueness constraint remains the idempotency safeguard under concurrent workers.

Provider/model identifiers and optional provider-reported input, cached-input, output, reasoning-output, and total token counts may be retained as bounded operational metadata. Unknown values stay null; summaries identify partial totals rather than treating unknown as zero. Source, prompts, raw output, prices, credentials, and provider error bodies are never usage data. An ambiguous process/provider outcome leaves its reservation active and blocks a duplicate paid call. Automated reconciliation/release, billing, plans, and public usage APIs are deferred.

## Review-job worker foundation

M4 stores review work in PostgreSQL with `READY`, `PROCESSING`, `COMPLETED`, and `FAILED` states. A JDBC claim transaction selects a bounded due batch in `next_attempt_at`, `created_at`, `id` order using `FOR UPDATE SKIP LOCKED`, increments attempts, and assigns each row a fresh UUID claim token and expiring lease. The transaction commits before retrieval runs. Completion, retry, and failure are separate short transactions whose conditional updates require both job ID and the current claim token.

Expired `PROCESSING` leases are reclaimed by a later poll when attempts remain. An expired final attempt becomes `FAILED`. Retryable failures return to `READY` with deterministic `baseDelay * 2^(attempt-1)` backoff capped by the configured maximum; only a bounded safe error code is stored. Terminal failures and exhausted attempts become `FAILED` immediately. No payload, stack trace, external response, credential, or source content is stored in M4 jobs.

The analysis scheduler is disabled by default and invokes a separately testable poll-once worker. Rate-limit and transient GitHub failures retry; stale revisions, inaccessible resources, malformed responses, and excessive PRs terminate with bounded codes. With AI disabled, successful context construction ends as `REVIEW_AI_ANALYSIS_NOT_IMPLEMENTED`. With publication disabled, non-empty validated output is durably queued but not sent; zero findings complete successfully. When publication is enabled, the independent publication worker owns all GitHub-write retries. Redis/Kafka and distributed queue coordination remain deferred.
