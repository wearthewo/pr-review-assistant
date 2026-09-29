# Production deployment

## Scope and topology

M20 defines, but does not create, the first production topology on Render. The root `render.yaml` provisions exactly three resources in `frankfurt`:

```text
Internet -> Next.js web service
GitHub   -> Spring Boot web service (/api/webhooks/github)
Next.js  -> Spring Boot public HTTPS origin (server-to-server only)
Spring   -> Render PostgreSQL 18 internal connection (TLS required)
```

The Spring service initially runs HTTP APIs, review polling, and publication polling in one process. This is deliberate: PostgreSQL leases, `SKIP LOCKED`, claim tokens, unique keys, the durable analysis checkpoint, and publication reconciliation already make old/new overlap and later multi-instance polling safe. Redis, Kafka, Kubernetes, a separate worker service, and a second database are not required at this scale.

Both application services use paid, non-sleeping compute. The backend uses `1c-2g` because a JVM, HTTP API, and bounded workers share the process. The frontend uses `0.5c-512mb`. PostgreSQL uses `0.5c-1g`, 15 GB storage, no public IP allowlist, and no external pooler. These are current Render plan identifiers, not price commitments. The initial database tier has no high-availability standby; scale it before availability requirements exceed single-primary plus managed recovery.

## Container images

`backend/Dockerfile` builds the executable jar with the Maven Wrapper on Eclipse Temurin 21.0.12+8 and copies only that jar into a digest-pinned, shell-free Java 21 distroless runtime. It runs as UID/GID 65532. The image build skips test execution because the `Docker` CI job depends on the complete `Backend` verification job; it does not skip compilation or packaging.

`frontend/Dockerfile` uses Node 24.21.0 and npm 11.19.1 for `npm ci` and the production build. Next.js `output: "standalone"` keeps the runtime image limited to the standalone server and static assets. It runs as the image's unprivileged `node` user. Build-only values are fixed `.invalid` origins and non-secret placeholders; runtime configuration is injected by Render and no `NEXT_PUBLIC_*` product secret exists.

Each build context has a `.dockerignore` that excludes build output, dependency directories, local environment files, logs, and key material. Neither image contains Maven caches, source-control metadata, a private key, or a runtime credential.

## Ports, TLS, and proxy boundary

Both services bind `0.0.0.0:${PORT}`; the Blueprint fixes `PORT=10000`, which is Render's conventional private HTTP port. Render terminates public TLS. `APP_BASE_URL`, GitHub's OAuth callback, Auth0 callbacks/logout origins, and `BACKEND_BASE_URL` must use their final public HTTPS origins.

The frontend calls the backend from Server Components with a server-only bearer token. M20 deliberately retains the existing production requirement that `BACKEND_BASE_URL` is HTTPS instead of weakening it for plaintext private-network HTTP. The Render Blueprint pins this origin to the verified backend custom domain, `https://api.pullsage.com`; dashboard recovery uses the same origin and does not fall back to a separately configured Render subdomain. Browsers do not call Spring directly, and Spring CORS remains disabled. The backend is public only because GitHub webhooks and authenticated dashboard APIs require it; Spring Security still denies unspecified routes.

The application does not derive tenant authority, OAuth destinations, or provider callbacks from forwarded host headers. Provider callbacks are explicit configuration, so M20 does not enable broad forwarded-header trust. If custom domains are added, update `APP_BASE_URL`, `BACKEND_BASE_URL`, Auth0 allowed callback/logout/web origins, the GitHub callback, and the GitHub webhook URL together, then verify redirects before removing an old domain.

## Environment matrix

`sync: false` means the operator supplies the value during initial Blueprint creation. Render-generated database references are not copied manually. Every value in the Blueprint is listed below; all changes require a service redeploy/restart. Application tuning variables not listed retain the bounded defaults in `application.yml` and are optional rather than hidden deployment prerequisites.

| Service | Variable | Class | Required | Purpose / safe example | Source |
| --- | --- | --- | --- | --- | --- |
| backend | `SPRING_PROFILES_ACTIVE` | server config | yes | activates fail-closed production behavior; `production` | Blueprint |
| backend | `PORT` | platform config | yes | container listen port; `10000` | Blueprint/Render |
| backend | `DB_CONNECTION_URI` | platform/generated secret | yes | internal PostgreSQL URI; no example value | database `connectionString` reference |
| backend | `DB_USERNAME` | platform/generated config | yes | generated database role; no example value | database `user` reference |
| backend | `DB_PASSWORD` | platform/generated secret | yes | generated database password; no example value | database `password` reference |
| backend | `DB_NAME` | platform/generated config | yes | generated database name; `pr_review_assistant` | database `database` reference |
| backend | `GITHUB_APP_ID` | public config | yes | GitHub App numeric identifier; provider supplied | operator/GitHub |
| backend | `GITHUB_PRIVATE_KEY_PATH` | server config | yes | fixed secret-file mount path; `/etc/secrets/github-app-private-key.pem` | Blueprint |
| backend | `GITHUB_WEBHOOK_SECRET` | secret | yes | HMAC verification credential; no example value | operator/GitHub App owner |
| backend | `GITHUB_OAUTH_CLIENT_ID` | public config | yes | ownership-flow client identifier; provider supplied | operator/GitHub App owner |
| backend | `GITHUB_OAUTH_CLIENT_SECRET` | secret | yes | ownership-flow client credential; no example value | operator/GitHub App owner |
| backend | `GITHUB_OAUTH_CALLBACK_URL` | public config | yes | final frontend callback; `https://app.example.invalid/github/callback` | operator |
| backend | `DASHBOARD_AUTH_ISSUER` | public config | yes | trusted OIDC issuer; `https://tenant.example.invalid/` | operator/Auth0 |
| backend | `DASHBOARD_AUTH_AUDIENCE` | public config | yes | backend API audience; `https://api.example.invalid` | operator/Auth0 |
| backend | `DASHBOARD_AUTH_JWK_SET_URI` | public config | yes | same-origin JWKS URL; `https://tenant.example.invalid/.well-known/jwks.json` | operator/Auth0 |
| backend | `OPENAI_API_KEY` | secret | yes while AI enabled | provider credential; no example value | operator/OpenAI |
| backend | `REVIEW_WORKER_ENABLED` | server config | yes | enables durable review polling; `true` | Blueprint |
| backend | `REVIEW_WORKER_LEASE_DURATION` | server config | yes | crash-recovery lease; `5m` | Blueprint |
| backend | `REVIEW_PUBLICATION_ENABLED` | server config | yes | enables durable publication polling; `true` | Blueprint |
| backend | `REVIEW_PUBLICATION_LEASE_DURATION` | server config | yes | publication recovery lease; `3m` | Blueprint |
| backend | `REVIEW_AI_ENABLED` | server config | yes | enables accepted OpenAI adapter; `true` | Blueprint |
| frontend | `PORT` | platform config | yes | standalone server listen port; `10000` | Blueprint/Render |
| frontend | `BACKEND_BASE_URL` | server config | yes | verified backend public HTTPS origin; `https://api.pullsage.com` | Blueprint |
| frontend | `AUTH0_DOMAIN` | public config | yes | Auth0 tenant hostname; `tenant.example.invalid` | operator/Auth0 |
| frontend | `AUTH0_CLIENT_ID` | public config | yes | Auth0 application identifier; provider supplied | operator/Auth0 |
| frontend | `AUTH0_CLIENT_SECRET` | secret | yes | Auth0 application credential; no example value | operator/Auth0 |
| frontend | `AUTH0_SECRET` | platform/generated secret | yes | 256-bit encrypted-session key; no example value | Render `generateValue` |
| frontend | `AUTH0_AUDIENCE` | public config | yes | same backend API audience; `https://api.example.invalid` | operator/Auth0 |
| frontend | `APP_BASE_URL` | public config | yes | final frontend HTTPS origin; `https://app.example.invalid` | operator |

Render Blueprint syntax cannot declare secret-file contents. Before a successful backend deploy, upload the existing GitHub App PKCS#8 PEM as the backend secret file named exactly `github-app-private-key.pem`. Docker services receive it at `/etc/secrets/github-app-private-key.pem`. Never paste the PEM into YAML or an ordinary environment variable.

The production profile fails startup when generated database data is invalid, dashboard authentication or GitHub ownership authorization is absent, provider endpoints are non-HTTPS, or the GitHub private key cannot be parsed. AI enablement separately requires its provider key. Error messages redact configuration values.

## Database and migrations

The backend receives Render's generated internal PostgreSQL connection URI plus generated user/password/database references. A production-only adapter converts the URI to a JDBC URL without copying credentials into the URL and adds `sslmode=require`; Render internal PostgreSQL uses self-signed TLS, so hostname verification is unavailable on that path. External database access is blocked by `ipAllowList: []`.

Hikari is capped at five connections with one minimum idle connection. During a zero-downtime replacement, old and new backend instances therefore use at most ten application-pool connections plus short startup/migration overhead, below the selected database plan's connection ceiling.

Flyway runs exactly once as part of Spring startup; there is no `preDeployCommand`. A separate pre-deploy command cannot invoke the packaged application in migration-only mode without adding a new runtime mode, and running both would violate the one-strategy rule. Flyway's schema history lock serializes concurrent starters. A migration or Hibernate validation failure prevents readiness, so Render keeps the previous healthy instance serving traffic.

Every production migration must be backward-compatible with the still-running previous version. Use expand/migrate/contract across separate releases; do not rename/drop a column or tighten a constraint while old code can still run. Never edit V1-V10. The generated Render database user currently performs both migrations and runtime access because the initial Blueprint exposes one generated credential. Separate migration/runtime roles remain a hardening step when deployment automation can manage and rotate both safely; do not grant superuser access.

## Health, startup, and shutdown

Render checks backend `/actuator/health/readiness`. It includes process readiness and PostgreSQL, hides details, and does not depend on GitHub, Auth0, or OpenAI. Liveness remains `/actuator/health/liveness`. The frontend health path is `/`; it proves the standalone server, production environment validation, proxy, and rendering boundary can start without calling an external provider.

Spring graceful shutdown has a 110-second phase timeout, scheduled work is allowed up to 105 seconds to finish, and Render allows 120 seconds after `SIGTERM` before force termination. New scheduler instances may overlap old ones during a deploy. Database row locks prevent simultaneous claims; leases recover killed work; claim tokens reject stale completion; usage reservations/checkpoints prevent automatic duplicate AI; publication markers reconcile ambiguous GitHub writes. A forced stop can delay a job until lease expiry but does not require manual repair. The unavoidable provider-response-before-checkpoint ambiguity described in `reliability.md` remains conservative rather than exactly once.

## Provisioning order

1. Merge to `main` only after required `Backend`, `Frontend`, `E2E`, and `Docker` checks pass. Protect `main`; do not enable a bypassing deploy path.
2. Create one Render Blueprint from the repository's root `render.yaml`. Verify the selected branch is `main` and all three resources are in `frankfurt`.
3. Supply every `sync: false` value. Use the final HTTPS service/custom-domain origins, not temporary HTTP or local URLs.
4. Upload `github-app-private-key.pem` to the backend Secret Files area. An initial backend deploy attempted before this step is expected to fail closed; redeploy after the file exists.
5. In Auth0, register the frontend `/auth/callback`, logout origin, and web origin. In the GitHub App, register the frontend `/github/callback` and backend `/api/webhooks/github` URLs, and configure the exact webhook secret.
6. Confirm Flyway reaches V10, then confirm backend readiness and frontend health. Do not enable traffic manually around a failing health check.
7. Run the post-deploy checklist below with a dedicated test tenant/repository. Never use a personal access token.

`autoDeployTrigger: checksPass` deploys commits on `main` only after GitHub checks pass. Pull-request previews are disabled. The repository stores no Render API key or deploy hook.

## Post-deploy smoke checklist

1. Frontend HTTPS root returns the landing page and strict CSP/security headers.
2. Unauthenticated `/dashboard` follows the constrained Auth0 login path.
3. Auth0 callback returns to the fixed dashboard destination without a token in URL or HTML.
4. An authenticated but unbound user sees onboarding and no fabricated tenant.
5. GitHub ownership connection uses the fixed callback and ignores submitted installation identifiers.
6. An authorized membership can load Overview.
7. Repositories shows only that tenant's persisted repositories.
8. Reviews shows only that tenant's bounded history.
9. Usage shows only that tenant's current UTC quota period.
10. A foreign tenant UUID fails closed without existence disclosure.
11. Backend `/actuator/health/liveness` is healthy and detail-free.
12. Backend `/actuator/health/readiness` is healthy and detail-free.
13. Unauthenticated `/actuator/prometheus` is rejected.
14. A locally generated HMAC request with the configured webhook secret receives the expected accepted response.
15. Exact webhook redelivery remains idempotent.
16. A controlled PR event creates one tenant-owned review job.
17. The review worker processes it without exposing source or credentials in logs.
18. Usage is reserved/consumed once and a durable analysis checkpoint exists after successful AI.
19. Publication either succeeds once or enters its documented bounded recovery state.
20. A redeploy/controlled stop leaves no permanently owned job; any interrupted claim recovers after its lease.

## Logs, metrics, backup, rollback, and rotation

Both services log to stdout/stderr for Render collection. Structured backend events remain bounded and secret-free. In the Render workspace, set **Integrations -> Notifications** to email and Slack with at least failure notifications; verify per-service overrides do not disable backend/frontend health or deploy alerts. Render treats a running service as unhealthy after repeated health failures and can restart it, so route those notifications to the paging path.

For retention beyond the workspace plan, configure **Integrations -> Observability -> Log Streams** to an approved HTTPS or TLS-syslog destination. Store the ingestion token only in Render, exclude preview logs unless explicitly needed, verify JSON fields arrive intact, and create log-based terminal/ambiguity fallbacks without indexing source-like values. Render limits per-instance log volume, so metrics—not log counts—remain the primary alert source.

`/actuator/prometheus` remains JWT-protected. Before launch, select a collector capable of server-side bearer authentication and token rotation, import `infra/monitoring/prometheus-alerts.yml`, stream Render platform/PostgreSQL metrics to the same provider when supported, and test routes. Do not expose Actuator or deploy an unauthenticated proxy. Render's native notifications cover deploy/image-pull/service-health failures; the external provider covers queue, terminal workflow, publication ambiguity, AI failure, and database-readiness rules.

Paid Render PostgreSQL provides managed point-in-time recovery; verify the recovery window in the workspace and schedule/export logical backups when retention requirements exceed it. Follow `operations-runbook.md` for isolated PITR/logical restore and quarterly drill evidence. A backup that has never been restored is unverified.

Application rollback means redeploying a previously green commit/image definition. Database rollback does not mean editing or deleting a Flyway migration: restore to a new managed database when data/schema recovery is required, point a controlled backend deploy to it, verify readiness and smoke tests, then retire the damaged instance. Backward-compatible migrations are what make ordinary code rollback possible.

Rotate secrets one at a time with an observed redeploy: database credentials via Render's zero-downtime credential procedure; GitHub private key by adding a new App key, replacing the secret file, verifying, then revoking the old key; GitHub OAuth/OpenAI/Auth0 client secrets by updating the provider and Render value; `AUTH0_SECRET` with the expectation that existing sessions are invalidated; webhook secret through a coordinated GitHub/Render change and immediate signed-delivery test. Never print old or new values.

## Scaling and deferred boundaries

Scale vertically first when memory/CPU saturation, GC pressure, request latency, connection wait, or queue age demonstrate need. Add a second backend instance only after confirming database connection capacity and monitoring lease recovery; the current queue and token ownership already support it. Split workers from the API only when independent scaling or fault isolation is measured. Add PostgreSQL HA before the product's recovery objective requires automatic primary failover. Redis/Kafka remain unjustified until PostgreSQL claim throughput or latency is a measured bottleneck.

Primary cost drivers are the two always-on compute plans, PostgreSQL compute/storage/backup retention, extra instances, outbound provider/network use, Auth0, GitHub, and OpenAI consumption. M20 makes no price claim and creates no cloud resource.

Deferred: custom domains, organization ownership bootstrap, billing, separate workers, deployment-time migration identity, HA/read replicas, automated restore drills, a repository-selected monitoring vendor, tracing backend, container registry/signing/SBOM publication, autoscaling, WAF/rate-limiter infrastructure, and staging topology. Alert routing itself is a launch gate even though provider selection and credentials remain external.
