# Architecture

## Purpose and status

This document defines the intended component boundaries and responsibilities. It deliberately avoids class-level design. The backend is complete through M16. M13G closes the integrated read-only dashboard phase and M16 instruments the existing boundaries without changing their behavior; review detail, repository mutation, billing, production monitoring infrastructure, and later dashboard product operations remain absent.

## System context

GitHub is the event source, source-code context provider, and destination for published pull request reviews. The product receives authenticated GitHub App events, creates durable review work, retrieves authorized context, analyzes a pull request, validates candidate findings, and publishes an approved review. Operators and tenant administrators will use a web interface for installation-level configuration and status when that interface is introduced.

## Logical flow

```text
GitHub
  -> webhook ingestion
  -> API/application boundary
  -> PostgreSQL-backed review job queue
  -> review worker
  -> GitHub context retrieval
  -> deterministic analyzers + AI analysis boundary
  -> tenant usage consumption
  -> validation and ranking
  -> GitHub review publisher
```

## Component boundaries

### Webhook ingestion

Terminates GitHub webhook requests, bounds the raw request body, authenticates its exact bytes, validates minimum envelope metadata and JSON syntax, and durably deduplicates accepted delivery IDs. M5 dispatches only already-verified JSON. It recognizes `pull_request` actions `opened`, `reopened`, and `synchronize`, validates only the fields needed for an exact revision, and ignores all other events/actions. Signed but malformed reviewable events remain durable without jobs. For a valid reviewable event, M14 resolves ownership from the signed installation and numeric repository IDs inside the existing short acceptance transaction; no GitHub API call or review work occurs at ingress.

### Tenant ownership boundary

Maps authoritative GitHub installation and repository numeric IDs to application-owned UUIDs. First use provisions tenant, installation, and repository records atomically; transaction-scoped PostgreSQL advisory locks coordinate races while unique and composite foreign-key constraints remain the durable safeguards. Repository owner/name and repository content are never ownership authorities. Reassignment across installations or tenants fails closed with a bounded code. Status workflows, uninstall/transfer reconciliation, memberships, and public tenant administration remain outside M14.

### API and application boundary

Owns use-case coordination, authorization, tenant context, and transaction boundaries. It translates inbound requests into domain operations and exposes intentionally documented contracts. HTTP, persistence, and provider-specific types remain at adapters rather than becoming domain concepts.

The dashboard sub-boundary independently validates Auth0 RS256 access JWTs in Spring, maps the trusted issuer/subject pair to an internal application user, and resolves tenant authorization only through durable membership. `AuthorizedTenantContext` can be created only by that membership lookup; a requested tenant UUID identifies a resource but never proves access. GitHub webhook identity remains separate from human identity.

The ownership-bootstrap sub-boundary keeps Auth0 identity and GitHub human identity separate. It uses hashed, expiring, single-use state bound to the application user plus S256 PKCE, exchanges the code server-side, verifies the stable GitHub numeric user ID, and enumerates bounded pages of installations visible to that user. M14 remains the installation-to-tenant authority. Only exact personal-account ownership creates the initial controlled `OWNER`; organization access and multiple candidates fail closed.

### PostgreSQL system of record and job queue

Stores durable product state, ownership mappings, and review jobs. For a new reviewable delivery, its raw envelope, ownership provisioning/resolution, and conflict-safe tenant-associated job insertion commit atomically. The review identity is installation ID, numeric repository ID, pull request number, and immutable expected head object ID. Delivery uniqueness and review-target uniqueness are separate database guarantees. M4 queue operations create `READY` work and transactionally claim bounded due batches using PostgreSQL row locking with `SKIP LOCKED`. Jobs move through `READY`, `PROCESSING`, `COMPLETED`, or `FAILED`; retry delay remains data in `next_attempt_at` rather than a separate status. PostgreSQL remains the source of truth; Redis and Kafka remain unjustified.

### Review worker

Claims durable jobs in deterministic due/creation/ID order, commits the claim, then executes work outside a database transaction. Completion, retry, and failure use separate short ownership-checked transactions. Both schedulers remain disabled by default. Zero accepted findings complete without a GitHub write. For non-empty validated output, atomic durable handoff precedes analysis-job completion; the independent publication worker owns all GitHub-write retries, so those retries never repeat retrieval, context building, suppression, or AI.

### Usage accounting boundary

Owns the tenant-scoped decision to reserve, consume, or release one logical `REVIEW_ANALYSIS` unit for a review job. The review-job/usage-type key is unique. A reservation is committed immediately before AI, the provider executes outside a database transaction, and consumption is committed afterward. Both active reservations and consumed events count against the half-open UTC monthly quota. PostgreSQL transaction advisory locks serialize each tenant/month decision; composite foreign keys prove the usage job and repository belong to the same tenant.

The ledger stores only bounded provider/model identifiers and optional numeric provider-reported token measurements. Unknown token data remains unknown and aggregate completeness is explicit. Source, prompts, raw model output, credentials, response bodies, and prices do not cross this boundary. Ambiguous reservations remain conservatively active to prevent a duplicate paid invocation; automated reconciliation and billing remain future concerns.

### GitHub integration

Creates short-lived installation credentials and retrieves minimum authorized context. M6 reuses M2 tokens to resolve a numeric repository ID, fetch PR metadata, verify exact revision identity, and paginate changed-file metadata through derived requests. Redirects are disabled, pagination URLs are never followed, responses are operation-bounded, and remote errors omit bodies/credentials. Provider DTOs are normalized into an immutable in-memory snapshot; GitHub-specific types do not define later review policy.

### Repository context engine

Consumes only an exact M6 snapshot, discovers bounded candidates from changed paths and patches, and retrieves selected UTF-8 source through the authenticated GitHub boundary at explicit HEAD or BASE SHAs. It produces an immutable, ephemeral `ReviewContext` with provenance, controlled reasons, omissions, and budget usage. It neither clones repositories nor persists source, follows URLs, invokes AI, or performs review analysis. More context is not automatically better context.

Whole files are retained only when they fit. Larger content requires a unique deterministic declaration anchor and produces an incomplete window with real line provenance. Without an anchor, the file is omitted; arbitrary leading fragments have negative product value and are forbidden.

### Repository configuration boundary

Loads the single canonical `.reviewbot.yml` path once, after the numeric base repository is authenticated and resolved and before context selection, using the exact PR BASE SHA. Changed code remains pinned to the exact HEAD SHA. For fork PRs, the base repository—not the contributor's head repository—is the policy authority. This prevents a PR from weakening its own review; a merged policy change applies to later PRs whose base includes it. The boundary converts bounded, NUL-free UTF-8 through safe strict YAML parsing into an immutable effective policy. Raw YAML never crosses this boundary. Missing or invalid content produces centralized defaults and a safe status; GitHub authentication, authorization, throttling, and transient failures keep their existing job semantics.

The policy can choose only a predefined review mode, bounded deterministic ignore globs, and enablement flags for existing finding categories. Ignore policy removes source before context discovery and AI serialization. Category policy constrains trusted application-generated instructions and schema before the provider call. Repository choices remain subordinate to operator ceilings and cannot supply prompts, schemas, model/provider choices, confidence thresholds, publication limits, URLs, includes, or executable behavior. No configuration state is persisted. M11 publication retries consume their durable payload and never re-enter this boundary.

### Deterministic analysis

Runs predictable, testable rules over normalized review inputs and returns structured candidate findings with evidence. It has no dependency on a model provider and must be reproducible for the same inputs and rule version.

### AI analysis boundary

M8 accepts separate application instructions and untrusted input, a caller-owned strict JSON Schema, and a controlled generation profile, then returns structured JSON plus provider-neutral usage and execution metadata. The OpenAI adapter uses the Responses API, stores no provider payloads, enables no tools, and contains every SDK type.

M9 owns review policy above that generic transport. It deterministically serializes the exact bounded context with explicit untrusted-data marking, maps unified diffs to real new-side coordinates, makes one balanced/economical-reasoning generation call, and converts strict structured output into immutable candidate findings. It accepts empty reviews, excludes unknown paths and unsupported locations, bounds all fields/counts, and propagates safe usage metadata. Model output remains untrusted and cannot publish directly.

### Validation and ranking

M10 is a provider-independent, in-memory trust boundary after M9. Its fixed gate order applies conservative confidence and severity policy, verifies changed-code/location support, rejects insufficient or generic evidence and inconsistent severity, resolves same-category exact/near duplicates, ranks deterministically, and limits the result to three publication candidates by default. It returns accepted findings plus aggregate suppression counts only; rejected content is not exposed or persisted.

This boundary verifies support facts, not semantic correctness. It makes no AI, GitHub, network, filesystem, database, or tool call. M9 candidates and M10 accepted findings are deliberately distinct concepts. Zero accepted findings is successful.

### Review publisher

Converts validated findings into one bounded GitHub `COMMENT` review at the immutable head SHA. The analysis side atomically stores a version-1 rendered payload and a separate publication job. The publication worker uses the existing installation-token boundary, modern new-side line coordinates, bounded retries, leases, and claim-token ownership.

Before a POST the durable publication becomes `AMBIGUOUS`. An uncertain outcome is reconciled by listing bounded, locally derived review pages and matching an exact application-owned marker before any repeat POST. `PUBLISHED` is terminal and stores only GitHub's review ID and submitted time. Rendering sanitizes model-controlled Markdown and keeps file-level findings in the summary rather than inventing a line. No network call runs inside a database transaction; publication retries never repeat AI analysis.

### Frontend

The Next.js 16 App Router defaults to Server Components. Auth0 Universal Login owns OIDC state/PKCE and an encrypted HttpOnly browser session. Auth configuration and access tokens remain in `server-only` modules; the dashboard server obtains a token and calls Spring with bearer authentication. Spring does not trust the Next.js login assertion and validates the token independently. The access-token/profile SDK routes are unavailable, and no generic proxy exists.

The browser is never a tenant authority. A verified `(issuer, subject)` resolves to an application user, then a membership, then an authorized tenant. M13D1 can create the first membership only from server-verified personal GitHub installation ownership; browser/setup identifiers remain untrusted hints and are ignored by the implemented flow. GitHub, OpenAI, auth client, webhook, and database secrets never enter `NEXT_PUBLIC_*` configuration or Client Components.

The request boundary generates a fresh CSP nonce and attaches strict production headers. React text escaping remains the display baseline. `/dashboard` is a Server Component that obtains the bounded session DTO, renders unauthenticated/unbound/error states, and selects a workspace only from returned memberships. An optional tenant query identifies a preference but never authorizes it; an unmatched value fails closed. Overview and the read-only Repositories, Reviews, and Usage sections are functional; settings, billing, and observability remain future scope. GitHub connection callbacks accept exactly one bounded code and state. Active hashed OAuth state is bounded per application user through PostgreSQL transaction coordination and indexed pruning.

Dashboard navigation preserves the exact selected member workspace as an identifier across those four routes. Each destination revalidates selection against the fresh session DTO and each Spring resource endpoint independently authorizes membership, so the URL never becomes authority. Server-only clients share an incremental byte-counting response boundary; no dashboard response is fully buffered before its operation-specific ceiling is enforced.

The Usage page calls one protected Spring endpoint after session resolution. Spring independently resolves the application user and membership, then delegates to M15's quota service. That service derives the half-open UTC month from the injected `Clock`, applies the configured global quota, and issues one tenant/type/period aggregate. Dashboard reads do not take the reservation advisory lock because they neither decide nor mutate quota. The frontend treats the bounded DTO as untrusted, keeps the bearer token server-only, and uses a native accessible progress element capped visually at the limit while preserving truthful over-limit numbers.

The repository page sends the selected authorized identifier server-to-server to `GET /api/dashboard/tenants/{tenantId}/repositories`. Spring repeats membership authorization before a tenant-scoped PostgreSQL query; prior session selection alone is never sufficient. M14 `tenant_repositories` is the bounded dashboard read model, while GitHub remains the external source of truth. Results are ordered by numeric GitHub repository ID and limited to 100 with explicit truncation. No live GitHub, token-generation, AI, configuration, or per-repository request occurs. The DTO deliberately omits unavailable display names and all inferred status or metrics.

The Reviews page uses the same server-only and independently reauthorized path through `GET /api/dashboard/tenants/{tenantId}/reviews`. A history entry is a target-bearing `review_jobs` row, with its optional unique `review_publications` row left-joined in the same bounded query. The existing tenant/created/id index supports reverse keyset traversal. Ordering is `(created_at DESC, id DESC)`; pages default to 20 and cap at 50. The canonical base64url cursor carries only tenant-bound boundary data and remains non-authoritative. Analysis status and publication status remain distinct, publication state takes precedence when present, and absent publication is never interpreted as zero findings. PostgreSQL is the complete history read model; reads trigger no provider or worker behavior.

### Infrastructure and delivery

Defines PostgreSQL-only local infrastructure for the backend. It will add CI checks and deployment concerns only when required by a later milestone. Infrastructure does not own product rules. No additional service, CI workflow, or deployment platform choice exists at M4.

### Observability boundary

M16 separates logs, metrics, and health. Stable structured events correlate individual webhook, review-job, and publication workflows using bounded identifiers. Micrometer aggregates only controlled, low-cardinality dimensions and derives queue gauges from indexed PostgreSQL scalar queries at scrape time. Health groups describe process liveness and database-backed readiness; GitHub/OpenAI availability remains an operation metric rather than a restart signal. Instrumentation is best effort and cannot participate in, roll back, or replace authoritative business state. Prometheus export is protected; the monitoring backend and deployment policy remain M20 concerns.

## Domain and dependency direction

Core review concepts and policies are independent of frameworks, persistence, GitHub, and AI vendors. Application orchestration depends on domain contracts. Inbound and outbound adapters depend inward on those contracts. External systems never become implicit authorities for tenant identity or policy.

## Cross-cutting invariants

- Every installation-scoped operation carries explicit tenant identity.
- Webhook acceptance and job creation are idempotent.
- Workers and external side effects tolerate retries and partial failure.
- Untrusted input is bounded and validated before use.
- Secrets remain inside integration boundaries and are never logged or sent to the model.
- Review publication accepts only validated, ranked findings.
- Observability uses identifiers and bounded metadata rather than unnecessary source content.
- Usage accounting is tenant-scoped, idempotent per review job, and enforced before AI without holding a transaction across the provider call.

## Deployment view

The backend is currently one Spring Boot application with Actuator health, Flyway-managed PostgreSQL state, process-local GitHub authentication, the webhook endpoint, internal tenant/installation/repository ownership, human identity/membership authorization, a bounded repository read endpoint, disabled-by-default job pollers, exact-revision repository configuration, a disabled-by-default AI adapter/review engine, deterministic finding suppression, durable review publication, and tenant usage accounting. Database locks, constraints, claim tokens, and tenant/month quota locks make provisioning, work ownership, and quota decisions safe across processes. PostgreSQL stores ownership UUIDs, webhook envelopes, immutable targets, usage states with bounded operational metadata, and only the sanitized accepted user-facing publication payload plus routing metadata. It stores no credentials, fetched context, repository configuration, prompts, raw AI responses, rejected findings, patches, prices, or suppression content. The frontend is a separate Next.js process boundary and calls protected dashboard endpoints only server-to-server; deployment topology remains undecided.

## Decision records

Accepted foundational decisions are recorded in [docs/adr](adr), including the M13B OIDC/server-mediated dashboard authentication decision. Material changes require a new superseding ADR.
