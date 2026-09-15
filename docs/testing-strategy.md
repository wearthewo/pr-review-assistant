# Testing Strategy

## Goals

Testing must provide confidence in behavior, boundaries, tenant isolation, idempotency, retry safety, concurrency, provider contracts, and safe failure. Tests should be deterministic, readable, and proportionate to risk. The default suite must not depend on live GitHub, a live AI provider, or developer-owned infrastructure.

M1 establishes the backend foundation tests. M2 adds authentication and cache tests; M3 covers exact-byte webhooks; M4 covers leased queue concurrency; M5 covers atomic revision jobs; M6 covers bounded PR retrieval. M7 adds exact-SHA context contracts and deterministic bounded selection. M8 adds offline provider-contract, SDK mapping, configuration, usage, limits, retry classification, redaction, and concurrency tests with a deterministic fake. M9 adds fake-provider review tests, strict review-schema/domain validation, deterministic serialization, unified-diff line mapping, prompt-injection regression cases, empty-review success, and no-false-completion worker coverage. M10 adds pure policy tests for confidence/severity boundaries, changed-line and file-level support, generic/actionable evidence, severity sanity, deterministic duplicate/overlap resolution, caps, aggregate accounting, immutability, inert malicious text, zero-result success, and worker handoff without a second AI call. M11 adds publication-key, versioned-codec, rendering, GitHub contract, ambiguous reconciliation, PostgreSQL handoff/lease/idempotency, and no-repeat-AI tests. The remaining categories below become requirements as corresponding behavior is implemented.

M12 adds strict YAML, size, encoding, duplicate-key, tag, and alias tests; bounded logical glob tests; exact-SHA content-fetch contracts; predefined mode-ceiling tests; category-constrained schema tests; and workflow tests proving early ignore and zero AI/publication for empty policy results.

M14 adds PostgreSQL-backed tenant/installation/repository provisioning, external-ID uniqueness, composite same-tenant foreign-key, concurrent first-use, reassignment rejection, rollback, historical V4-to-V5 upgrade, tenant-bearing job/publication, signed-webhook authority, and zero-GitHub/AI/publication denial-path tests. Tenant-like payload/config fields and mutable owner/name metadata must never affect resolved ownership.

M15 adds fixed-clock unit tests for UTC month windows, quota bounds, safe metadata, unknown/partial token totals, and tenant-context enforcement. PostgreSQL 18.6 integration tests cover reservation/consumption/release, review-job idempotency, same-tenant foreign keys, direct uniqueness, populated V5-to-V6 migration, and tenant-scoped summaries. Barrier-based concurrency tests prove same-job single reservation and no quota oversubscription for many distinct jobs. Worker tests prove quota denial and ambiguous retries make zero AI calls, successful and post-provider-invalid outputs consume once, no-AI paths create no usage, and publication retries make no usage call.

M13A uses the Node test runner through a minimal TypeScript executor. Foundation tests server-render the root and dashboard pages, prove untrusted text is escaped, validate strict production/development CSP differences and attached response headers, exercise production backend-origin failure and URL rejection, assert the server-only import and absence of client-public secret variables, and scan production TSX for raw-HTML/dynamic-code patterns. Lint, strict TypeScript, lockfile installation, production build, dependency tree, and production vulnerability audit are separate required gates. Full browser E2E remains deferred until authenticated behavior exists.

M13B extends those lightweight frontend tests with unauthenticated and membership-less rendering, server-only bearer forwarding, strict backend DTO validation, response/token redaction, blocked token/profile routes, fixed login destinations, Auth0 environment/session-cookie assertions, and preservation of CSP. Backend tests cryptographically exercise RS256 plus expiry, not-before, issuer, audience, wrong-signature, and unsigned rejection; MockMvc covers empty 401 behavior and safe session DTOs. PostgreSQL 18.6 tests cover `(issuer, subject)` identity, issuer namespace separation, concurrent first-login convergence, membership uniqueness/role/FKs, Tenant A/Tenant B denial, historical unowned tenants, and clean V1-to-V7 plus populated V6-to-V7 migration. No live Auth0 tenant is required.

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

M6 GitHub contract tests assert installation-scoped authorization, shared Accept/version headers, repository-ID resolution, exact request paths, multi-page termination, missing/renamed/removed/unknown file variants, malformed and oversized responses, 401/403/404/429/5xx/timeout classification, disabled redirects, and refusal to follow untrusted pagination URLs. Loader tests separately prove exact-revision short-circuiting and all in-memory bounds without live GitHub.

M11 contract tests assert one GitHub Create Review POST, exact `commit_id`, `COMMENT`, modern RIGHT-side line fields, required media/version/authentication headers, response bounds, status classification, and bounded locally derived reconciliation pagination. They must prove redirects or response-supplied URLs cannot receive authorization.

### Webhook fixture tests

Keep sanitized, synthetic fixtures for supported GitHub event variants. M3 tests the official GitHub HMAC vector, known test secrets and exact raw bytes, malformed/missing/SHA-1 signatures, altered and byte-reformatted bodies, Unicode/invalid encoding, duplicate delivery IDs, malformed JSON, bounded headers, and configured size limits. M5 tests `opened`, `reopened`, `synchronize`, ignored actions/events, missing/wrong/oversized identity fields, duplicate delivery versus duplicate revision, and changed head revisions. Fixtures must contain no real tenant source code or credentials.

### Concurrency tests

Use the real PostgreSQL locking and transaction behavior to prove that concurrent workers do not claim the same available job, locked rows are skipped rather than blocking peers, expired work can be recovered by one new owner, stale owners cannot transition work, and concurrent completion remains safe. M4 uses latches, barriers, and bounded futures rather than sleeps for these claims. Also exercise simultaneous duplicate webhook delivery, tenant quota contention, same-job reservation idempotency, and publication guards.

Publication concurrency tests run against PostgreSQL and prove publication-key uniqueness, `SKIP LOCKED` batch splitting, lease recovery, claim-token stale-owner rejection, and atomic payload/job handoff. An ambiguous-write scenario must prove that reconciliation finds the marker and the create-review request count remains one. Publication retry tests also assert that the AI provider invocation count remains one. M14 tests additionally prove a publication cannot reference an analysis job or repository owned by another tenant, while retries reuse the persisted association without provisioning.

### End-to-end tests

Exercise the smallest complete deployed-like flow: a signed synthetic webhook enters the API, a durable job is claimed, controlled GitHub and AI doubles return context and candidates, validation/ranking runs, and one expected review publication is observed. Include duplicate delivery, transient provider failure, deterministic-only degradation where supported, and terminal failure. E2E tests must not call live tenant repositories by default.

### Security regression tests

Every fixed vulnerability receives a test at the lowest effective level plus boundary coverage when needed. Maintain explicit coverage for webhook forgery and replay, cross-tenant identifier substitution, prompt-injection payloads, malicious paths and encodings, log/response redaction, authorization failures, oversized inputs, rate and retry bounds, malformed model output, and unsafe rendering. CI handling of forked contributions must be tested or policy-checked without exposing secrets.

M11 security regressions cover marker forgery, hostile Markdown and mentions, payload/string redaction, secret-bearing GitHub errors, ambiguous write reconciliation, and the zero-finding no-write rule.

M12 security regressions treat configuration itself as hostile: unknown instruction/model/URL/secret-shaped fields fall back without content exposure; duplicate keys, custom tags, invalid UTF-8, NUL, oversized input, and alias expansion are rejected. Tests prove matching uses only bounded repository-path strings, category restrictions occur before AI, config-only/all-ignored/all-disabled work makes no provider or publication call, the authenticated base repository and exact BASE SHA are used once, HEAD policy cannot weaken its own review, fork policy never comes from the contributor repository, and publication retries never reload config. All tests remain offline and deterministic.

M14 security regressions prove signed GitHub installation/repository IDs are authoritative, arbitrary tenant IDs are ignored, repository rename strings do not change identity, cross-tenant job/publication associations fail at the database boundary, ownership failures use bounded codes, and no GitHub retrieval, configuration fetch, AI invocation, or publication handoff occurs after a mismatch.

M15 security regressions prove tenant-like quota/usage/plan/billing fields in webhooks and repository configuration have no authority, cross-tenant usage insertion and finalization fail closed, provider metadata is bounded, token values cannot be negative or overflow application limits, and string/error representations reveal no token counts or external content. Tests inspect the migration for the absence of source-content columns and ensure unknown measurements are not converted to zero.

M13B security regressions prove invalid JWT classes fail, email is absent from identity storage, identical subjects under different issuers do not collide, concurrent logins create one user, and membership—not tenant UUID knowledge—authorizes. They also prove no token is rendered or persisted, Auth0 helper routes cannot expose it, backend error bodies are not propagated, unowned tenants fail closed, CSP remains strict, and browser storage/public-secret patterns are absent. Secure GitHub-backed first-membership bootstrap remains a later integration test because M13B intentionally exposes no such operation.

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
