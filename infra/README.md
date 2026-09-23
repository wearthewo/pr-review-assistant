# Infrastructure

This directory contains the local-development PostgreSQL service required by the backend. Production topology is defined separately by the root `render.yaml`; product rules do not belong in either definition.

## Local PostgreSQL

Copy the safe configuration template before starting the service:

```sh
cp .env.example .env
docker compose --env-file .env -f infra/docker-compose.yml up -d
docker compose --env-file .env -f infra/docker-compose.yml ps
```

The Compose project runs only `postgres:18.6-bookworm`, binds it to `127.0.0.1`, checks readiness with `pg_isready`, and stores data in the named `postgres-data` volume. Database name, port, username, and password come from the ignored root `.env` file; Compose has no default password.

Stop the service without deleting its data:

```sh
docker compose --env-file .env -f infra/docker-compose.yml down
```

Deleting the named volume is intentionally not part of the normal shutdown procedure. CI does not start this Compose project; backend tests own ephemeral PostgreSQL 18.6 containers through Testcontainers.

M20 production uses two Docker web services and managed PostgreSQL from the root Blueprint. Local Compose remains PostgreSQL-only and is not a production emulator. Redis, Kafka, object storage, and a separate worker service remain absent. See [deployment.md](../docs/deployment.md).

`monitoring/prometheus-alerts.yml` contains the M21 vendor-neutral application alert policy. It does not deploy a collector or monitoring service; see [observability.md](../docs/observability.md) and the [operations runbook](../docs/operations-runbook.md).
