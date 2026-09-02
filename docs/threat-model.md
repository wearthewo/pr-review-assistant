# Threat Model

## Scope

This threat model covers the planned path from GitHub webhooks through durable processing, context retrieval, analysis, and GitHub review publication, plus the future administrative frontend and software supply chain. M2 adds a concrete server-side GitHub App authentication boundary; webhook handling, tenant persistence, and the review pipeline remain future scope.

## Assets

- Tenant source code, diffs, comments, metadata, and configuration
- GitHub App private key, webhook secret, and short-lived installation tokens
- AI provider credentials and submitted model context
- Tenant identity, installation mappings, review jobs, findings, and audit state
- The ability to read repositories and publish GitHub reviews
- Service availability, rate-limit budgets, and build/release integrity

## Trust boundaries and actors

Trust boundaries exist between GitHub and webhook ingestion, clients and the future frontend/API, application logic and PostgreSQL, workers and GitHub, analysis and AI providers, tenants sharing the service, and source/CI dependencies entering the build. Potential threat actors include unauthenticated internet clients, malicious repository contributors, compromised tenant accounts, compromised external providers, malicious dependencies, and accidental or malicious operators.

## Threats and required mitigations

| Threat | Example impact | Required mitigation direction |
| --- | --- | --- |
| GitHub webhook forgery | An attacker creates unauthorized jobs or actions | Verify the signature over the exact raw body, reject before parsing into trusted state, protect and rotate the webhook secret, constrain accepted event types and payload sizes |
| Webhook replay or duplicate delivery | Duplicate work, cost, or GitHub comments | Atomically deduplicate the delivery identifier in installation scope, make job creation idempotent, and guard publication with stable idempotency state |
| Compromised installation token | Unauthorized repository reads or review publication | Use least permissions, short-lived tokens, server-only handling, no logging, revocation/rotation procedures, bounded use, and audit identifiers |
| Compromised GitHub App private key or App JWT | An attacker mints installation credentials across App installations | Mount the PEM as a protected secret, never log or persist key/JWT material, use short JWT lifetimes, rotate/revoke App keys, and restrict backend filesystem/runtime access |
| Installation-token cache confusion or refresh stampede | Cross-installation access or excessive GitHub authentication traffic | Key strictly by installation ID, supply that ID per authenticated operation, refresh before expiry, coalesce same-installation refreshes, isolate failures, and test concurrency deterministically |
| Cross-tenant data access | One customer reads or changes another customer's data | Carry verified tenant context through every operation, scope storage and cache access, enforce authorization at boundaries, avoid guessable identifiers as authorization, and test cross-tenant denial |
| Prompt injection through repository contents | Source text persuades a model or agent to reveal data or bypass policy | Mark repository material as untrusted data, separate it from system policy, expose no ambient tools or secrets, minimize context, require structured output, and validate against independent policy |
| Malicious diffs or repository data | Parser exploits, path tricks, excessive computation, terminal/log injection, or unsafe publication | Never execute code; validate encoding, paths, sizes, shapes, and output locations; use safe parsers; escape rendered content; isolate processing; impose time and resource bounds |
| Secret leakage | Credentials or private source appear in logs, prompts, responses, UI, or fixtures | Centralize secret handling, redact sensitive fields, minimize collected and transmitted content, prohibit secrets in logs and models, use synthetic fixtures, scan commits and artifacts |
| Oversized pull requests and resource exhaustion | Queue starvation, cost spikes, memory exhaustion, or provider overuse | Enforce layered byte/file/token/time/concurrency limits, per-tenant quotas, backpressure, bounded retries, cancellation, and explicit skipped/partial outcomes |
| GitHub API abuse | Rate-limit exhaustion or actions beyond product purpose | Request minimum scopes, validate target installation/repository, budget requests per job and tenant, cache safely, honor rate-limit headers, back off, and audit outbound actions |
| AI provider failures or hostile output | Stalled jobs, corrupted state, fabricated findings, or unsafe comments | Use timeouts and bounded retries, classify errors, validate schemas and evidence, treat output as untrusted candidates, allow deterministic-only degradation, and prevent direct publication |
| Supply-chain compromise | Malicious code executes in CI, builds, or production | Minimize and review dependencies, pin CI actions, scan dependencies and secrets, separate forked PR workflows from secrets, use least-privilege build identities, and verify release provenance |

## Abuse cases across the review pipeline

A malicious contributor may craft files, diffs, comments, or metadata to trigger parser edge cases, inflate token use, imitate system instructions, reference another tenant, inject control characters into logs, or induce comments on unrelated code. Each pipeline stage must retain provenance, apply independent limits, and pass only normalized data forward. Validation and ranking must confirm evidence and target location before publication.

An attacker may intentionally redeliver valid events or exploit worker crashes near publication. The system must assume at-least-once delivery and reconcile durable state with GitHub outcomes so retries do not create duplicate reviews.

An external provider may be unavailable, slow, return malformed results, retain data unexpectedly, or become compromised. Requests must be minimal, bounded, contract-validated, and incapable of carrying service credentials or cross-tenant context. Provider failure cannot authorize a fallback publication.

## Security invariants

- Unverified webhooks cause no durable or external effect.
- Tenant identity comes from authenticated context and is explicit in all tenant-scoped operations.
- No model or repository content can grant capabilities or obtain credentials.
- No analysis result reaches GitHub without validation and publication policy checks.
- Duplicate deliveries and job retries do not duplicate externally visible reviews.
- One tenant's workload and data are isolated from every other tenant.
- Sensitive content is not required for routine observability.

## Residual risk and review triggers

AI analysis can be wrong even after validation, GitHub permissions still carry impact, and software dependencies cannot be made risk-free. The product reduces these risks through bounded authority, high-confidence publication, human review, monitoring, and incident response.

The M2 cache is local to one process. Multiple application instances can each mint a token for the same installation, which is acceptable at current scale but may increase authentication traffic. Redis or distributed locking is not justified without measured multi-instance contention. The private key remains readable by the backend process; a future deployment may replace file-based signing with a sign-only key-management service through a separately reviewed design.

Review and update this model when adding an endpoint, permission, event type, data store, AI provider, executable analysis mechanism, deployment environment, tenant-facing feature, or material data-retention change, and after any security incident.
