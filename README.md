# Pull Request Review Assistant

> Project status: **pre-alpha**. Milestone M12 adds safe repository-owned review configuration at the exact reviewed revision.

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

The backend currently uses Java 21, Spring Boot 4.1.1, Maven Wrapper 3.3.4 with Maven 3.9.16, PostgreSQL 18, Flyway, JPA, Actuator, Testcontainers, a narrow GitHub App client, signed webhook ingestion, revision-specific jobs, bounded context retrieval, safe exact-revision repository configuration, an OpenAI Responses API adapter behind an internal structured-generation boundary, deterministic false-positive suppression, and durable GitHub review publication. The frontend remains uninitialized.

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

M9 produces at most five bounded, evidence-checked candidate findings from one provider call. M10 then applies a second, provider-independent trust boundary: confidence and severity gates, changed-code and location support, concrete/actionable evidence checks, conservative severity sanity, same-category duplicate suppression, stable ranking, and a three-candidate publication ceiling.

The initial operator policy is confidence 85, minimum severity MEDIUM, and at most three publication candidates. Silence is better than a weak comment; zero accepted findings completes without a GitHub write.

M11 renders accepted findings into one `COMMENT` review tied to the exact head SHA. It atomically stores a versioned, bounded publication payload and a separate PostgreSQL publication job before analysis completes. Publication retries never invoke retrieval, context building, suppression, or AI. An application-owned hidden marker and an `AMBIGUOUS` state reconcile uncertain POST outcomes before another write, preventing duplicate reviews. Publishing is disabled by default and requires the GitHub App `Pull requests: write` repository permission.

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
