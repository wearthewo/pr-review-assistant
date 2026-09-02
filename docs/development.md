# Development Guide

## Current state

The repository is at Milestone M2. The backend is a minimal Java 21 and Spring Boot 4.1.1 application with PostgreSQL connectivity, Flyway, JPA schema validation, Actuator health, Testcontainers tests, and internal GitHub App authentication infrastructure. Local infrastructure contains PostgreSQL only. There is no webhook endpoint, review functionality, domain schema, frontend, OpenAPI specification, worker, AI integration, or CI/CD workflow.

## Prerequisites

- Java 21 JDK available through `JAVA_HOME` or `PATH`
- Docker with Docker Compose
- Git

The backend includes Maven Wrapper 3.3.4 pinned to Maven 3.9.16, so a global Maven installation is unnecessary. The local and test database image is PostgreSQL `18.6-bookworm`.

## Working in the monorepo

- Start with [AGENTS.md](../AGENTS.md), then read architecture, security, testing, and all ADRs.
- Work only within the current milestone and keep changes focused.
- Use repository-owned wrappers and scripts once introduced; do not require undocumented global tools.
- Keep local secrets in ignored environment files or an approved secret mechanism. `.env.example` must contain safe placeholders only.
- Use synthetic data and controlled external-service doubles for routine development.
- Update documentation alongside behavior and record material architecture changes with a superseding ADR.

## Local backend workflow

From the repository root:

```sh
cp .env.example .env
docker compose --env-file .env -f infra/docker-compose.yml up -d
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

On Windows PowerShell:

```powershell
Copy-Item .env.example .env
docker compose --env-file .env -f infra/docker-compose.yml up -d
Set-Location backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

The health endpoint is `http://localhost:8080/actuator/health`. The database health contributor is enabled, only the health endpoint is exposed over HTTP, and details are hidden.

Stop local infrastructure from the repository root without deleting the named volume:

```sh
docker compose --env-file .env -f infra/docker-compose.yml down
```

## Running tests

From `backend`:

```sh
./mvnw clean verify
```

On Windows, use `.\mvnw.cmd clean verify`. The test suite starts a pinned PostgreSQL Testcontainer, connects Spring to it, runs Flyway, validates the empty schema through Hibernate, and shuts the container down through the normal Testcontainers lifecycle. It does not require the Compose service to be running.

## Configuration principles

Common configuration is in `backend/src/main/resources/application.yml`. The local profile in `application-local.yml` imports the ignored root `.env` file. Non-local environments inject `DB_JDBC_URL`, `DB_USERNAME`, and `DB_PASSWORD` directly. Required values have no application defaults, so missing database configuration fails startup instead of selecting an embedded database. Secrets must never be committed, logged, exposed to the frontend, or passed to AI models.

GitHub App authentication additionally requires `GITHUB_APP_ID` and `GITHUB_PRIVATE_KEY_PATH`. `GITHUB_API_BASE_URL` defaults to GitHub's public API and is overridable for tests or compatible enterprise deployments. Installation ID is not global configuration; each internal operation supplies it because one App serves many installations.

For local development, download a private key from the GitHub App settings, store it outside this repository with access restricted to your account, and set `GITHUB_PRIVATE_KEY_PATH` in the ignored `.env` to its absolute path. GitHub-generated PKCS#1 PEM and PKCS#8 PEM are accepted. Do not paste PEM content into YAML or `.env`, commit it, print it, or pass it to tests. The repository's `*.pem` ignore rule is defense in depth, not permission to store keys here.

Automated tests use generated ephemeral RSA keys and a loopback HTTP server; they never contact GitHub. A real GitHub smoke test is optional only when an operator has explicitly configured an App and installation. Never request or substitute a personal access token.

## Database evolution

Flyway is enabled and is the sole schema migration mechanism. M1 has no migration because there are no business tables. Hibernate is configured with `ddl-auto: validate`; it cannot create or update the schema. Add a new versioned migration for every future schema change and never edit a migration that may have run outside a disposable local environment. Integration tests run Flyway against PostgreSQL through Testcontainers.

## Troubleshooting and completion

When a command or test fails, preserve the original error, isolate whether the cause is code, configuration, dependency, or environment, and document any unmet prerequisite. Before handing off work, inspect the diff and report exact changes, verification performed, skipped checks, unresolved issues, and architectural impact.
