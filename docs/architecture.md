# Architecture

## Purpose and status

This document defines the intended component boundaries and responsibilities. It deliberately avoids class-level design. At M5, the Spring Boot/PostgreSQL foundation, GitHub App authentication boundary, durable webhook ingestion, narrow pull request event interpretation, and revision-specific review-job lifecycle exist; PR data retrieval and the review pipeline remain unimplemented.

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

Claims durable jobs in deterministic due/creation/ID order, commits the claim, then executes work outside a database transaction. Completion, retry, and failure use separate short ownership-checked transactions. The scheduler remains disabled by default. The infrastructure no-op handler succeeds only for targetless M4 fixtures and terminally refuses real target-bearing jobs, so M5 work cannot be falsely completed. Later handlers must remain retry-safe; exactly-once execution is not promised.

### GitHub integration

Creates short-lived installation credentials, retrieves the minimum authorized context, observes API budgets, and will eventually publish reviews. M2 implements only App JWT creation, installation-token exchange/cache, and a narrow read-only accessible-repository operation. Installation identity is supplied per operation, and credentials never leave this server-side boundary. GitHub payloads and content are translated and validated here; GitHub-specific types do not define the domain model.

### Deterministic analysis

Runs predictable, testable rules over normalized review inputs and returns structured candidate findings with evidence. It has no dependency on a model provider and must be reproducible for the same inputs and rule version.

### AI analysis boundary

Accepts a bounded, policy-approved analysis request and returns provider-neutral structured candidates. An internal provider interface isolates credentials, request formats, response formats, failure behavior, and future provider substitution. Model output is untrusted and cannot publish directly.

### Validation and ranking

Validates structure and evidence, removes unsafe, duplicate, irrelevant, or low-confidence candidates, and ranks remaining findings under a bounded review budget. This boundary enforces the product principle that fewer high-confidence findings are preferable to noisy coverage.

### Review publisher

Converts validated findings into an idempotent GitHub review operation. It verifies that comments still target relevant content, applies output safety rules, and records the external result without exposing credentials or unnecessary source content.

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

The backend is currently one Spring Boot application with Actuator health, Flyway-managed PostgreSQL state, process-local GitHub authentication infrastructure, the webhook endpoint, and an optional scheduled job poller. Installation tokens are cached only within the process; multi-instance cache coordination is deferred. Database locks and claim tokens make job ownership safe across multiple backend processes. PostgreSQL stores accepted webhook envelopes and immutable M5 review targets, but no GitHub credentials, tenant/install records, fetched PR content, or findings. The frontend and deployment topology remain undecided.

## Decision records

Accepted foundational decisions are recorded in [docs/adr](adr): monorepo ownership, GitHub App authentication, a PostgreSQL-backed queue, and an internal AI provider abstraction. Material changes require a new superseding ADR.
