# Pull Request Review Assistant

> Project status: **pre-alpha**. Milestone M6 securely retrieves and bounds the exact pull request revision and changed-file data for durable review jobs; analysis and review publishing are not implemented.

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

The backend currently uses Java 21, Spring Boot 4.1.1, Maven Wrapper 3.3.4 with Maven 3.9.16, PostgreSQL 18, Flyway, JPA, Actuator, Testcontainers, a narrow GitHub App client, signed webhook ingestion, revision-specific jobs, and bounded pull request metadata/changed-file retrieval. The frontend and review analysis/publication pipeline remain uninitialized.

## Repository layout

```text
backend/     Spring Boot foundation; future API and review worker
frontend/    Future Next.js user interface
infra/       Local PostgreSQL Compose definition; future deployment definitions
docs/        Product, architecture, security, testing, and development documentation
docs/adr/    Accepted architecture decision records
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

After startup, `GET http://localhost:8080/actuator/health` is the operational endpoint. `POST http://localhost:8080/api/webhooks/github` is the GitHub boundary and authenticates requests with `X-Hub-Signature-256`. See [docs/development.md](docs/development.md) for configuration, test, and shutdown commands.

## Current milestone

M6 resolves the target repository through authenticated numeric-ID metadata, fetches the PR, verifies its number, repository ID, and exact expected head revision, then retrieves every changed-file page through derived same-client requests. A changed head returns `STALE` before file retrieval. Snapshots are immutable and in-memory; paths, counts, pages, response bodies, file count, and patch bytes are bounded, while missing/binary patches remain explicitly unavailable. Rate limits and transient failures retry through M4; stale, inaccessible, malformed, and oversized work terminates with safe codes. The worker remains disabled by default, and even a successful fetch terminates as analysis-not-implemented rather than claiming a completed review. Arbitrary repository context, analysis, AI, snapshot persistence, and publishing remain deferred.
