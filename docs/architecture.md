# Architecture

## Purpose and status

This document defines the intended component boundaries and responsibilities. It deliberately avoids class-level design. At M11, the Spring Boot/PostgreSQL foundation, GitHub App authentication, durable webhook/job lifecycle, bounded exact-revision context, structured AI transport, candidate review analysis, deterministic finding suppression, and durable GitHub review publication exist.

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
  -> validation and ranking
  -> GitHub review publisher
```

## Component boundaries

### Webhook ingestion

Terminates GitHub webhook requests, bounds the raw request body, authenticates its exact bytes, validates minimum envelope metadata and JSON syntax, and durably deduplicates accepted delivery IDs. M5 dispatches only already-verified JSON. It recognizes `pull_request` actions `opened`, `reopened`, and `synchronize`, validates only the fields needed for an exact revision, and ignores all other events/actions. Signed but malformed reviewable events remain durable without jobs. Tenant resolution remains future work; no GitHub API call or review work occurs at ingress.

### API and application boundary

Owns use-case coordination, authorization, tenant context, and transaction boundaries. It translates inbound requests into domain operations and exposes intentionally documented contracts. HTTP, persistence, and provider-specific types remain at adapters rather than becoming domain concepts.

### PostgreSQL system of record and job queue

Stores durable product state and review jobs. For a new reviewable delivery, its raw envelope and conflict-safe job insertion commit atomically. The review identity is installation ID, numeric repository ID, pull request number, and immutable expected head object ID. Delivery uniqueness and review-target uniqueness are separate database guarantees. M4 queue operations create `READY` work and transactionally claim bounded due batches using PostgreSQL row locking with `SKIP LOCKED`. Jobs move through `READY`, `PROCESSING`, `COMPLETED`, or `FAILED`; retry delay remains data in `next_attempt_at` rather than a separate status. PostgreSQL remains the source of truth; Redis and Kafka remain unjustified.

### Review worker

Claims durable jobs in deterministic due/creation/ID order, commits the claim, then executes work outside a database transaction. Completion, retry, and failure use separate short ownership-checked transactions. Both schedulers remain disabled by default. Zero accepted findings complete without a GitHub write. For non-empty validated output, atomic durable handoff precedes analysis-job completion; the independent publication worker owns all GitHub-write retries, so those retries never repeat retrieval, context building, suppression, or AI.

### GitHub integration

Creates short-lived installation credentials and retrieves minimum authorized context. M6 reuses M2 tokens to resolve a numeric repository ID, fetch PR metadata, verify exact revision identity, and paginate changed-file metadata through derived requests. Redirects are disabled, pagination URLs are never followed, responses are operation-bounded, and remote errors omit bodies/credentials. Provider DTOs are normalized into an immutable in-memory snapshot; GitHub-specific types do not define later review policy.

### Repository context engine

Consumes only an exact M6 snapshot, discovers bounded candidates from changed paths and patches, and retrieves selected UTF-8 source through the authenticated GitHub boundary at explicit HEAD or BASE SHAs. It produces an immutable, ephemeral `ReviewContext` with provenance, controlled reasons, omissions, and budget usage. It neither clones repositories nor persists source, follows URLs, invokes AI, or performs review analysis. More context is not automatically better context.

Whole files are retained only when they fit. Larger content requires a unique deterministic declaration anchor and produces an incomplete window with real line provenance. Without an anchor, the file is omitted; arbitrary leading fragments have negative product value and are forbidden.

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

Will provide tenant administrators with configuration, status, and operational visibility. It never receives GitHub installation tokens or model credentials and accesses backend capabilities only through explicit authenticated APIs.

### Infrastructure and delivery

Defines PostgreSQL-only local infrastructure for the backend. It will add CI checks and deployment concerns only when required by a later milestone. Infrastructure does not own product rules. No additional service, CI workflow, or deployment platform choice exists at M4.

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

## Deployment view

The backend is currently one Spring Boot application with Actuator health, Flyway-managed PostgreSQL state, process-local GitHub authentication, the webhook endpoint, disabled-by-default job pollers, a disabled-by-default AI adapter/review engine, deterministic finding suppression, and durable review publication. Database locks and claim tokens make ownership safe across processes. PostgreSQL stores webhook envelopes, immutable targets, and only the sanitized accepted user-facing publication payload plus routing metadata. It stores no credentials, fetched context, prompts, raw AI responses, rejected findings, patches, or suppression content. The frontend and deployment topology remain undecided.

## Decision records

Accepted foundational decisions are recorded in [docs/adr](adr): monorepo ownership, GitHub App authentication, a PostgreSQL-backed queue, and an internal AI provider abstraction. Material changes require a new superseding ADR.
