# ADR 0003: Use PostgreSQL as the initial durable job queue

- Status: Accepted
- Date: 2026-09-02

## Context

Webhook requests must acknowledge quickly while reviews execute asynchronously and survive process failure. The product already requires PostgreSQL as durable storage. Initial throughput, latency, ordering, and fan-out requirements do not demonstrate a need for a dedicated broker.

## Decision

Store review jobs in PostgreSQL and have workers claim them through transactional queue operations. The design must support atomic enqueueing, safe concurrent claims, explicit job states, retry scheduling, recovery of abandoned work, tenant scoping, and idempotent external effects. Delivery is treated as at least once.

## Rationale

- Reuses the required system of record and its transaction guarantees.
- Allows webhook state and job creation to be committed atomically.
- Reduces infrastructure, operational burden, failure modes, and local setup.
- Provides sufficient durability and observability for expected early workloads.
- Preserves a path to measure real queue requirements before selecting specialized technology.

## Consequences

- Queue queries, indexes, locking, retention, and cleanup require deliberate design and concurrency tests on real PostgreSQL.
- Long-running work must not hold database transactions open.
- Workers need leases or equivalent recovery semantics, bounded retries, and terminal failure handling.
- Queue load shares database resources with product state and must be monitored and bounded.
- Exactly-once execution is not promised; consumers and publishers must be idempotent.

## Alternatives considered

- Kafka: strong event-streaming and replay capabilities, but unjustified operational and conceptual cost without demonstrated throughput, retention, or multi-consumer requirements.
- Redis-backed queues: useful for low-latency job processing, but introduce another data system and durability/consistency concerns before a measured need exists.
- In-memory execution: cannot provide durable recovery or safe horizontal processing.

## Revisit when

Measured database contention, throughput, latency, event-retention, fan-out, isolation, or independent scaling requirements cannot be met safely with PostgreSQL. A replacement requires a migration plan and a superseding ADR, not an ad hoc dependency.
