# Infrastructure

This directory contains the local-development PostgreSQL service required by the M1 backend. It does not contain product rules or production deployment definitions.

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

Deleting the named volume is intentionally not part of the normal shutdown procedure. M19 CI does not start this Compose project; backend tests own ephemeral PostgreSQL 18.6 containers through Testcontainers. Redis, Kafka, object storage, mail services, proxies, frontend containers, deployment automation, cloud resources, and production topology remain deferred.
