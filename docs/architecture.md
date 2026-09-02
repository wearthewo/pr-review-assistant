# Architecture

## Purpose and status

This document defines the intended component boundaries and responsibilities. It deliberately avoids implementation-level class, package, endpoint, schema, deployment, and algorithm design. The repository is at M0: none of these runtime components exists yet.

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

Terminates GitHub webhook requests, preserves the raw body for signature verification, rejects invalid or oversized requests, identifies the installation tenant, deduplicates deliveries, and hands accepted events to the application boundary. It does not perform a pull request review during the request.

### API and application boundary

Owns use-case coordination, authorization, tenant context, and transaction boundaries. It translates inbound requests into domain operations and exposes intentionally documented contracts. HTTP, persistence, and provider-specific types remain at adapters rather than becoming domain concepts.

### PostgreSQL system of record and job queue

Stores durable product state and review jobs. Queue operations provide atomic claiming, explicit state transitions, retry scheduling, and recovery from abandoned work. Tenant ownership is part of all tenant-scoped data. PostgreSQL is the initial queue; a separate broker is not part of the baseline architecture.

### Review worker

Claims durable jobs, orchestrates the review pipeline, records bounded progress and outcomes, and makes each step safe to retry. It does not assume exactly-once delivery. Publishing is guarded against duplicate external effects.

### GitHub integration

Creates short-lived installation credentials, retrieves the minimum authorized pull request context, observes API budgets, and publishes reviews. GitHub payloads and content are translated and validated at this boundary. GitHub-specific types do not define the domain model.

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

Will define reproducible local dependencies, CI checks, and deployment concerns when required by a milestone. Infrastructure does not own product rules. No Compose services, CI workflows, or deployment platform choices are introduced in M0.

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

The planned backend contains logically separate API and worker responsibilities. Whether they run as separate processes is a later operational decision and must not weaken their boundary. PostgreSQL is shared durable state. The frontend is a separate web application. Exact hosting, topology, scaling, and network design are intentionally deferred until requirements are demonstrated.

## Decision records

Accepted foundational decisions are recorded in [docs/adr](adr): monorepo ownership, GitHub App authentication, a PostgreSQL-backed queue, and an internal AI provider abstraction. Material changes require a new superseding ADR.
