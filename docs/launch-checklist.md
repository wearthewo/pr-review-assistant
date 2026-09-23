# Production launch checklist

Every item is a go-live gate unless explicitly marked post-launch. Record the operator, UTC time, evidence link, and result in the launch change record. Never include credentials in evidence.

## Build and supply chain

- [ ] Protected `main` requires Backend, Frontend, E2E, and Docker CI checks.
- [ ] Release commit is immutable, reviewed, and all dependency audits are accepted.
- [ ] Backend and frontend images build from the committed lockfiles/wrappers and run as non-root.
- [ ] `render.yaml` validates against the current Render Blueprint schema.
- [ ] No local environment file, PEM, token, database dump, test artifact, or build output is tracked.

## Render and identity

- [ ] Blueprint resources, region, paid plans, health paths, shutdown delays, and instance counts match `deployment.md`.
- [ ] Every `sync: false` value is supplied from an approved secret/config source.
- [ ] GitHub private key is an exact secret file, not YAML or a multiline environment value.
- [ ] Auth0 issuer/audience/JWKS, callback, logout, and web origins match final HTTPS origins.
- [ ] GitHub OAuth callback and webhook URL/secret match final HTTPS origins and least-privilege App permissions.
- [ ] Spring CORS remains disabled and browser-to-Spring calls are not introduced.

## Observability and alerts

- [ ] Render email and Slack notifications are enabled for deploy/image-pull/health failures on both services.
- [ ] JSON logs are visible, retention meets policy, and a TLS/HTTPS log stream is configured when longer retention is required.
- [ ] The selected collector authenticates to `/actuator/prometheus`; the endpoint is not made public to simplify scraping.
- [ ] Render platform/PostgreSQL metrics and application metrics reach the selected provider.
- [ ] `infra/monitoring/prometheus-alerts.yml` is imported or equivalently translated and each route has been test-fired.
- [ ] `page` and `ticket` routes have a named owner, escalation path, and quiet-hours policy.
- [ ] A negative log review confirms no source, patch, prompt, model response, JWT, GitHub/OpenAI token, private key, authorization header, cookie, or provider body.

## Database and recovery

- [ ] Paid PostgreSQL PITR window and logical-export availability are confirmed.
- [ ] A logical export has been downloaded to encrypted access-controlled storage and its digest recorded.
- [ ] A restore drill into an isolated PostgreSQL 18 instance passed within the last quarter.
- [ ] Restored Flyway history, tenant ownership, usage, checkpoint, job, and publication counts were validated.
- [ ] The rollback owner understands that applied Flyway migrations are never edited or deleted.

## Security and product smoke

- [ ] M17 abuse/security checks and secret scans pass.
- [ ] Health endpoints expose no details; unauthenticated Prometheus access is rejected.
- [ ] Authentication, unbound onboarding, ownership bootstrap, tenant isolation, repositories, reviews, and usage are smoke-tested with a dedicated test tenant.
- [ ] Signed webhook acceptance/redelivery, exact-revision review, checkpoint recovery, and publication reconciliation are smoke-tested without personal access tokens.
- [ ] Worker limits, leases, provider timeouts, monthly quota, and repository/config limits match the approved launch values.
- [ ] No unsupported marketing, billing, organization bootstrap, or administrative capability is enabled.

## Go/no-go and rollback

- [ ] Incident lead, deployment lead, database owner, and communications owner are identified.
- [ ] `docs/operations-runbook.md` is accessible to responders and alert links resolve to its sections.
- [ ] Previous green commit is identified and backward-compatible with the current schema.
- [ ] Launch window, observation period, abort criteria, and rollback authority are recorded.
- [ ] Final readiness, queues, provider failures, publication ambiguity, database connections, memory, CPU, and HTTP error rate are healthy.

No checked-in document proves these external steps occurred. Production launch remains prohibited until the change record contains evidence for every gate.
