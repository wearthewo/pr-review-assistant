# Testing Strategy

## Goals

Testing must provide confidence in behavior, boundaries, tenant isolation, idempotency, retry safety, concurrency, provider contracts, and safe failure. Tests should be deterministic, readable, and proportionate to risk. The default suite must not depend on live GitHub, a live AI provider, or developer-owned infrastructure.

M1 establishes a Spring context test and a PostgreSQL Testcontainers integration test for the backend foundation. The remaining categories below become requirements as corresponding behavior is implemented.

## Test layers

### Unit tests

Exercise domain rules, validation, ranking, state transitions, limit calculations, retry classification, and mapping logic without frameworks, networks, databases, clocks, or random behavior unless those dependencies are controlled. Cover success, boundary, and failure cases. Domain tests should make provider independence visible.

### Integration tests

Verify real adapters and boundaries: persistence mappings, transactions, Flyway migrations, queue claiming, HTTP serialization, authentication filters, and provider adapter behavior against controlled servers. Integration tests must prove tenant scoping and rollback/failure semantics rather than merely booting an application context.

### Testcontainers

Use Testcontainers for PostgreSQL integration tests so tests exercise the supported database family and real concurrency/locking semantics. Apply Flyway migrations from an empty database and test upgrades from supported schema states when those states exist. Pin a compatible PostgreSQL image version. Container-backed tests must isolate data and remain runnable locally and in CI without a shared database.

### Contract tests

Validate both inbound and outbound boundaries:

- generated or implemented HTTP behavior against the committed OpenAPI contract;
- GitHub webhook parsing and GitHub API requests/responses against documented, sanitized examples;
- the internal AI provider contract across provider adapters, including structured success, throttling, timeout, malformed output, and refusal/failure cases.

Provider contract tests use fakes or controlled mock servers by default. Optional live-provider tests must be separately invoked, credential-safe, bounded in cost, and never required for routine contribution.

### Webhook fixture tests

Keep sanitized, synthetic fixtures for supported GitHub event variants. Test signature verification using known test secrets and exact raw bytes; malformed signatures; missing fields; unsupported actions; altered bodies; duplicate delivery IDs; reordered or additional fields; unusual encodings; and configured size limits. Fixtures must contain no real tenant source code or credentials.

### Concurrency tests

Use the real PostgreSQL locking and transaction behavior to prove that concurrent workers do not claim the same available job, expired work can be recovered, retry transitions remain valid, and publication guards prevent duplicate external effects. Also exercise simultaneous duplicate webhook delivery and tenant quota contention. Synchronize tests deliberately; avoid timing-only assertions and unbounded sleeps.

### End-to-end tests

Exercise the smallest complete deployed-like flow: a signed synthetic webhook enters the API, a durable job is claimed, controlled GitHub and AI doubles return context and candidates, validation/ranking runs, and one expected review publication is observed. Include duplicate delivery, transient provider failure, deterministic-only degradation where supported, and terminal failure. E2E tests must not call live tenant repositories by default.

### Security regression tests

Every fixed vulnerability receives a test at the lowest effective level plus boundary coverage when needed. Maintain explicit coverage for webhook forgery and replay, cross-tenant identifier substitution, prompt-injection payloads, malicious paths and encodings, log/response redaction, authorization failures, oversized inputs, rate and retry bounds, malformed model output, and unsafe rendering. CI handling of forked contributions must be tested or policy-checked without exposing secrets.

## Test data and doubles

- Use synthetic tenant IDs, repositories, tokens, keys, diffs, and comments.
- Clearly distinguish fakes, stubs, and controlled mock servers from production adapters.
- Provider doubles must model failure and latency classes, not only happy paths.
- Avoid brittle full-payload snapshots when targeted assertions better express the contract.
- Freeze or inject clocks, identifiers, and randomness when observable behavior depends on them.

## CI expectations

Later milestones will define actual workflows. The intended progression is fast unit and static checks first, integration and contract suites next, then security and E2E coverage appropriate to the change. Failures must be reproducible locally through repository-owned commands. Flaky tests are defects and may not be hidden by indiscriminate retries.

## Completion evidence

Contributors must report the exact commands and results for tests and checks run, plus anything skipped and why. Passing unrelated tests is not evidence for an untested behavior. Coverage percentages may inform gaps but do not replace behavior- and risk-based test design.
