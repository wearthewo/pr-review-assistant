# AGENTS.md

## Authority and scope

This file defines the engineering rules for every human contributor and automated coding agent working in this repository. It applies to the entire repository. A more specific `AGENTS.md` may add constraints for a subdirectory, but it may not weaken these rules or contradict an accepted Architecture Decision Record (ADR).

The project is a production-grade, multi-tenant GitHub App that reviews pull requests with deterministic analysis and AI-assisted analysis. Correctness, security, tenant isolation, explainability, and operational simplicity take priority over delivery speed or feature count.

## Required reading before making changes

Before modifying code, configuration, schemas, or architecture:

1. Read `README.md` and the relevant directory README.
2. Read `docs/architecture.md`, `docs/security.md`, and `docs/testing-strategy.md`.
3. Read every ADR in `docs/adr/`, plus any documentation relevant to the task.
4. Inspect the current implementation, tests, and repository state. Do not assume a planned component already exists.

If documentation and implementation disagree, stop and report the inconsistency. Do not silently choose one interpretation.

## Architectural governance

- Accepted ADRs are binding. Never silently change an architectural decision.
- A change that reverses or materially alters an ADR requires a new ADR that supersedes it, with context, consequences, and migration impact.
- Keep component boundaries described in `docs/architecture.md`. Cross-boundary dependencies must be explicit and justified.
- Never introduce infrastructure, a service, a datastore, a broker, a framework, or a dependency without a demonstrated current requirement.
- Prefer the simplest solution that satisfies verified requirements. Do not add speculative abstractions, generalized frameworks, extension points, or premature scaling mechanisms.
- Keep domain and policy logic independent of GitHub, OpenAI, persistence frameworks, HTTP frameworks, and other external providers. Integrations adapt external representations to internal concepts at the boundary.
- OpenAI and any future model provider must remain behind the internal AI provider abstraction. Provider-specific types must not leak into domain logic.
- PostgreSQL is the initial durable job queue. Do not introduce Kafka, Redis, or another queue until measured requirements justify a new ADR.
- OpenAPI is the contract for intentionally exposed HTTP APIs. Avoid accidental APIs between modules.

## Security and trust boundaries

- Treat repository contents, file names, diffs, patches, comments, commit messages, branch names, and all pull request metadata as untrusted attacker-controlled input.
- Treat model output as untrusted input. Validate, constrain, and rank it before any publication or downstream action.
- Preserve tenant isolation in every data model, query, cache key, job, log, metric, API call, and test. Tenant identity must be explicit; never infer it from user-controlled repository data.
- Verify GitHub webhook signatures using the exact raw request body before processing an event.
- Make webhook processing idempotent. Duplicate or replayed deliveries must not create duplicate effects.
- Make background jobs safe for retries. State transitions and external side effects must tolerate crashes, redelivery, and partial completion.
- Use least-privilege GitHub App permissions and short-lived installation tokens. Never expose installation tokens to clients or model providers.
- Never log secrets, webhook secrets, private keys, GitHub tokens, model credentials, authorization headers, or source code unnecessarily. Logs should contain identifiers and bounded metadata, not repository content.
- Never commit credentials or real secrets. Example environment files contain names and safe placeholders only.
- Do not send repository content to an AI provider unless the product flow explicitly permits it, the data is minimized, and tenant/security policies are enforced.
- Bound and validate payload sizes, file counts, diff sizes, token usage, API calls, concurrency, and execution time. Fail safely on malicious or oversized inputs.
- Follow `docs/security.md` and `docs/threat-model.md`. Add a security regression test whenever a vulnerability is fixed.

## Data, migrations, and compatibility

- Schema changes use versioned Flyway migrations.
- Never modify an existing migration that may have run in a production or shared environment. Add a new migration instead.
- Data ownership and tenant keys must be explicit in schemas and access paths.
- Define transaction boundaries deliberately. Queue claiming and job state changes must be safe under concurrent workers.
- Avoid destructive or irreversible migrations when an expand/migrate/contract sequence is practical.
- Maintain backward compatibility for externally consumed contracts unless a documented decision explicitly permits a breaking change.

## Engineering and dependency rules

- Use Java 21 and the repository Maven Wrapper for backend work once the backend is initialized.
- Use TypeScript with strict checks for frontend work once the frontend is initialized.
- Pin or constrain tool and dependency versions through the appropriate ecosystem files when those files exist.
- Keep dependencies minimal. Before adding one, verify that the standard library or an existing dependency cannot reasonably meet the need; document non-obvious choices.
- Do not introduce application functionality into documentation-only milestones.
- Do not commit generated build output, local environment files, editor state, credentials, or machine-specific configuration.
- Keep changes focused. Do not reformat, rename, or reorganize unrelated content.
- Comments should explain constraints and reasoning, not restate code.

## Testing and verification

- Add tests for every meaningful behavior, including failure paths, authorization boundaries, idempotency, retry behavior, and tenant isolation where applicable.
- Use the smallest test scope that proves the behavior, while adding integration or end-to-end coverage when a boundary is involved.
- Follow `docs/testing-strategy.md` for unit, integration, Testcontainers, contract, fixture, concurrency, end-to-end, and security regression testing.
- Tests must be deterministic. Do not depend on live GitHub or AI provider services in the default test suite.
- Run all relevant tests, static checks, and formatting checks before claiming completion. If a relevant check cannot run, report why and do not imply it passed.
- Documentation-only changes still require inspection for broken links, contradictory statements, accidental secrets, and scope violations.

## Working procedure

For each task:

1. Establish the requested scope and current milestone.
2. Inspect relevant documentation, ADRs, code, tests, and working-tree changes.
3. Identify security, tenancy, idempotency, retry, migration, and compatibility implications.
4. Implement the smallest coherent change.
5. Add or update tests and documentation that describe actual behavior.
6. Run relevant verification.
7. Review the diff for unrelated changes, secrets, unsafe logging, and architectural drift.

Do not overwrite or discard user changes. Ask for direction if existing work conflicts with the requested change.

## Completion report

Every completion report must state exactly:

- what files and behaviors changed;
- which tests and checks were run, including their results;
- any tests or checks not run and why;
- unresolved issues, risks, assumptions, or follow-up work;
- whether architecture or ADRs changed.

Never claim completion based only on code generation. Completion requires inspection and proportionate verification.
