# Security Principles

## Security posture

The system processes private source code and acts through GitHub installation permissions in a multi-tenant environment. It therefore assumes that inbound content, external services, model output, and tenant users can fail or be malicious. Controls use least privilege, explicit tenant context, bounded processing, defense in depth, and auditable state transitions.

This document defines required controls. Concrete libraries, schemas, thresholds, key-management products, and hosting controls are deferred until implementation and deployment decisions exist.

## Identity, authentication, and tenant isolation

- Use a GitHub App with the minimum repository permissions and event subscriptions needed for the product.
- Verify every webhook signature against the exact raw body before parsing or accepting the event. Reject missing, malformed, stale where policy permits, or invalid signatures without processing.
- Derive installation identity from verified GitHub data and resolve it to an explicit internal tenant. Never trust repository text or client-supplied tenant identifiers as authorization.
- Scope every tenant-owned record, query, job, cache entry, metric dimension, and external action to the resolved tenant and installation.
- Test denial paths and cross-tenant identifier substitution. Prefer defense-in-depth data access constraints in addition to application checks.

## Replay, idempotency, and job safety

- Record the GitHub delivery identifier within the appropriate tenant/installation scope and enforce deduplication atomically.
- Treat GitHub redelivery and repeated event delivery as normal. Duplicate delivery must not create duplicate review jobs or reviews.
- Queue consumers must tolerate at-least-once execution, crashes, lease expiry, and partial external success.
- Use explicit job state transitions, bounded retries, backoff, terminal failure handling, and idempotency keys for publication.
- Do not hold the webhook request open while a review runs.

## Secrets and credentials

- Store GitHub App private keys, webhook secrets, installation tokens, database credentials, session secrets, and AI provider credentials in an approved secret store or protected runtime configuration.
- Never commit secrets or place real values in examples, fixtures, logs, traces, error messages, model prompts, analytics, or client bundles.
- Generate short-lived GitHub installation tokens only when required, keep them server-side, restrict their scope, and discard them promptly.
- Rotate credentials and support revocation. Treat any exposed token as compromised and investigate its use.
- Logs should contain opaque identifiers, decisions, timings, sizes, and error classes—not authorization headers, raw payloads, prompts, model responses, or source code unless a narrowly approved diagnostic path explicitly requires redacted content.

## Untrusted repository and model content

- Treat diffs, source files, paths, comments, commit messages, metadata, generated files, encodings, and archives as malicious input.
- Normalize and validate formats; enforce byte, file, line, token, nesting, and time limits before analysis.
- Never execute pull request code or follow instructions embedded in repository content as system instructions.
- Isolate trusted system policy from quoted repository material in model requests. Tell the model that repository content is data, restrict allowed outputs to a provider-neutral schema, and validate all returned fields.
- Prompt injection in source or comments must not change tool permissions, reveal secrets, request unrelated data, alter tenant context, or bypass publishing policy.
- Do not provide model tools with ambient GitHub, network, filesystem, database, or secret access.
- Minimize content sent to a model and apply tenant data-use and retention policy before transmission.

## Availability and abuse controls

- Bound webhook body size, pull request size, fetched content, model input/output, deterministic analysis work, processing time, retries, concurrency, and stored diagnostic data.
- Apply per-installation and service-wide quotas so a single tenant or oversized pull request cannot exhaust shared resources.
- Respect GitHub rate-limit and abuse responses, use conditional or cached retrieval where safe, and back off with jitter rather than retrying aggressively.
- Classify AI provider timeouts, throttling, malformed output, and outages. Retry only safe transient failures within a budget, use circuit breaking where demonstrated, and complete with deterministic results or a clear failure state when appropriate.
- Never publish unchecked fallback text after an AI failure.

## Supply-chain and delivery security

- Minimize dependencies and use supported, pinned or constrained versions through ecosystem lock and build files when those files are introduced.
- Review provenance, maintenance, license, and transitive risk before adding a dependency.
- Pin CI actions to immutable revisions under a documented update process; give workflows minimal token permissions and protect release credentials.
- Run dependency, secret, static, and artifact scanning appropriate to the implementation, and address findings according to risk.
- Build from reproducible definitions, produce an inventory of shipped components when delivery begins, and keep build and runtime identities separate.
- Do not run untrusted pull request code in privileged CI contexts or expose secrets to workflows triggered from forks.

## External-provider failure and compromise

- GitHub and AI providers are external trust boundaries. Authenticate requests, validate responses, use timeouts, observe quotas, and make partial failure explicit.
- A compromised GitHub installation token must be containable by short lifetime, least privilege, server-only handling, revocation, and auditability.
- AI provider responses cannot authorize actions or publish directly. Provider credentials remain inside the AI adapter.
- Provider failure must not corrupt durable job state, cross tenant boundaries, leak content, or cause unbounded retries.

See [threat-model.md](threat-model.md) for threat scenarios and [testing-strategy.md](testing-strategy.md) for required regression coverage.
