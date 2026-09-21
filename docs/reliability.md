# Workflow Reliability

## Scope and guarantee

The review pipeline uses at-least-once processing, PostgreSQL-enforced idempotency, leases with ownership tokens, a durable provider-neutral analysis checkpoint, and publication reconciliation. It does not claim distributed exactly-once execution. GitHub and the AI provider remain independent systems that cannot share a transaction with PostgreSQL.

The principal guarantee is: once a validated AI result is durably checkpointed, no retry invokes AI again for that review job. A checkpoint and the matching `REVIEW_ANALYSIS` transition from `RESERVED` to `CONSUMED` commit in the same PostgreSQL transaction.

## Production workflow

```text
signed GitHub webhook
  -> durable delivery + idempotent exact-revision review job (one transaction)
  -> review worker claim
  -> exact HEAD revision retrieval
  -> .reviewbot.yml from exact trusted BASE revision
  -> bounded repository context
  -> tenant quota reservation
  -> AI provider call
  -> validated provider-neutral candidate findings
  -> checkpoint + usage CONSUMED (one transaction)
  -> deterministic suppression
  -> publication + publication job (one transaction), or zero-publication completion
  -> publication worker claim
  -> GitHub Review API and marker reconciliation
  -> terminal publication and review states
```

Remote retrieval, AI, and GitHub publication execute outside database transactions. Transactions cover only short durable state changes.

## Durable boundaries and identities

| Boundary | Durable identity or guard | Recovery behavior |
| --- | --- | --- |
| Webhook acceptance | unique GitHub delivery ID | exact redelivery is successful and creates no duplicate work |
| Review target | installation ID, numeric repository ID, PR number, exact head SHA | different deliveries for one revision converge; a new SHA is new work |
| Review claim | lease expiry plus random claim token | expired work is reclaimable; stale owners cannot transition it |
| Usage | unique review job and usage type, tenant/month advisory lock | reservations are idempotent and concurrent quota decisions serialize |
| Analysis result | unique review job checkpoint with composite tenant ownership FK | retry reconstructs candidates and skips AI |
| Publication handoff | unique analysis job and deterministic publication key | handoff retries converge on one payload and one publication job |
| Publication claim | lease expiry plus random claim token | expired work is reclaimable; stale owners cannot transition it |
| GitHub side effect | deterministic hidden marker and `AMBIGUOUS` state | reconciliation precedes any repeated POST |

## Review state machine

`READY` jobs whose `next_attempt_at` is due may be claimed. Claiming atomically changes the job to `PROCESSING`, increments the attempt count, assigns a claim token, and establishes a lease. A current owner can transition to:

- `COMPLETED` after zero-publication processing or a durable publication handoff;
- `READY` with deterministic bounded backoff after a retryable failure and remaining attempts;
- `FAILED` after a terminal failure or exhausted attempts.

An expired `PROCESSING` job with attempts remaining may be reclaimed with a new token. An expired job with exhausted attempts becomes `FAILED`. `COMPLETED` and `FAILED` are never claimable. Completion, retry, and failure require the current token, so an old worker cannot overwrite a newer owner.

## Analysis checkpoint and accounting

`review_analysis_checkpoints` stores only the exact review target and bounded, provider-neutral candidate findings needed to rerun deterministic suppression. The finding payload is deterministic JSON with a 256 KiB database and application ceiling and a maximum of ten candidates. Reads reconstruct domain values through their normal validators. The table stores no prompt, source, patch, repository context, provider response envelope, credentials, token, or chain-of-thought.

The checkpoint is written only for a matching tenant-owned review job. Its composite foreign key binds `(review_job_id, tenant_id, tenant_repository_id)` to the job. Creation and `RESERVED -> CONSUMED` occur in one transaction. If either write fails, neither survives. Concurrent creation converges through the unique review-job constraint; the losing transaction accepts only an identical checkpoint paired with `CONSUMED` usage.

Before AI, the handler looks for a checkpoint. A valid checkpoint is reused without quota reservation or AI. Empty candidate lists are checkpointed too, allowing a crash before zero-publication completion to recover without another provider call. A historical `CONSUMED` event without a checkpoint cannot be reconstructed and fails closed as `USAGE_ANALYSIS_RESULT_UNAVAILABLE`; the application never guesses or reruns AI.

There is one unavoidable AI ambiguity window: the provider may complete or bill a request and the process may fail before it receives, validates, and checkpoints the response. PostgreSQL cannot atomically commit with the provider. The current provider abstraction does not prove that an exception or invalid provider output means no billable effect, so those reservations remain conservatively `RESERVED`; they are never changed to `CONSUMED` without a valid checkpoint, and automatic re-invocation is blocked. This is at-most-one automatic provider attempt for that uncertain job, not distributed exactly-once delivery.

## Publication state machine

The publication handoff atomically creates a bounded sanitized publication record and queue job. Publication jobs use `READY`, `PROCESSING`, `COMPLETED`, and `FAILED`, with the same lease/token ownership principle as review jobs. The publication record uses `PENDING`, `AMBIGUOUS`, `PUBLISHED`, and `FAILED`.

Before the remote POST the record becomes `AMBIGUOUS`. After a lost response, the worker searches bounded GitHub review pages for the application-owned deterministic marker. A found marker transitions to `PUBLISHED` without another POST. Not found permits the existing bounded retry; reconciliation failure preserves ambiguity and retries reconciliation rather than assuming absence. Publication retries operate only on the durable sanitized payload and never invoke retrieval, configuration, context construction, suppression, or AI.

## Reliability invariant matrix

| ID | Invariant | Primary enforcement and regression coverage |
| --- | --- | --- |
| R1 | Accepted webhook work is durable | webhook/job transaction and ingestion integration tests |
| R2 | Exact delivery redelivery has one durable effect | delivery uniqueness and duplicate-delivery tests |
| R3 | One exact PR revision has one review job | review-target unique key and concurrent ingestion tests |
| R4 | A new head SHA is separate work | review-target identity and synchronize tests |
| R5 | The worker never analyzes a different revision | exact-head retrieval and stale-revision tests |
| R6 | Trusted policy comes from exact BASE repository/SHA | configuration-loader base/fork tests |
| R7 | Only a current claim owner transitions a job | claim-token predicates and stale-worker tests |
| R8 | Crashed claims become available after lease expiry | PostgreSQL lease-recovery tests |
| R9 | Concurrent workers split work without duplicate ownership | `FOR UPDATE SKIP LOCKED` concurrency tests |
| R10 | Quota cannot be oversubscribed concurrently | tenant/month advisory-lock tests |
| R11 | One job has one logical usage event | usage unique key and idempotency tests |
| R12 | `CONSUMED` analysis has a durable checkpoint for new V10 work | atomic checkpoint transaction tests |
| R13 | Checkpoint recovery never invokes AI | handler crash/recovery tests |
| R14 | Zero candidates recover without AI or publication | zero-finding checkpoint and end-to-end tests |
| R15 | Durable handoff contains everything publication needs | publication persistence integration tests |
| R16 | Publication retry never invokes AI | separated worker boundary and retry tests |
| R17 | Ambiguous GitHub writes are reconciled before another POST | marker reconciliation tests |
| R18 | Tenant ownership survives every async boundary | composite FKs, tenant-scoped queries, isolation tests |
| R19 | Rollback leaves no partial durable state | webhook/job, publication handoff, and checkpoint rollback tests |
| R20 | Recovery signals cannot change authority | bounded logs/metrics and observability regression tests |

## Operational interpretation

`RESERVED` usage may indicate an in-flight or conservatively ambiguous AI attempt; it must not be labeled completed analysis. `CONSUMED` plus a checkpoint means deterministic post-analysis recovery is possible. `COMPLETED` without publication means only that no publication was durably handed off; the dashboard deliberately uses `COMPLETED_WITHOUT_PUBLICATION` rather than claiming a specific reason.

No recovery metric or log is authoritative. PostgreSQL state, tenant-scoped constraints, claim tokens, and idempotency keys remain the source of truth.
