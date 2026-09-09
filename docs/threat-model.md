# Threat Model

## Scope

This threat model covers the implemented path from GitHub webhooks through durable processing, context retrieval, analysis, suppression, and disabled-by-default GitHub review publication, plus the future administrative frontend and software supply chain. Tenant persistence remains future scope.

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
| GitHub webhook forgery | An attacker creates unauthorized durable input or actions | Verify HMAC-SHA256 over the exact bounded raw body with constant-time comparison; reject before parsing or persistence; protect and rotate the distinct webhook secret; subscribe the App only to needed event types |
| Webhook replay or duplicate delivery | Duplicate work, cost, or GitHub comments | Atomically deduplicate the opaque globally unique delivery identifier at ingress; future job creation and publication must retain their own idempotency guards |
| Different deliveries for the same PR revision | Duplicate review jobs and eventual review cost | Uniquely key review work by authenticated installation ID, numeric repository ID, PR number, and head object ID; use conflict-safe insertion rather than select-before-insert |
| Crash between webhook and job creation | GitHub sees acceptance but the revision is never reviewed | Commit a new reviewable webhook and its job in one transaction; roll back the webhook when job insertion fails so redelivery remains effective |
| Signed but malformed PR payload | Permanent retry loop, corrupt job identity, or attacker-controlled diagnostics | Validate only bounded typed identity fields after authentication; retain the delivery without a job, acknowledge it, and expose no payload detail |
| Concurrent claims or worker crash | Duplicate active ownership, stuck work, or corrupt terminal state | Claim due rows with `FOR UPDATE SKIP LOCKED`, use expiring leases and a fresh token per claim, require that token on every transition, bound attempts, and execute work outside the claim transaction |
| Malicious or secret-bearing job error | Credentials or attacker text persists in queue state or logs | Persist only a validated bounded safe error code; never copy exception messages, response bodies, stack traces, source content, or credentials into M4 job state |
| Compromised installation token | Unauthorized repository reads or review publication | Use least permissions, short-lived tokens, server-only handling, no logging, revocation/rotation procedures, bounded use, and audit identifiers |
| Compromised GitHub App private key or App JWT | An attacker mints installation credentials across App installations | Mount the PEM as a protected secret, never log or persist key/JWT material, use short JWT lifetimes, rotate/revoke App keys, and restrict backend filesystem/runtime access |
| Installation-token cache confusion or refresh stampede | Cross-installation access or excessive GitHub authentication traffic | Key strictly by installation ID, supply that ID per authenticated operation, refresh before expiry, coalesce same-installation refreshes, isolate failures, and test concurrency deterministically |
| Cross-tenant data access | One customer reads or changes another customer's data | Carry verified tenant context through every operation, scope storage and cache access, enforce authorization at boundaries, avoid guessable identifiers as authorization, and test cross-tenant denial |
| Prompt injection through repository contents | Source text persuades a model or agent to reveal data or bypass policy | Mark repository material as untrusted data, separate it from system policy, expose no ambient tools or secrets, minimize context, require structured output, and validate against independent policy |
| Malicious diffs or repository data | Parser exploits, path tricks, excessive computation, terminal/log injection, or unsafe publication | Never execute code; validate encoding, paths, sizes, shapes, and output locations; use safe parsers; escape rendered content; isolate processing; impose time and resource bounds |
| Malicious repository configuration or prompt injection through config | Repository selects hidden instructions, external resources, secrets, or weakened review policy | Accept only fixed versioned typed fields; never concatenate raw config into instructions; reject unknown fields and keep models, prompts, confidence, output, and publication policy operator-owned |
| YAML object construction or alias bomb | Parser executes types or consumes excessive CPU/memory | Use safe construction, reject custom tags and duplicate keys, disable aliases, bound nesting/code points, and parse only after a strict byte ceiling |
| Oversized or complex ignore policy | Path matching exhausts resources or accesses server files | Limit pattern count/per-pattern/total characters; support only bounded segment globs over logical `/` paths; use deterministic matching with no regex, filesystem, shell, or URL behavior |
| Repository cost amplification through review mode | A contributor selects unbounded context, models, calls, or findings | Map only `FAST`, `BALANCED`, and `DEEP` to application-owned profiles under operator ceilings; retain one AI call and fixed suppression/publication limits |
| Category policy abuse or intentional disablement | Important checks are disabled or spend continues despite opt-out | Treat category flags as explicit repository policy, constrain the request before AI, and make all-disabled complete without AI/publication; future tenant authorization/governance remains required |
| Secrets embedded in repository config | Config values leak through diagnostics, prompts, or durable state | Reject unknown secret-shaped fields and never log, persist, serialize to AI, or include raw YAML/value excerpts in exceptions |
| Stale or default-branch configuration substitution | Policy from another revision controls the reviewed code | Fetch only the canonical fixed path with `ref` equal to the immutable reviewed HEAD SHA and load it once per analysis job |
| Secret leakage | Credentials or private source appear in logs, prompts, responses, UI, or fixtures | Centralize secret handling, redact sensitive fields, minimize collected and transmitted content, prohibit secrets in logs and models, use synthetic fixtures, scan commits and artifacts |
| Oversized webhooks or pull requests and resource exhaustion | Memory exhaustion, queue starvation, cost spikes, or provider overuse | Bound webhook reads; M6 bounds metadata/page bodies, pages, files, path length, per-file and total patch bytes and returns explicit unavailable/too-large outcomes; later add tenant quotas, backpressure, and cancellation |
| GitHub API abuse | Rate-limit exhaustion or actions beyond product purpose | Request minimum scopes, validate target installation/repository, budget requests per job and tenant, cache safely, honor rate-limit headers, back off, and audit outbound actions |
| Stale or substituted PR revision | Analysis applies to a different commit or repository | Resolve mutable names from authenticated numeric repository metadata, recheck PR number/base repository ID, compare exact head SHA before files, and terminally classify mismatches |
| Redirect or pagination credential exfiltration | Installation token reaches an attacker-controlled host | Disable redirects, never follow response-supplied pagination URLs, and derive bounded page requests on the configured GitHub client base |
| Malicious patch/path content | Filesystem traversal, prompt injection, log injection, or secret-shaped content propagation | Keep paths opaque and in-memory, perform no filesystem operations or code execution, redact string forms/logs, and treat patches only as bounded untrusted data |
| AI provider failures or hostile output | Stalled jobs, corrupted state, fabricated findings, or unsafe comments | Use timeouts and bounded retries, classify errors, validate schemas and evidence, treat output as untrusted candidates, allow deterministic-only degradation, and prevent direct publication |
| Source attempts to override review instructions | Secret disclosure, tool abuse, policy bypass, or attacker-selected findings | Keep application instructions separate from JSON-marked untrusted data, enable no tools or URL access, and test hostile instruction strings as inert source text |
| Hallucinated path or line | Misleading comment on unchanged/unrelated code | Require exact canonical changed-file paths and real new-side/HEAD line evidence; exclude previous rename, auxiliary-only, removed-HEAD, and unmapped locations without inventing replacements |
| Structured-output flooding or schema abuse | Excess cost, memory pressure, or noisy reviews | Use strict additional-property-free schema, bounded output tokens, maximum finding count and field lengths, then independently validate the parsed domain |
| Repeated provider calls | Multiplied cost and latency for one attempt | Make exactly one provider invocation per logical M9 analysis and bound provider-level retries; defer any second-opinion/escalation policy |
| Weak or overconfident model findings | Generic, speculative, or severity-inflated comments erode trust | Apply fixed confidence/severity/location/evidence gates; suppress rather than rewrite; treat confidence as policy input rather than semantic proof |
| Duplicate and overlapping findings | Repetitive comments overwhelm developers | Resolve same-category exact and strong bounded token-set overlaps deterministically, retain the stronger candidate, and cap publication candidates |
| Malicious content in model findings | URL fetching, command execution, local path access, HTML injection, or secret leakage | Treat every field as inert text; perform normalization only; make no network, URL, filesystem, shell, rendering, or tool call; keep content out of logs |
| Suppression analytics leakage | Rejected source or finding content reaches durable telemetry | Expose aggregate controlled reason counts only; do not persist or log paths, titles, evidence, explanations, fixes, source, prompts, or model output |
| Nondeterministic ranking | Retries or input ordering produce inconsistent publication candidates | Use fixed gates, explicit severity order, deterministic tie-breaks and IDs, immutable output, and stable final sorting |
| Ambiguous GitHub write | A timeout after acceptance causes a retry to publish a duplicate review | Mark the publication ambiguous before POST; reconcile the exact application marker through bounded derived pages before another POST |
| Marker forgery | Model-controlled finding text impersonates an earlier publication | Strip HTML comments from untrusted text and append the marker only in trusted rendering code; compare the complete 64-hex-key marker |
| Publication retry reruns AI | Transient GitHub failure multiplies paid calls and may change findings | Persist the final versioned payload and enqueue a separate publication job atomically; publication code has no analysis dependency |
| Unsafe Markdown output | Mentions, HTML, links, headings, task lists, or fences cause unwanted effects | Normalize control characters and neutralize active Markdown/HTML forms before persistence and publication; enforce per-field and total bounds |
| Notification spam | Duplicate or low-value reviews create unwanted developer notifications | Publish one `COMMENT` review per non-empty validated result, remain disabled by default, and reconcile ambiguous outcomes before retrying |
| Stale-SHA publication | Findings for an old revision are attached to a newer head | Send the immutable reviewed SHA as `commit_id`; never let GitHub select the current head implicitly |
| Publication-payload exposure | Persisted model-derived findings reveal repository information | Persist only sanitized accepted user-facing output and exact target metadata; exclude prompts, context, patches, rejected findings, secrets, and raw responses |
| Remote reconciliation spoofing | Another actor copies the non-secret marker and tricks local reconciliation | Scope lookup to the authenticated exact repository/PR and require the complete deterministic key marker; app-author identity verification is deferred until a stable identity input exists |
| Repository rename during publication | Stored routing names no longer address the numeric repository identity | Treat owner/name only as authenticated routing metadata and fail a 404 terminally; do not follow redirects with credentials; safe route refresh is deferred |
| Secondary rate limiting | Aggressive publication retries amplify GitHub abuse protection | Treat 403/422 with explicit rate-limit headers and 429 as retryable, then use bounded durable queue backoff without in-request sleeps |
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

M7 reduces source-exposure and cost risk by selecting before fetching, using immutable revision SHAs, rejecting unsafe repository paths, suppressing generated/vendor and external-package candidates, and enforcing independent candidate, request, file, line, and byte ceilings. Optional misses produce explicit partial context; authentication, repository identity, rate-limit, and transient failures retain M6/M4 classification. Source is ephemeral and is never logged, persisted, executed, or treated as instructions.

M8 keeps application instructions separate from hostile repository input, enables no model tools or URL access, and requests only structured JSON. M9 sends the minimum bounded M7 evidence across that boundary. M10 applies conservative support gates and ranking without another provider call. M11 persists only sanitized accepted output, separates publication retries from paid analysis, and reconciles ambiguous GitHub writes with an exact application marker. These controls reduce false positives and duplicate writes but cannot prove semantic correctness. Tenant policy, consent, retention, deletion, and data-use disclosure remain required before production enablement.

AI analysis can be wrong even after validation, GitHub permissions still carry impact, and software dependencies cannot be made risk-free. The product reduces these risks through bounded authority, high-confidence publication, human review, monitoring, and incident response.

The M2 cache is local to one process. Multiple application instances can each mint a token for the same installation, which is acceptable at current scale but may increase authentication traffic. Redis or distributed locking is not justified without measured multi-instance contention. The private key remains readable by the backend process; a future deployment may replace file-based signing with a sign-only key-management service through a separately reviewed design.

M3 retains accepted webhook payloads as raw UTF-8 text for audit fidelity and future processing. This increases the impact of database read access and requires a future retention/deletion policy before production data governance is finalized. Payloads are never routine log data. M3 does not implement timestamp-based replay rejection because GitHub's required delivery contract supplies no signed delivery timestamp; durable delivery-ID uniqueness handles redelivery without inventing an unreliable freshness signal.

M4 leases provide recovery, not exactly-once execution. A handler may have performed work before losing its lease, so all later external effects still require their own idempotency guard. Claim ownership is safe across backend processes, but scheduler coordination is intentionally process-local and each instance may poll. M5 jobs carry an authenticated installation reference and immutable repository/PR/revision identity, but the internal tenant mapping is not implemented; no outbound GitHub access may treat the payload alone as sufficient tenant authorization.

M5 retains malformed signed reviewable deliveries without a processing-status column. This is deliberate because interpretation is synchronous and atomic for valid work, while permanently malformed data has no safe job to recover. Operational reporting and retention for these records remain later requirements.

M6 holds complete bounded snapshots only in process memory. This reduces durable source exposure but means retrieval repeats after retry or process loss. GitHub's files endpoint can omit patches and caps results; M6 records absence truthfully and returns `TOO_LARGE` at configured completeness limits rather than claiming a partial snapshot is complete. Tenant authorization mapping is still absent, so production outbound enablement requires that later boundary even though installation-scoped authentication is implemented.

Review and update this model when adding an endpoint, permission, event type, data store, AI provider, executable analysis mechanism, deployment environment, tenant-facing feature, or material data-retention change, and after any security incident.
