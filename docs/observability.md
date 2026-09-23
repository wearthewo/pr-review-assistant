# Observability

## Scope and philosophy

M16 instruments the existing application without changing review behavior, transactions, or idempotency. Logs describe individual operational events, metrics aggregate bounded outcomes, and health reports whether this application instance can serve its role. Telemetry is best effort: a metrics-registry failure cannot fail webhook acceptance, a job transition, publication, or usage accounting.

No monitoring vendor, collector, dashboard, alerting rule, or observability database is part of M16. A future production deployment may scrape the protected Prometheus endpoint and route JSON logs; that backend choice remains deferred to M20.

## Correlation and structured logs

Every HTTP request receives an `X-Correlation-ID` response header. An inbound value is retained only when it is 1-64 characters, starts with an ASCII letter or digit, and otherwise contains only ASCII letters, digits, `.`, `_`, or `-`. Invalid or absent input is replaced with a UUID. The value is stored in MDC as `correlation_id` only for the request and is restored or removed in `finally`, preventing thread reuse from leaking context.

Asynchronous work uses durable identifiers rather than HTTP correlation: `github_delivery_id`, `review_job_id`, and `publication_id`. These identifiers may appear in structured logs but never in metric labels.

Console logs use Spring Boot's Logstash JSON format. Application lifecycle events use stable fields where relevant:

| Event | Level | Principal fields |
| --- | --- | --- |
| `github_webhook_ingested` | INFO | `github_delivery_id`, `github_event`, `outcome` |
| `github_webhook_rejected` | WARN | controlled `reason`, `outcome` |
| `github_webhook_failed` | ERROR | controlled `reason`, `outcome` |
| `review_job_finished` | INFO/WARN/ERROR | `review_job_id`, `attempt`, `outcome`, optional `safe_error_code` |
| `review_job_transition_failed` / `review_job_stale_claim` | WARN/DEBUG | `review_job_id`, `attempt` |
| `publication_attempt_finished` | INFO/WARN/ERROR | `publication_id`, `attempt`, `outcome`, optional `safe_error_code` |
| `publication_stale_claim` | DEBUG | `publication_id`, `attempt` |

Logs do not contain request/response bodies, source, patches, prompts, model responses, findings, repository configuration, credentials, authorization headers, cookies, OAuth state/code/verifier, or provider error bodies. Customer names, profiles, and email are not observability fields.

## Metric namespace and inventory

Custom metrics use the Micrometer namespace `pr.review.assistant`. Prometheus converts dots to underscores and appends conventional suffixes such as `_total` and timer `_seconds` series.

| Micrometer name | Type | Controlled tags | Meaning |
| --- | --- | --- | --- |
| `pr.review.assistant.webhook.requests` | counter | `outcome`, `reason` | accepted, duplicate, rejected, or failed deliveries |
| `pr.review.assistant.webhook.duration` | timer | `outcome` | verified webhook ingestion latency |
| `pr.review.assistant.review.jobs.claimed` | counter | none | jobs returned by claims |
| `pr.review.assistant.review.jobs.stale.recovered` | counter | none | expired leases reclaimed |
| `pr.review.assistant.review.jobs.outcomes` | counter | `outcome` | completed, retryable, terminal, stale-claim, or unexpected outcomes |
| `pr.review.assistant.review.jobs.duration` | timer | `outcome` | one claimed review-job execution |
| `pr.review.assistant.github.operations` | counter | `operation`, `outcome` | logical outbound GitHub operations |
| `pr.review.assistant.github.duration` | timer | `operation`, `outcome` | logical GitHub operation latency |
| `pr.review.assistant.ai.requests` | counter | `outcome`, `error_type` | one logical provider invocation |
| `pr.review.assistant.ai.duration` | timer | `outcome` | logical provider latency |
| `pr.review.assistant.ai.tokens` | counter | `type` | provider-reported token measurements when present |
| `pr.review.assistant.review.findings` | counter | `stage`, `category`, `severity` | candidate and accepted finding funnel |
| `pr.review.assistant.review.findings.suppressed` | counter | `reason` | aggregate deterministic suppression reasons |
| `pr.review.assistant.publication.attempts` | counter | `outcome` | publication-worker outcomes, including ambiguity |
| `pr.review.assistant.publication.duration` | timer | `outcome` | one claimed publication execution |
| `pr.review.assistant.publication.reconciled` | counter | none | uncertain publications matched to an existing review |
| `pr.review.assistant.usage.events` | counter | `action`, `outcome` | reservation, consumption, and release observations |
| `pr.review.assistant.queue.depth` | gauge | `queue`, `state` | review/publication queue depth |
| `pr.review.assistant.queue.oldest.actionable.age` | gauge | `queue` | seconds since the oldest due or expired item became actionable |

Spring Boot's existing `http.server.requests` metrics remain the dashboard/backend HTTP signal; M16 does not duplicate them.

GitHub `operation` is restricted to application-owned values: installation token, repository, pull request, changed files, contents, review publication/reconciliation, and OAuth token/user/installations. AI `error_type`, worker/publication `outcome`, finding category/severity/suppression reason, queue/state, and usage action/outcome are similarly controlled enums or fixed values. Tenant, user, job, publication, delivery, repository, PR, SHA, URL, correlation ID, exception message, model response, and customer-supplied text are never tags.

An AI metric counts one logical `AiProvider` invocation. SDK-internal HTTP retries are not separately counted. Token counters are emitted only from a successful provider response and do not imply price or billing. The current provider error model does not expose a separate ambiguous AI result; publication ambiguity is explicitly preserved as its own outcome.

## PostgreSQL queue gauges

Gauges query PostgreSQL only when a registry scrape/read requests them. For each existing `review_jobs` and `publication_jobs` queue they expose:

- `ready`: `READY` with `next_attempt_at <= now`;
- `delayed`: `READY` with `next_attempt_at > now`;
- `processing`: `PROCESSING` with an unexpired lease;
- `stale`: `PROCESSING` with an expired lease.

Oldest actionable age is the minimum due `next_attempt_at` or expired `claim_expires_at`, or zero when no actionable row exists. Queries return scalar `count`/`min` values, hydrate no rows, run only during observation, and use the existing partial polling/lease indexes from V2 and V4. A query failure yields `NaN` rather than affecting business behavior. No metrics table, cache table, or migration was added.

## Health and Actuator exposure

- `/actuator/health/liveness` answers whether the application process is alive and does not depend on GitHub, OpenAI, or PostgreSQL.
- `/actuator/health/readiness` includes application readiness and PostgreSQL connectivity.
- `/actuator/health` remains publicly available with details hidden.
- `/actuator/prometheus` is exposed for future scraping but requires the existing authenticated Spring Security boundary.

No `env`, `configprops`, `beans`, heap/thread dump, generic `metrics`, or other sensitive Actuator endpoint is exposed. GitHub and OpenAI outages are represented through operation metrics and logs rather than making JVM liveness false.

## Local inspection

With the backend running on its default port:

```sh
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```

Prometheus export intentionally requires a valid dashboard API bearer token:

```sh
curl -H "Authorization: Bearer <fake-example-only>" \
  http://localhost:8080/actuator/prometheus
```

Never place real tokens in shell history or documentation. Automated tests use a synthetic test JWT and verify exported names without external services.

## Deferred work

Render collects application stdout/stderr in the M20 topology, and health checks use the existing readiness/root boundaries. `/actuator/prometheus` remains authenticated; M20 does not deploy a scraper or weaken that protection. Operators should alert on deploy/restart/readiness state immediately and add an authenticated collector for queue age, terminal work, ambiguity, and provider outcomes when a monitoring backend is selected.

A customer/admin observability UI, tracing backend, Prometheus/Grafana/Loki/Tempo stack, vendor SDK, durable log archive, formal SLOs, and alert routing remain deferred. Production retention and incident policy must be set by the operator; see [deployment.md](deployment.md).
