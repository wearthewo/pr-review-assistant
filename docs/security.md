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
- M4's targetless compatibility jobs predate tenant ownership. M14 permits those historical rows to remain nullable, but tenant-required review execution rejects them before external or AI work.

### Pull request event controls implemented in M5

- Event interpretation receives only payloads that passed M3 exact-byte authentication, bounded metadata checks, and JSON validation. It never creates a parallel or weaker ingress path.
- Only `pull_request` actions `opened`, `reopened`, and `synchronize` can schedule work. All other event names and actions are successful no-ops.
- Relevant payloads are narrowly read as untrusted trees. Positive installation/repository IDs, a positive top-level PR number, and a bounded hexadecimal 40-64 character head object ID are required; unneeded GitHub fields are not deserialized.
- A signed but malformed relevant event is retained as an accepted delivery without a job and without logging payload-derived error detail. This avoids futile GitHub retries for permanently unprocessable content.
- A new reviewable delivery and its job commit in one short transaction. Job insertion failure rolls back the webhook; duplicate-target conflict is a normal result that still permits a distinct delivery to commit.
- Webhook delivery ID protects transport idempotency. A separate database unique key over installation, numeric repository, PR number, and head SHA protects business idempotency across deliveries and repository renames.
- Installation identity is explicit in every M5 job and selects M2 credentials during M6 retrieval. M14 binds new review work to an internal tenant and registered repository before that retrieval can run.
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

### Repository configuration controls implemented in M12

- `.reviewbot.yml` is fetched once through the existing installation-scoped boundary from the authenticated base repository at the immutable PR BASE SHA. Changed code remains pinned to the exact HEAD SHA. No head-repository/head-SHA, branch/default-branch, local-file, or remote-include policy lookup is permitted.
- A pull request cannot weaken its own review by changing mode, categories, or ignore patterns. Fork PR policy never comes from the contributor's fork; a policy change takes effect only for later pull requests whose base revision contains the merged change.
- Repository configuration is hostile data, never application instructions. Raw YAML, comments, ignore values, and unknown values are not logged, persisted, placed in exceptions, sent to AI, or exposed through Actuator.
- The file defaults to a 32 KiB ceiling with a 64 KiB hard maximum and must be NUL-free UTF-8. Safe YAML construction rejects duplicate keys, aliases, custom tags, excessive nesting, unknown fields, unsupported versions, and wrong scalar types.
- Missing, invalid, oversized, or unsupported content produces centralized balanced/all-enabled defaults plus a safe status. Authentication, permission, rate-limit, and transient GitHub failures are not disguised as missing configuration.
- Ignore matching supports only bounded logical `/` repository paths and the application-owned `*`, `**`, and `?` subset. It performs no regex evaluation, filesystem resolution, URL access, imports/includes, shell work, or code execution.
- Only fixed review modes and existing category booleans are accepted. Modes remain under M7 hard ceilings and all preserve one AI call maximum and M10 trust policy. Enabled categories constrain trusted instructions and schema before AI; repositories cannot select models, provider settings, confidence, output limits, or publication policy.
- The control file and ignored changed/auxiliary files are removed before context discovery and serialization. All-disabled, all-ignored, and config-only work completes with no AI or GitHub publication.
- Publication payloads contain no configuration. Once handoff commits, M11 retries neither reread configuration nor repeat analysis.

### Tenant ownership controls implemented in M14

- Internal tenant, installation, and repository identifiers are application-generated UUIDs. Only the signature-verified webhook's positive GitHub installation ID and numeric repository ID can establish or resolve ownership; payload tenant fields, repository owner/name, sender metadata, paths, source, and repository configuration have no tenant authority.
- First-use provisioning runs in the same transaction as accepted-delivery and review-job creation. PostgreSQL transaction-scoped advisory locks coordinate concurrent first use, unique external IDs prevent duplicate mappings, and composite foreign keys prove repository, job, analysis, and publication tenant consistency.
- Repository rename does not change ownership because numeric GitHub repository ID is authoritative. Unexpected installation/repository reassignment fails closed with bounded `TENANT_REPOSITORY_OWNERSHIP_MISMATCH`. Transfer and uninstall reconciliation are deferred rather than guessed from mutable names.
- New target-bearing jobs persist tenant and internal repository ownership. Claiming reconstructs that context from constrained database rows. Missing or mismatched ownership terminates before GitHub retrieval, config loading, AI, or publication.
- Publications persist the analysis tenant/repository association. A composite analysis-job foreign key prevents a publication under another tenant, and publication retries reuse durable ownership without invoking provisioning or analysis.
- V5 does not invent lifecycle events, tenant suspension, RLS, public tenant APIs, user membership, or billing. Historical pre-M14 rows remain nullable to avoid fabricated ownership and are barred from tenant-required execution.

### Usage-accounting controls implemented in M15

- The only metered unit is one logical AI analysis invocation. Webhooks, stale/config-only/all-disabled/all-ignored work, deterministic suppression, and publication retries do not consume usage.
- Every usage operation requires explicit tenant context and a matching tenant repository/review job. Composite database foreign keys and tenant-scoped queries prevent cross-tenant attribution or reads.
- A unique `(review_job_id, usage_type)` key makes retries idempotent. PostgreSQL transaction advisory locks serialize quota checks and reservations for each tenant and UTC month, so concurrent workers cannot oversubscribe the limit.
- Reservations commit before AI and consumption commits afterward; no database transaction spans provider work. `RESERVED` and `CONSUMED` count against quota. Ambiguous reservations remain active and block a duplicate potentially paid call.
- Repository content, webhook fields, and `.reviewbot.yml` cannot set tenant identity, quota, usage state, provider, model, token counts, plan, or price. The monthly limit is operator-owned configuration.
- Usage metadata is limited to bounded safe provider/model identifiers and optional nonnegative provider-reported token counts. Unknown stays unknown. Source, prompts, raw output, error bodies, credentials, authorization data, stack traces, and monetary calculations are excluded.
- Release is an explicit monotonic state transition retained for auditability; there is no automatic expiry or deletion. Reconciliation, billing, invoices, plans, retention, and public usage APIs remain future security designs.

### Frontend foundation controls implemented in M13A

- The M13A browser foundation grants no tenant authority. M13B now resolves authorization from authenticated server context rather than a submitted tenant ID; unauthenticated rendering still receives no tenant, repository, review, publication, or usage data.
- Backend origin configuration is server-only and protected with the React/Next `server-only` boundary. Production requires a credential-free HTTPS origin; path, query, fragment, unsupported scheme, and cross-origin escape are rejected. M13B uses that narrow origin only for its fixed session request and still exposes no generic proxy.
- No GitHub credential, OpenAI key, webhook secret, installation token, database credential, or other product secret may use `NEXT_PUBLIC_*` or be serialized into Client Components.
- A per-request unpredictable nonce authorizes framework scripts under production CSP. Production contains neither `unsafe-inline` nor `unsafe-eval`; development permits `unsafe-eval` only for framework tooling. Framing, objects, base URI, form actions, browser capabilities, MIME sniffing, and referrer disclosure are constrained.
- Repository names, usernames, findings, statuses, provider metadata, and GitHub-derived values remain untrusted display text. React escaping is the default; raw HTML, `dangerouslySetInnerHTML`, dynamic code execution, DOM HTML injection, and arbitrary external fetching are prohibited.
- Only error/reset boundaries are Client Components. User-facing error pages omit exception messages and sensitive diagnostic detail.

### Human authentication and tenant authorization controls implemented in M13B

- Auth0 Universal Login provides one OIDC path; the application implements no passwords, reset flow, MFA store, or authentication cryptography. Auth0-specific frontend code remains at the session boundary.
- Next.js stores session material in SDK-managed encrypted HttpOnly cookies with `SameSite=Lax` and `Secure` in production. Access and refresh tokens are absent from browser storage, Client Component props, HTML, URLs, logs, and the product database. Browser access-token/profile SDK routes are disabled and blocked.
- Spring is a separate OAuth2 Resource Server trust boundary. It accepts RS256 only and validates signature, required expiry, not-before, issuer, and audience. Missing or invalid bearer credentials produce empty 401 responses; configuration fails closed.
- Human identity is the bounded trusted `(issuer, subject)` pair. Email is neither persisted nor used for identity. A unique database constraint plus conflict-safe insert makes concurrent first login converge on one internal UUID.
- Tenant access requires a durable unique membership with controlled `OWNER` or `MEMBER` role. Requested tenant IDs only identify data; membership proves authorization. Unknown, cross-tenant, and historically unowned tenants fail with the same bounded denial.
- No automatic ownership bootstrap exists. Installation IDs, repository IDs/names, organizations, domains, emails, and tenant UUIDs cannot create membership. A later flow must verify GitHub ownership server-side.
- Browser traffic terminates at Next.js and Next.js calls Spring server-to-server. No broad CORS is enabled. Spring's dashboard API is stateless bearer authenticated and consumes no cookies, so CSRF is disabled there.
- M13B adds no application state-changing browser endpoint. Auth0 retains state/PKCE and callback protections, login return destinations are fixed, and future cookie-authenticated mutations must enforce origin/fetch metadata plus CSRF tokens where appropriate. GET never mutates product state.

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
- Candidate findings, source, prompts, schema bodies, and raw provider output remain in memory and are absent from logs, errors, queue state, Actuator, and string representations. M11 alone persists a sanitized, bounded rendering of accepted findings.
- Retryable provider failures map to bounded retry codes; authentication, permission, unavailable-model, malformed-output, and other terminal failures map to bounded terminal codes. Non-empty successful analysis completes only after durable publication handoff; zero findings complete without a publication.

### Finding-suppression controls implemented in M10

- Model output remains untrusted after M9. A provider-independent deterministic boundary requires the initial confidence 85 and MEDIUM severity policy, validates changed-code/location support, rejects obvious generic or insufficient evidence, and conservatively rejects inconsistent CRITICAL impact.
- Line findings must intersect real added new-side evidence, except patch-unavailable files where exact HEAD coordinates must come from M7 changed-file context. Removed files cannot receive invented HEAD coordinates; their file-level findings require explicit deletion relevance. Previous rename paths remain invalid.
- Same-category exact and strong normalized overlaps are resolved deterministically. Stable severity/confidence/location/ID ranking retains at most three publication candidates by default. Zero accepted findings is successful.
- Suppression exposes only candidate/accepted/suppressed counts and controlled reason counts. It does not persist or log paths, finding bodies, source, model output, or suggested fixes.
- Finding text, URLs, shell-like strings, HTML, secret-like strings, and paths remain inert. Suppression performs no AI, GitHub, network, URL, filesystem, database, code-execution, or tool call.
- Suppression is an explainable support filter, not proof of semantic correctness. Model confidence is not authority and cannot bypass independent publication controls.

### GitHub review-publication controls implemented in M11

- Publication is separately disabled by default and uses only operation-scoped installation tokens; tokens and Authorization headers are never persisted, logged, or included in errors.
- Only sanitized accepted findings are rendered. Control characters, HTML/comments, marker-like content, Markdown constructs, links, and mentions are neutralized or removed; the application alone appends the exact idempotency marker.
- A versioned bounded payload and publication job commit atomically. Prompts, raw model output, rejected findings, suppression details, source, patches, secrets, and GitHub error bodies are excluded.
- Repository owner/name come from M6's authenticated numeric-ID resolution and are routing metadata only. Numeric repository ID and exact head SHA remain authoritative. A rename-induced 404 fails terminally in M11 rather than following an authenticated redirect; safe route refresh is deferred.
- The publisher marks a record `AMBIGUOUS` before POST. After any uncertain outcome it reconciles bounded, locally derived review-list pages for the exact marker before another write. Redirects and arbitrary pagination URLs remain disabled.
- GitHub writes occur outside transactions. Queue leases and claim tokens reject stale owners; database uniqueness protects analysis-job and publication-key idempotency.

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
- Enforce the M15 tenant monthly AI-analysis quota before provider invocation. Per-installation and service-wide backpressure remain additional future controls.
- Respect GitHub rate-limit and abuse responses, use conditional or cached retrieval where safe, and apply bounded queue backoff rather than retrying aggressively.
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
