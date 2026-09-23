# Pull Request Review Assistant

> Project status: **pre-alpha**. M20 defines a reproducible Render deployment foundation for the accepted M0-M19 system; no production resource has been created by this repository change.

Pull Request Review Assistant is a planned production-grade, multi-tenant GitHub App that will automatically review pull requests. It will combine deterministic analysis with AI-assisted analysis, validate and rank candidate findings, and publish a small set of high-confidence review comments back to GitHub.

## High-level architecture

The intended flow is:

```text
GitHub
  -> webhook ingestion
  -> Spring Boot API
  -> PostgreSQL-backed review job queue
  -> review worker
  -> GitHub API and context retrieval
  -> deterministic analysis and LLM analysis
  -> validation and ranking
  -> GitHub pull request review publishing
```

The API and worker are logical components of the backend. PostgreSQL is the initial system of record and durable queue. External systems—including GitHub and AI providers—are accessed through explicit integration boundaries. See [docs/architecture.md](docs/architecture.md) and the ADRs in [docs/adr](docs/adr).

## Planned stack

- Java 21 and Spring Boot 4.1.x
- Maven Wrapper
- PostgreSQL 18 and Flyway
- Next.js and TypeScript
- Docker Compose for local dependencies
- GitHub Actions
- OpenAPI
- OpenAI behind an internal provider abstraction

The backend currently uses Java 21, Spring Boot 4.1.1, Maven Wrapper 3.3.4 with Maven 3.9.16, PostgreSQL 18, Flyway, JPA, Actuator, Testcontainers, a narrow GitHub App client, signed webhook ingestion, revision-specific jobs, bounded context retrieval, safe exact-revision repository configuration, an OpenAI Responses API adapter behind an internal structured-generation boundary, deterministic false-positive suppression, durable GitHub review publication, and tenant-scoped AI-analysis usage accounting. The frontend foundation uses Node.js 24 LTS, Next.js 16.3.5, React 19.3.0, and strict TypeScript 5.9.3.

## Repository layout

```text
backend/     Spring Boot API/workers and production container definition
frontend/    Next.js App Router dashboard and production container definition
infra/       Local PostgreSQL Compose definition
docs/        Product, architecture, security, testing, and development documentation
docs/adr/    Accepted architecture decision records
render.yaml  Render Blueprint for two web services and managed PostgreSQL
```

Root policy and contributor files apply across the monorepo. Directory READMEs describe current scope and ownership without creating placeholder applications.

## Development philosophy

- Build the smallest production-sound increment required by the current milestone.
- Prefer clear boundaries and simple, testable designs over speculative abstractions.
- Keep domain logic independent from GitHub, persistence, and AI providers.
- Treat all repository and pull request data as untrusted.
- Design explicitly for tenant isolation, webhook idempotency, job retries, and least privilege.
- Prefer fewer high-confidence findings over many low-quality findings.
- Add infrastructure and dependencies only when a demonstrated requirement exists.
- Record material architecture changes in ADRs rather than changing direction silently.

## Documentation map

- [Product definition](docs/product.md)
- [Architecture](docs/architecture.md)
- [Security principles](docs/security.md)
- [Threat model](docs/threat-model.md)
- [Testing strategy](docs/testing-strategy.md)
- [Development guide](docs/development.md)
- [Continuous integration](docs/ci-cd.md)
- [Production deployment](docs/deployment.md)
- [Observability](docs/observability.md)
- [M17 security hardening review](docs/security-hardening.md)
- [Workflow reliability](docs/reliability.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

## Local backend development

Prerequisites are a Java 21 JDK and Docker with Docker Compose. The backend uses its checked-in Maven Wrapper, so a global Maven installation is not required.

```sh
cp .env.example .env
docker compose --env-file .env -f infra/docker-compose.yml up -d
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

On Windows PowerShell, use `Copy-Item .env.example .env` and run `mvnw.cmd` from `backend`.

After startup, public health, liveness, and readiness endpoints are under `http://localhost:8080/actuator/health`; Prometheus-format metrics at `/actuator/prometheus` require authentication. `POST http://localhost:8080/api/webhooks/github` is the GitHub boundary and authenticates requests with `X-Hub-Signature-256`. See [docs/development.md](docs/development.md) and [docs/observability.md](docs/observability.md) for configuration, inspection, test, and shutdown commands.

## Current milestone

M20 adds multi-stage non-root backend and frontend images, a root Render Blueprint, a production Spring profile, Render-generated PostgreSQL binding with required internal TLS, health/graceful-shutdown policy, and a fourth blocking-candidate `Docker` CI job. The intended first topology is two paid, non-sleeping Render web services plus one private Render PostgreSQL 18 instance in Frankfurt. The backend service initially owns both APIs and durable workers. Provisioning, provider registration, secret upload, custom domains, monitoring infrastructure, and any live deployment remain operator actions; see [docs/deployment.md](docs/deployment.md).

M19 establishes the repository's build-verification contract. GitHub Actions runs independent `Backend`, `Frontend`, and `E2E` Linux jobs for pull requests to `main`, pushes to `main`, and manual diagnostics. The workflow uses Java 21 plus the checksum-pinned Maven Wrapper, Node 24/npm 11 plus the committed lockfile, PostgreSQL 18.6 Testcontainers, blocking dependency audits, and failure-only bounded Playwright diagnostics. It has read-only repository permissions, receives no product secrets, and performs no deployment or artifact publication. See [docs/ci-cd.md](docs/ci-cd.md).

M18 verifies the signed-webhook-to-publication workflow against the real PostgreSQL queue, ownership, accounting, checkpoint, and publication stores while replacing only external GitHub and AI calls with deterministic boundaries. Flyway V10 adds a tenant-bound, one-per-job provider-neutral analysis checkpoint. Validated candidates and `RESERVED -> CONSUMED` usage now commit atomically; retries load the checkpoint before AI, so a crash after that commit cannot cause a second AI invocation. Empty analyses are checkpointed as well. Historical consumed rows without a checkpoint fail closed because their result cannot be reconstructed. See [docs/reliability.md](docs/reliability.md).

The system still does not claim exactly-once external AI execution: a provider result can become ambiguous if the process fails before receiving and checkpointing it. Such reservations remain conservatively active. GitHub publication retains its existing durable handoff, hidden-marker reconciliation, lease, and stale-owner protections.

M17 completes an application-level adversarial review of the implemented trust boundaries. It bounds active GitHub OAuth connection state per user, rejects ambiguous callback parameters, bounds GitHub App PEM parsing, and removes bidirectional display controls from model-authored GitHub publication text. It adds no Redis, WAF, deployment platform, product feature, or organization-ownership workflow.

M16 adds structured JSON operational logs, bounded HTTP correlation IDs, low-cardinality Micrometer metrics, PostgreSQL queue depth/age gauges, and explicit liveness/readiness groups. The Prometheus registry is the only new dependency; no Prometheus server, monitoring vendor, frontend observability UI, database table, or migration is added. `/actuator/prometheus` is exposed only behind the existing authenticated Spring boundary, while health details remain hidden. Instrumentation is best effort and cannot change authoritative transactions or idempotency. See [docs/observability.md](docs/observability.md) for the exact metric and tag inventory.

M13D1 connects an authenticated SaaS user to an existing M14 tenant only through the existing GitHub App's server-side user authorization flow. Next.js accepts no installation or role authority from the browser. Spring creates a high-entropy state and PKCE challenge, stores only the state hash with a ten-minute lifetime and application-user binding, exchanges the callback code server-side, verifies the stable GitHub user ID, and reads bounded pages of `GET /user/installations`. The temporary GitHub user token is never persisted, returned, rendered, or logged.

GitHub documents installation visibility as access, which may include collaborators and organization members; it is not sufficient to grant organization `OWNER`. The implemented rule therefore grants `OWNER` only when an existing M14 installation targets a personal `User` account whose numeric account ID exactly matches the authenticated GitHub user's numeric ID. Organization installations, multiple matches, unknown installations, existing other owners, stale/replayed/cross-user state, and setup-URL hints all fail closed. `installation_id` from GitHub's setup URL is ignored. Repository management and organization ownership proof remain deferred.

M13B uses Auth0 Universal Login as one production-capable OIDC path. Next.js keeps the encrypted session in an HttpOnly cookie and obtains access tokens only on the server. It calls `GET /api/dashboard/session` with `Authorization: Bearer`; Spring independently validates RS256 signature, expiry/not-before, issuer, and audience before provisioning an internal user keyed by `(issuer, subject)` and resolving durable tenant memberships. The browser never supplies an authoritative tenant identity.

M13C turns that boundary into a responsive, server-rendered dashboard shell. An authenticated user without membership sees a closed onboarding state; a member sees only memberships returned by Spring. The optional `tenant` query value identifies a desired membership, but the server renders it only after an exact match against that authenticated set. Unknown values disclose no tenant data and never fall back to another tenant. Overview contains descriptive pipeline and membership state only—no fabricated repository, review, finding, usage, quota, token, or spend metrics.

Historical tenants remain valid and inaccessible until the M13D1 proof matches their existing personal installation. No user is attached from a tenant UUID, submitted installation ID, repository ID/name, organization, email, or domain. Organization onboarding still requires stronger server-verified authority. Settings remains a disabled navigation foundation; repositories, review history, and usage are read-only and repository mutation remains deferred.

M13D2 makes Repositories the first tenant-resource dashboard. `GET /api/dashboard/tenants/{tenantId}/repositories` treats the path UUID only as a requested identifier, independently reauthorizes the JWT-backed application user through `tenant_memberships`, and then reads a bounded, deterministically ordered list from M14's `tenant_repositories`. The DTO exposes only the numeric GitHub repository ID and the time the ownership record was first stored. Repository names, visibility, enablement, configuration state, and review metrics are not persisted and are neither inferred nor fabricated. Ordinary dashboard rendering performs no GitHub or AI call; PostgreSQL is the available read model and may lag GitHub.

M13E makes Reviews a second read-only tenant-resource page. A history entry is one target-bearing `review_jobs` execution; an optional `review_publications` row supplies the publication state and exact accepted/publishable finding count. Spring independently authorizes membership, then performs one bounded keyset query ordered by `(created_at DESC, id DESC)`. The default page is 20 and the maximum is 50. Cursors are bounded, canonical base64url values bound to the tenant, timestamp, and job boundary; they position a page and never authorize it. The UI displays numeric repository ID, PR number, abbreviated exact head SHA, controlled state, timestamp, and a publishable count only when a publication exists. It performs no GitHub, OpenAI, worker, retry, or mutation operation.

The state mapping is deliberately conservative: `READY` becomes `QUEUED`, `PROCESSING` becomes `ANALYZING`, job `FAILED` becomes `ANALYSIS_FAILED`, and job `COMPLETED` without a publication becomes `COMPLETED_WITHOUT_PUBLICATION`. Publication state takes precedence: `PENDING`, `AMBIGUOUS`, `PUBLISHED`, and `FAILED` become `PUBLICATION_PENDING`, `PUBLICATION_UNCERTAIN`, `PUBLISHED`, and `PUBLICATION_FAILED`. Absence of a publication cannot prove zero findings, so the dashboard never labels that state as zero findings.

M13F makes Usage a third read-only tenant-resource page. `GET /api/dashboard/tenants/{tenantId}/usage` independently reauthorizes membership, reuses M15's configured quota policy and exact UTC month calculation, and performs one aggregate count over `REVIEW_ANALYSIS` events in the half-open period. `RESERVED` and `CONSUMED` occupy quota; `RELEASED` does not. The response contains only period boundaries, limit, used, and nonnegative remaining units. It exposes no accounting rows, tokens, provider metadata, plans, or prices and performs no GitHub, OpenAI, quota-lock, or accounting-mutation operation.

M13G verifies those pieces as one boundary. Overview, Repositories, Reviews, and Usage preserve the selected authorized workspace in server-rendered navigation, while every Spring resource endpoint still independently derives authorization from the verified JWT and durable membership. All dashboard response readers enforce byte ceilings incrementally, including chunked responses without `Content-Length`. Playwright exercises production-like CSP/headers and deterministic desktop/mobile user states without a production authentication bypass; real Auth0 and GitHub provider smoke tests remain deployment work.

The initial operator policy is confidence 85, minimum severity MEDIUM, and at most three publication candidates. Silence is better than a weak comment; zero accepted findings completes without a GitHub write.

M11 renders accepted findings into one `COMMENT` review tied to the exact head SHA. It atomically stores a versioned, bounded publication payload and a separate PostgreSQL publication job before analysis completes. Publication retries never invoke retrieval, context building, suppression, or AI. An application-owned hidden marker and an `AMBIGUOUS` state reconcile uncertain POST outcomes before another write, preventing duplicate reviews. Publishing is disabled by default and requires the GitHub App `Pull requests: write` repository permission.

## Tenant and installation ownership

GitHub numeric installation and repository IDs are the only external ownership authorities. Owner/name strings, webhook sender fields, repository content, `.reviewbot.yml`, and supplied tenant-like fields never select an internal tenant. First use creates the tenant, installation, and repository mappings in one transaction; PostgreSQL advisory locks plus unique and composite foreign-key constraints make concurrent provisioning converge on one mapping and prevent cross-tenant reassignment.

Review jobs carry the resolved tenant and internal repository association. Workers reject unresolved or mismatched ownership before GitHub retrieval, configuration loading, AI, or publication. Publications retain the same tenant association as their analysis job, and publication-only retries reuse persisted ownership without reprovisioning. Historical pre-M14 jobs/publications keep nullable ownership during this safe transition and cannot execute tenant-required production paths. Installation removal, repository transfer reconciliation, tenant suspension, RLS, retention, organization ownership bootstrap, and billing remain deferred.

## Repository review configuration

M12 optionally reads one fixed `.reviewbot.yml` file from the authenticated base repository at the exact pull-request base SHA. Changed code still comes from the exact head SHA. This prevents a pull request—including one from a fork—from weakening its own review policy; a merged policy change applies to later pull requests whose base revision includes it. Missing or invalid configuration uses safe defaults: balanced mode, no ignore patterns, and all defect categories enabled. The only accepted version-1 controls are `review.mode`, bounded ignore globs, and booleans for the existing categories. Raw YAML is never sent to AI, logged, persisted, or interpreted as instructions.

```yaml
version: 1
review:
  mode: balanced
ignore:
  - "**/generated/**"
  - "**/*.lock"
categories:
  correctness: true
  security: true
  concurrency: true
  transactional_integrity: true
  reliability: true
  api_misuse: true
  performance: false
```

`fast`, `balanced`, and `deep` select application-owned profiles under operator hard ceilings; they cannot select a model or weaken suppression. Ignored files are removed before context discovery and AI. The config file excludes itself. If every file is ignored or every category is disabled, the job completes without AI or publication. Publication retries use the already persisted output and never reread configuration.

## Usage accounting and quotas

The billable unit is one logical AI provider analysis invocation. Webhook receipt, stale jobs, configuration-only outcomes, all-disabled/all-ignored policy, deterministic suppression, and publication retries do not consume a unit. A successful provider response consumes the reservation even when later validation or suppression produces no published findings.

`REVIEW_USAGE_MONTHLY_LIMIT` defaults to `50` and accepts values from 1 through 100000. Quotas use half-open UTC calendar months. PostgreSQL transaction advisory locks serialize each tenant/month decision, while a unique review-job usage key makes retries idempotent. Both `RESERVED` and `CONSUMED` events occupy quota; `RELEASED` records remain auditable but no longer count. Provider-reported token measurements are optional metadata only: absent values remain unknown and mixed known/unknown data is reported as partial, never fabricated as zero.

If a process loses the provider outcome after reserving, the reservation remains conservatively active. A retry will not make a second potentially paid call. The tenant-facing dashboard reports that conservative state as quota usage without calling it completed work. Automated stale-reservation release, reconciliation, pricing, invoices, plan management, and historical usage reporting are intentionally deferred.
