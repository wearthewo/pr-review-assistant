# Continuous Integration

## Scope

M19 adds build verification only. The GitHub Actions workflow in `.github/workflows/ci.yml` validates pull requests targeting `main`, pushes to `main`, and explicit manual runs. It does not deploy, publish packages or images, mutate infrastructure, use production credentials, or choose a production platform.

The stable required-check candidates are the `Backend`, `Frontend`, and `E2E` jobs. Repository branch protection should require all three before merge.

## Trigger and concurrency policy

- `pull_request` runs for changes targeting `main`.
- `push` runs for commits that reach `main`.
- `workflow_dispatch` permits an explicit diagnostic run.
- A newer run for the same pull request cancels the older run.
- Main-branch and manual runs use their unique run ID, so independent runs are never cancelled by another commit.

The workflow deliberately uses `pull_request`, not `pull_request_target`. Fork code therefore runs without repository secrets or a privileged base-repository context. Workflow permissions are restricted to read-only repository contents, and checkout does not persist credentials.

## Backend job

The Linux backend job installs Eclipse Temurin Java 21 with the official setup action and runs the repository-owned Maven Wrapper:

```sh
cd backend
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress dependency:tree
```

`clean verify` is the complete backend gate: 431 tests at M19 baseline, Flyway V1 through V10 from an empty PostgreSQL 18.6 database, the populated V9 to V10 migration path, and real Testcontainers concurrency/recovery coverage. GitHub-hosted Linux runners already provide Docker; the job does not start the development Compose stack or a duplicate PostgreSQL service.

The dependency-tree command is diagnostic and fails if Maven cannot resolve the declared graph. Maven repository and wrapper distribution caches are dependency caches only; compiled output is not cached.

## Frontend job

The Linux frontend job reads Node 24 from `frontend/.node-version`, activates the exact `npm@11.19.1` declared by `package.json`, and uses the committed lockfile:

```sh
cd frontend
npm ci
npm run lint
npm run typecheck
npm test
npm run build
npm audit --omit=dev
npm audit
npm ls --depth=0
```

The production build needs syntactically valid Auth0 and backend configuration because production startup validation is intentional. CI supplies fixed `.invalid` origins and non-secret placeholder values only to the build step. These values cannot authenticate to any service and must never be replaced with production credentials for pull-request verification.

The npm cache contains downloaded packages keyed from `package-lock.json`; `node_modules`, `.next`, and other build outputs are not cached or uploaded.

## Browser E2E job

The E2E job performs its own `npm ci`, installs only Chromium plus its Linux system dependencies, and runs:

```sh
npx --no-install playwright install --with-deps chromium
npm run test:e2e
```

The existing Playwright configuration builds and starts the real Next.js application plus its deterministic test fixture. It needs no real Auth0, GitHub, OpenAI, database, or product secret. Eighteen desktop/mobile Chromium tests form the M19 baseline.

On failure only, the job uploads the bounded `frontend/test-results` diagnostics for seven days. Successful runs upload no artifact. This is test evidence, not a deployable artifact.

## Versions and supply-chain policy

- Java: 21, installed by the immutable `actions/setup-java` v6.0.1 release using Eclipse Temurin.
- Maven: 3.9.16 through the checksum-pinned Maven Wrapper.
- Node.js: 24 from `frontend/.node-version`, installed by the immutable `actions/setup-node` v7.0.0 release.
- npm: 11.19.1, activated through Node's bundled Corepack before lockfile installation.
- GitHub-maintained actions: checkout v7.0.1, setup-java v6.0.1, setup-node v7.0.0, and failure-only upload-artifact v7.0.1. Workflow references use their full release commit SHAs rather than mutable tags.

Action release pins are reviewed and upgraded deliberately. Application dependencies remain pinned by Maven metadata and `package-lock.json`; CI never runs automatic force-upgrades. Both production-only and full npm audits are blocking checks. No third-party action, arbitrary install script, `curl | sh`, or secret-bearing step is present.

## Timeouts and failure behavior

Backend, frontend, and E2E jobs have 25-, 15-, and 20-minute limits respectively. Jobs fail closed on compilation, test, migration, lint, type, build, audit, browser, or dependency-resolution failure. There is no broad `continue-on-error` and no indiscriminate retry hiding flakiness.

For a local failure, reproduce the exact job command from the relevant directory with Java 21 or Node 24 active. For Testcontainers failures, verify Docker availability and PostgreSQL image access. For Playwright failures, inspect the locally generated `frontend/test-results`; CI exposes that directory only for failed E2E runs.

## Intentionally deferred

There are no application Dockerfiles, so M19 does not invent image builds. Artifact signing, SBOM generation, container publishing, deployment environments, migration rollout orchestration, smoke tests against deployed services, rollback, and M20 production infrastructure remain deferred.
