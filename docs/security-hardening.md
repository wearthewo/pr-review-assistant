# M17 Security Hardening Review

## Scope and trust boundaries

M17 reviewed the implemented application through M16. The relevant boundaries are GitHub webhook to Spring (raw-body HMAC authority); Auth0 token to Spring (RS256 issuer/audience/time authority); browser to Next.js (Auth0 HttpOnly session and same-origin mutation checks); GitHub OAuth to ownership bootstrap (hashed user-bound state, S256 PKCE, authenticated GitHub identity); application to PostgreSQL (parameterized operations and constraints); worker to GitHub (fixed origins and installation tokens); review pipeline to OpenAI (bounded provider-neutral request, no tools); and validated review to GitHub publication (deterministic suppression and rendering).

External webhook/HTTP data, JWT bytes and claims before validation, callback/query/header values, database content originating externally, repository paths/source/patches/configuration, provider responses, and AI output remain untrusted. GitHub App PEM/JWT, webhook secret, installation tokens, callback-local GitHub user token, Auth0 configuration/session/bearer tokens, OpenAI key, and database credentials remain confined to server integration boundaries.

## Attack-surface matrix

| Class | Asset | Attacker / entry point | Attack | Existing control | M17 action | Remaining risk |
| --- | --- | --- | --- | --- | --- | --- |
| Spoofing | webhook authority | Internet client / webhook endpoint | forge or replay a delivery | bounded raw read, exact SHA-256 HMAC, constant-time compare, delivery uniqueness | re-audited order and regressions | webhook-secret compromise requires rotation |
| Spoofing | dashboard identity | bearer-token caller / dashboard API | forged, expired, wrong-issuer, or wrong-audience JWT | Spring Resource Server, RS256 only, issuer/audience/expiry/not-before validation | re-audited validators and tests | Auth0 account/provider compromise |
| Tampering | ownership bootstrap | authenticated browser / OAuth routes | replay, cross-user substitution, polluted callback, spoofed installation | hashed expiring atomic state, PKCE, user binding, setup ID ignored | exact-one callback parameters and bounded state issuance | organization ownership remains unsupported |
| Tampering | tenant data | authenticated tenant user / dashboard identifiers | tenant, repository, job, or cursor substitution | membership context, tenant SQL predicates, composite FKs, non-enumerating denial | cross-feature/constraint audit | no PostgreSQL RLS; privileged process compromise |
| Repudiation | asynchronous effects | worker crash/retry / durable queues | stale completion or duplicate publication | durable timestamps, leases, claim tokens, publication key/marker | concurrency and idempotency audit | formal retention policy remains deferred |
| Disclosure | credentials/customer content | malformed requests, errors, telemetry, browser | leak secret, token, source, prompt, or internal response | bounded codes, controlled tags, server-only tokens, React escaping, strict CSP | logging/source scan and Actuator regression | privileged DB/runtime access remains high impact |
| DoS | webhook/review resources | Internet client or hostile repository / ingestion and retrieval | force buffering, parsing, API, AI, or retry exhaustion | size/page/count/token/time/retry bounds; signature before parsing/business work | complete limit and retry audit | deployment connection limits are M20 work |
| DoS | temporary OAuth state | authenticated user / connection start | create unbounded unused durable rows | TTL and opportunistic cleanup | per-user ceiling, advisory lock, indexed pruning | no distributed request-rate limiter |
| Privilege escalation | tenant ownership | authenticated GitHub user / callback | confuse installation access with ownership | exact personal-user account-ID rule; organizations fail closed | re-audited spoofed/multiple/other-owner paths | secure organization proof deferred |
| Privilege escalation | review policy/publication | hostile repository or model / AI pipeline | change policy, escape schema, or publish abusive text | BASE-SHA policy, no tools, strict domain validation, deterministic suppression | bidi-control removal and hostile-output audit | semantic model error cannot be eliminated |

## Production fixes

1. **OAuth-state storage exhaustion:** an authenticated user could create unlimited unconsumed rows during the TTL. Creation now takes a transaction-scoped per-user advisory lock, removes expired/old consumed rows, prunes older active rows, and inserts under a default ceiling of five (configurable 1-20). V9 adds the partial user/order index. Entropy, hashing, PKCE, expiry, user binding, and atomic consume are unchanged.
2. **OAuth callback parameter pollution:** callback parsing previously selected the first code/state. It now requires exactly one of each and rejects provider error callbacks through the bounded failure path.
3. **Unbounded PEM parse input:** private-key loading now reads at most 64 KiB plus a sentinel byte, rejects empty/oversized input, clears its temporary buffer, and retains library-based PKCS#1/PKCS#8 parsing and redacted errors.
4. **Bidirectional Markdown deception:** the publication renderer removes Unicode bidi display controls before persistence/publication while retaining the existing HTML, link, mention, fence, and active-Markdown neutralization.

## Resource-limit inventory

| Boundary | Limit | Enforcement / failure |
| --- | --- | --- |
| Webhook body | 1 MiB default; max 25 MiB | bounded controller read; 413 before parse/persistence |
| Webhook signature | `sha256=` + 64 hex | verifier; authentication failure |
| Delivery/event metadata | bounded value models | 400, no persistence |
| OAuth callback state/code | 512 chars, no controls, exactly one each | Next/Spring validation; safe connection failure |
| OAuth state lifetime/count | 10 min default, max 30 min; 5 active/user default, max 20 | properties and PostgreSQL store; older state invalidated |
| OAuth installations | 10 pages/1,000 default; max 20/2,000 | client; typed too-many failure |
| OAuth response | 256 KiB default; max 1 MiB | bounded decoded stream; safe invalid response |
| Dashboard bodies | session 64, repository 128, review 192, usage 32 KiB | incremental server reader; safe error UI |
| Repositories | 100 plus truncation sentinel | tenant query; explicit truncation |
| Reviews | page 20 default/50 max; cursor 160 chars | keyset service; bounded response/400 |
| Changed files | 1,000 files/10 pages default | loader; explicit too-large result |
| Patch | 256 KiB/file, 5 MiB total default | loader; unavailable/too-large result |
| GitHub files response | 8 MiB/page default | bounded client read; typed failure |
| Repository context | 12 files, 128 KiB/file, 512 KiB total, 100 candidates, 20 API calls | context builder; explicit omissions |
| Repository config | 32 KiB default, 64 KiB hard | parser; safe defaults for invalid/oversized data |
| AI input/schema/output | 120k chars, 64 KiB, 2,048 tokens | properties/provider; typed failure |
| AI findings | 5 default, 10 hard; bounded fields; 200-line span | schema plus domain validation; reject |
| Publication | 6k summary, 2k/comment, 20k total; 3 accepted default | renderer/suppression; bounded terminal failure |
| PEM private key | 64 KiB | bounded loader; redacted invalid configuration |
| Correlation ID | 64 safe ASCII chars | filter; generate UUID on invalid input |
| GitHub HTTP | 5s connect/20s request | JDK client; typed failure |
| OAuth HTTP | 5s connect/15s read | JDK client; typed failure |
| OpenAI HTTP | 60s default | SDK configuration; provider failure |
| Dashboard backend fetch | 10s | AbortSignal; safe timeout UI |
| Queue retries | 3 default, max 100, capped backoff | durable job/publication state; terminal failure |

Limits apply to bytes exposed by response streams. Clients do not implement a separate decompression pipeline. Redirects are disabled and GitHub pagination derives fixed-origin requests instead of following `Link` URLs.

## Timeout, retry, SQL, SSRF, and durable-growth findings

All remote operations have finite timeouts. Worker/publication retries are durable, finite, and backed off; OpenAI defaults to one SDK retry inside one logical metered call. There are no retry sleeps in request paths.

External SQL values use bound parameters. The few assembled SQL fragments (`QueueMetrics`, dashboard review continuation, publication transitions) are application-owned constants/branches, not external values. Outbound GitHub/OAuth clients use configured fixed base origins and disabled redirects; response pagination URLs are not fetched. Frontend server clients use fixed validated backend URLs and `redirect: error`. No repository path reaches filesystem APIs.

OAuth state is temporary security state and is bounded in M17. Webhook deliveries, review jobs, publications, and usage events are legitimate business/audit history and are deliberately not deleted by this milestone. Retention/archival/erasure remains required before production scale.

## Security regression matrix

| Threat | Control | Representative tests | Remaining risk |
| --- | --- | --- | --- |
| forged/oversized webhook | raw HMAC first and body cap | webhook verifier/controller tests | secret compromise |
| JWT forgery/time/issuer/audience | RS256 validators | `DashboardJwtValidatorsTest`, dashboard integration tests | identity-provider compromise |
| cross-tenant IDOR | membership, tenant predicates/FKs | cross-feature security integration | privileged process/DB compromise |
| OAuth replay/cross-user | atomic hashed user consume | ownership bootstrap integration | stolen live sessions |
| OAuth state flooding | locked per-user pruning/index | concurrent state creation integration | no request-rate limiter |
| callback pollution | exact-one parser | frontend callback regression | provider protocol change |
| SSRF/token exfiltration | fixed origins, redirects off, derived pages | GitHub client contract tests | malicious operator configuration |
| prompt injection | data-only context, no tools, deterministic gates | context/AI/suppression tests | semantic model error |
| hostile AI output | exact fields/counts/path/line validation | `AiReviewEngineTest` | valid-looking incorrect result |
| Markdown deception/spam | deterministic sanitizer and caps | `PublicationModelTest` | plain-text social engineering |
| PEM exhaustion/disclosure | bounded read/redacted failure | `GitHubAppJwtServiceTest` | process-level key theft |
| queue/race abuse | DB locks, leases, claim tokens, unique keys | concurrency persistence suites | at-least-once side effects need idempotency |
| telemetry abuse | safe correlation and fixed tags | observability tests | collector policy is deployment-owned |
| Actuator disclosure | public health, protected Prometheus only | security/observability integration tests | scrape identity policy deferred |

## Operations and residual risk

The production runtime database role needs connect/schema usage and CRUD on application objects. Flyway additionally requires DDL/schema-history privileges. M20 should separate migration and lower-privilege runtime roles; M17 does not create deployment users.

Application controls do not replace deployment connection limits, reverse-proxy limits, egress policy, secret management, monitoring retention, or WAF policy. Auth0/GitHub/OpenAI compromise, stolen live sessions, privileged runtime/database compromise, semantic model errors, and long-term record retention remain residual risks.
