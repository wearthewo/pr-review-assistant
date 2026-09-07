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

- Record the globally unique GitHub delivery identifier atomically at ingress. Future tenant-scoped processing must additionally carry verified installation/tenant context without weakening this global delivery deduplication.
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

### GitHub App authentication controls implemented in M2

- The App private key is supplied as a protected PEM file path, not multiline YAML. GitHub PKCS#1 RSA keys and PKCS#8 keys are parsed by maintained cryptographic libraries; key material is never placed in messages or value-object string representations.
- App JWTs use RS256 only, an issued-at time 60 seconds in the past, and an expiration nine minutes after the current clock reading. JWTs are used only to request installation tokens and are never logged.
- Installation tokens are opaque secrets. Code does not inspect their prefix, length, or shape, and does not persist them. Tokens are cached by installation ID with an expiry safety window and cannot be returned after expiration.
- Installation ID is explicit per operation. It is not a process-wide credential and must eventually be derived from authenticated tenant context when that context exists.
- Authorization headers and GitHub response bodies are excluded from application-generated errors. Actuator exposes no GitHub credential or GitHub health detail.
- The process-local cache coalesces refreshes for one installation while isolating cache keys between installations. Cross-instance coordination is deferred; each instance may independently request a token.

### GitHub webhook controls implemented in M3

- `POST /api/webhooks/github` accepts JSON only and authenticates with `X-Hub-Signature-256`; session authentication, user agent, and legacy SHA-1 signatures grant no trust.
- The configured high-entropy webhook secret is separate from the App private key. It has no default, is never persisted or logged, and is redacted from configuration string representations and errors.
- The request body is read with a bounded allocation (1 MiB by default), and both declared and actual byte counts are enforced before parsing. The configured ceiling cannot exceed GitHub's 25 MiB payload cap.
- HMAC-SHA256 is computed over the exact received bytes and compared in constant time. Only after successful verification are bounded delivery/event headers and exactly one valid UTF-8 JSON value accepted.
- Accepted raw JSON text is stored without routine logging. Error responses are empty and never echo signatures, secrets, payloads, or internal exception detail.
- GitHub delivery ID is an opaque idempotency key. A PostgreSQL unique constraint and conflict-safe insert prevent concurrent duplicates, and the insert commits before `202 Accepted` is returned.
- M3 does not parse installation identity, create tenant state or review work, call GitHub, persist credentials, or process event-specific content.

### Review-job controls implemented in M4

- PostgreSQL is the job-state authority. Due jobs are selected and marked `PROCESSING` in one short transaction with `FOR UPDATE SKIP LOCKED`; work runs only after that transaction commits.
- Every claim has a fresh opaque UUID token and bounded expiry. Completion, retry, and failure require the current token, so an expired stale worker cannot overwrite a newer owner.
- Attempts are incremented at claim time and cannot exceed the finite configured maximum. Retryable failures use deterministic capped exponential backoff; terminal and exhausted work becomes `FAILED`.
- Expired claims are recovered by later polls without manual repair. An expired claim at its maximum attempt is terminalized rather than reclaimed indefinitely.
- Error storage accepts only a 64-character uppercase safe code. Jobs contain no payload, source content, stack trace, external response, GitHub credential, webhook value, or authorization data.
- Poll batches, lease duration, retry delays, and attempt counts are validated and bounded. The scheduler is disabled by default and prevents overlapping ticks within one process; PostgreSQL locking remains the cross-process control.
- M4 deliberately has no tenant or installation data. Before real review work is enqueued, a later milestone must add explicit authenticated tenant/installation ownership and its isolation tests.

### Pull request event controls implemented in M5

- Event interpretation receives only payloads that passed M3 exact-byte authentication, bounded metadata checks, and JSON validation. It never creates a parallel or weaker ingress path.
- Only `pull_request` actions `opened`, `reopened`, and `synchronize` can schedule work. All other event names and actions are successful no-ops.
- Relevant payloads are narrowly read as untrusted trees. Positive installation/repository IDs, a positive top-level PR number, and a bounded hexadecimal 40-64 character head object ID are required; unneeded GitHub fields are not deserialized.
- A signed but malformed relevant event is retained as an accepted delivery without a job and without logging payload-derived error detail. This avoids futile GitHub retries for permanently unprocessable content.
- A new reviewable delivery and its job commit in one short transaction. Job insertion failure rolls back the webhook; duplicate-target conflict is a normal result that still permits a distinct delivery to commit.
- Webhook delivery ID protects transport idempotency. A separate database unique key over installation, numeric repository, PR number, and head SHA protects business idempotency across deliveries and repository renames.
- Installation identity is explicit in every M5 job and selects M2 credentials during M6 retrieval. Internal tenant resolution and authorization remain required before production outbound enablement.
- M5 itself performs no GitHub API or AI call. M6 replaces the no-op boundary without allowing retrieval to masquerade as a completed review.

### Pull request retrieval controls implemented in M6

- Every request uses the target's installation ID with the existing M2 cache. Tokens remain opaque, process-local, unlogged, and unpersisted.
- Mutable owner/name addressing is obtained from authenticated `GET /repositories/{id}` data, bounded, and checked against the target's numeric repository ID. The PR number and base repository ID are rechecked before use.
- The returned head SHA must exactly match the immutable target before file retrieval. A mismatch is terminal stale work, not a retry and not permission to analyze a newer revision.
- HTTP redirects are disabled. A `Link` header can only indicate another page; its URL is never requested. Subsequent URLs are derived from the configured GitHub base URL and bounded numeric page counter.
- Metadata and each files response are byte-bounded. Page count, file count, repository-path length, per-file patch bytes, and total patch bytes are validated. Limits produce explicit unavailable/too-large states rather than silent truncation.
- Missing patches and binary-file metadata are accepted without fetching blobs. Repository paths remain opaque identifiers and are never resolved against the server filesystem.
- Patches, paths, and GitHub error bodies are untrusted source data. They are absent from application logs, exception messages, persisted error codes, and string representations; patch text cannot issue instructions or gain capabilities.
- Only bounded classifications cross into job state. Rate limits and transient transport/5xx failures retry; stale, inaccessible, malformed, and excessive inputs terminate. Successful retrieval is not reported as a completed review while analysis is absent.

### Repository context controls implemented in M7

M7 bounds source selection, preserves immutable revision provenance, rejects unsafe paths and binary/invalid text, and keeps source ephemeral. More context is not automatically safer or better.

Every source byte, import, filename, comment, and URL remains hostile data. Context retrieval uses validated repository-relative identifiers and immutable SHAs; it performs no filesystem access, code execution, URL following, cloning, or environment access. Contents and paths are excluded from logs, exceptions, job codes, `toString()`, Actuator, and metric labels. Binary/NUL and invalid UTF-8 content is not retained. Independent request, candidate, file, line, and byte ceilings limit cost and exhaustion.

Large source is never represented by an arbitrary leading fragment. Only a uniquely located controlled declaration can anchor an incomplete window with accurate line bounds; ambiguous or absent anchors produce `NO_RELEVANT_FRAGMENT`.

### AI transport controls implemented in M8

- AI is disabled by default. Enabling OpenAI requires a runtime API key; the key is redacted by configuration objects and never logged, persisted, exposed through Actuator, or copied into errors.
- Instructions and untrusted repository input remain separate request fields. The adapter enables no functions, tools, search, computer use, URL following, or code execution.
- Calls require caller-owned JSON Schema Structured Outputs, set provider storage off, and reject missing, refusal, incomplete, scalar, or malformed structured output as a controlled terminal failure.
- Input characters, schema bytes, output tokens, request duration, and retries are bounded independently. At most one SDK retry is allowed, preventing stacked application retries from multiplying provider cost.
- Only provider/model/request identifiers, duration, attempt ceiling, and provider-reported numeric usage are safe operational metadata. Instructions, source, schema content, output, and raw provider responses remain ephemeral and redacted.

### Review-engine controls implemented in M9

- Application-owned review instructions remain separate from deterministic JSON marked as untrusted repository data. Source text cannot alter policy or gain tools, network, filesystem, database, GitHub, or secret access.
- The engine requests at most five findings by default (hard ceiling ten), one provider call per logical attempt, the configured balanced model tier, economical reasoning, and the existing output-token ceiling. Empty findings are successful.
- Strict JSON Schema is followed by domain validation. Unknown fields, invalid enums/ranges, oversized strings, and output flooding fail safely. Low-confidence, duplicate, unknown-path, auxiliary-only, previous-rename-path, and unsupported-line candidates are excluded.
- New-side coordinates come only from a bounded linear unified-diff mapping or explicit M7 HEAD changed-file context. Malformed hunks lose precision; removed files cannot claim HEAD line locations; no replacement location is invented.
- Findings, source, prompts, schema bodies, and provider output remain in memory only and are absent from logs, errors, queue state, Actuator, and string representations. Safe metadata contains only provider/model, duration, attempt ceiling, numeric token usage, and finding count.
- Retryable provider failures map to bounded retry codes; authentication, permission, unavailable-model, malformed-output, and other terminal failures map to bounded terminal codes. Successful analysis stops before publication and cannot mark a review completed.

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
