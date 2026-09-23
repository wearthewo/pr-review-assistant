# Production operations runbook

## Scope, authority, and severity

This runbook covers the initial Render topology defined by `render.yaml`. PostgreSQL state is authoritative; logs and metrics are diagnostic signals only. Never repair a workflow by editing job state, deleting idempotency keys, or replaying a provider request without first proving the applicable invariant in `reliability.md`.

- **page**: user-visible availability, data durability, publication ambiguity, or a sustained stuck queue. Acknowledge within 15 minutes.
- **ticket**: degraded review quality or recoverable workflow instability. Triage during the staffed operating window and escalate if impact grows.
- **info**: expected deploy, isolated retry, or recovered lease. Review during normal operations.

For every incident, record UTC start/end, affected services, safe internal identifiers, timeline, mitigations, and follow-up. Do not paste payloads, source, patches, prompts, model output, credentials, authorization headers, cookies, private keys, or provider response bodies into incident systems.

## First response

1. Declare an incident, assign an incident lead, and note the last known-good deploy.
2. Check Render service events, health, CPU/memory, restart history, PostgreSQL metrics, and JSON logs using the incident time window.
3. Correlate only with bounded `correlation_id`, `github_delivery_id`, `review_job_id`, or `publication_id` values.
4. Stop automatic deploys or disable the affected worker only when continued work could increase harm. Disabling a worker pauses claims; it does not delete durable work.
5. Prefer a previously green application rollback for a code regression. Never edit an applied Flyway migration.
6. Confirm recovery with readiness, queue age/depth, failure-rate metrics, and one synthetic tenant smoke flow before resolving.

## Backend unavailable

Signals: Render unhealthy-service notification, failed readiness, or `PrReviewAssistantBackendUnavailable`.

1. Check whether the frontend and database are independently healthy and whether a deploy or platform event coincides with the outage.
2. Inspect startup failure categories without copying configuration values. Common causes are missing secret files, invalid environment configuration, Flyway failure, database connectivity, or resource exhaustion.
3. If the current deploy is bad, redeploy the prior green commit. Health-gated Render deploys should keep the prior instance serving when startup never becomes ready.
4. If capacity is exhausted, scale vertically after capturing CPU, memory, connection, and latency evidence.
5. Do not bypass readiness, expose Actuator details, or disable authentication to restore service.

## Database or connectivity failure

Signals: readiness failure, Render PostgreSQL event, connection saturation, queue gauges disappearing/returning no data, or `PrReviewAssistantDatabaseUnavailable`.

1. Check Render database status, connection count/limit, disk, locks, and recent credential/network changes.
2. Verify that the backend still uses the generated internal URI and TLS-required adapter. Never print the URI or password.
3. Pause workers if repeated connection failures amplify load. Keep webhook acknowledgement fail-closed: a delivery is not accepted without durable persistence.
4. For corruption or accidental data loss, use the restore procedure below. For transient platform failure, allow managed recovery and verify Flyway history before resuming workers.

## Repeated review failures

Signals: `PrReviewAssistantRepeatedReviewFailures` or repeated `review_job_finished` events with `outcome=failed`.

1. Group by controlled `safe_error_code`; do not inspect or log source material.
2. Check GitHub operation, AI provider, quota, and queue metrics to locate the failing boundary.
3. Distinguish terminal target/configuration failures from a systemic regression. Isolated terminal jobs remain failed; do not reset them blindly.
4. Disable review polling if a systemic defect would fail more work, deploy a verified fix/rollback, then re-enable and observe queue age.

## Publication failure or ambiguity

Signals: `PrReviewAssistantPublicationFailure` or `publication_attempt_finished` with `terminal_failure`/`ambiguous`.

1. Treat ambiguity as page-worthy because GitHub may have accepted the side effect.
2. Leave the durable marker and publication record intact. The worker must reconcile before any repeated POST.
3. Check GitHub status/rate limits and controlled error codes. Never paste the review body or GitHub response body into logs or tickets.
4. Do not manually retry a POST. If automation is paused, resume only after reconciliation behavior is verified.

## Stuck or reclaimed jobs

Signals: actionable age over ten minutes, stale depth above zero, repeated lease recovery, or `PrReviewAssistantActionableJobStuck`.

1. Confirm worker enablement, scheduler activity, database locks, and instance restarts.
2. Compare oldest actionable age with the configured review/publication leases. A single recovery after deploy can be normal; repeated recovery indicates crashes, lease sizing, or provider latency problems.
3. Never clear `claimed_at`, tokens, or statuses manually during ordinary recovery. Expired leases and claim tokens are designed to self-heal and reject stale owners.
4. If a provider routinely exceeds the lease, investigate timeout/lease alignment before changing configuration and rerun concurrency tests.

## Abnormal AI/provider failures

Signals: `PrReviewAssistantAbnormalAiProviderFailures`, a rate-limit spike, or repeated controlled AI error types.

1. Check provider status, quota, configured model availability, request timeout, and failure category.
2. Do not log or retrieve prompts/responses for diagnosis. Use aggregate outcome/error-type metrics and safe job identifiers.
3. Pause review polling when failures are systemic. Existing durable checkpoints prevent repeat AI calls after a successful checkpoint.
4. `RESERVED` without a checkpoint may represent an ambiguous provider charge; do not force a second call.

## PostgreSQL backup and restore

### Production backup policy

Use a paid Render PostgreSQL plan with point-in-time recovery enabled. Confirm the workspace recovery window weekly. Create a logical export before every risky schema release and at least weekly for an independent portable recovery point; download it to encrypted, access-controlled storage because Render export download availability is limited. Record the export timestamp and SHA-256 digest without recording database credentials.

### Preferred recovery: point in time

1. Freeze deploys and pause workers if writes could worsen the incident.
2. In the database Recovery page, create a new recovery database at the last safe UTC timestamp. Never overwrite the original.
3. Validate the recovered database in isolation: PostgreSQL major version, Flyway history through the expected version, tenant/repository/job/publication/usage/checkpoint row counts, constraints, and a read-only dashboard query.
4. Point a controlled backend deployment at the recovery database using secret configuration; verify readiness and the smoke checklist.
5. Resume workers gradually, monitor queues/publication ambiguity, then retire the damaged database only after the recovery is accepted.

### Logical restore

Use PostgreSQL 18 client tools matching the server. Restore only into a new empty database. For a custom-format dump, run `pg_restore --exit-on-error --no-owner --no-privileges --dbname=<new database> <dump>`; for a Render directory export, follow Render's export-specific extraction and `pg_restore` command. Validate before switching application configuration. `--clean` is destructive and is prohibited against the active production database.

### Drill evidence

At least quarterly, restore a recent sanitized logical export into an isolated PostgreSQL 18 instance. Record tool/server versions, start/end UTC, dump digest, restore exit code, Flyway version, representative row counts, and acceptance. Delete drill credentials and data after evidence is retained. A dump is not considered verified until restored.

M21 executed the local mechanics drill on 2026-09-23 with two disposable `postgres:18.6` containers and synthetic data only. `pg_dump -Fc` produced SHA-256 `50A40F8C9E67E11CCB260E1983C53246C2460A34E67E4E5876B16CA9003D65AC`; `pg_restore --exit-on-error --no-owner --no-privileges` exited successfully into a new database; PostgreSQL reported `18.6`; and both synthetic tenant-marker rows matched after restore. The dump and containers were deleted. This proves local dump/restore mechanics, not Render PITR or production-data recovery; the first isolated Render export/PITR drill remains a launch gate.

## Communications and closure

Communicate user impact truthfully; do not claim data loss or recovery until validated. After recovery, preserve safe evidence, rotate any credential that may have been exposed, create regression tests for defects, and complete a blameless review. Any manual database change requires a reviewed migration or separately approved, recorded emergency procedure.
