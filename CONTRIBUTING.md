# Contributing

This repository is pre-alpha. Contributions must stay within the explicitly assigned milestone and follow [AGENTS.md](AGENTS.md), which is authoritative for repository-wide engineering rules.

## Before changing the repository

1. Read the root README, relevant directory README, architecture, security documentation, testing strategy, and all accepted ADRs.
2. Confirm that the change belongs to the current milestone.
3. Search for existing work and inspect the working tree before editing.
4. Identify effects on tenant isolation, trust boundaries, idempotency, retries, schemas, and external contracts.

If a proposed change conflicts with an ADR, write a superseding ADR for review. Do not hide an architectural change inside an implementation pull request.

## Change expectations

- Keep pull requests focused and explain the problem being solved.
- Prefer simple, direct implementations and minimal dependencies.
- Include tests for meaningful behavior and update documentation when behavior or decisions change.
- Never commit credentials, private keys, tokens, real webhook payloads containing sensitive data, or local environment files.
- Use synthetic and sanitized fixtures.
- Preserve tenant isolation and keep external-provider details outside domain logic.
- Use new Flyway migrations for schema evolution; never edit an applied production migration.

## Verification and pull request notes

Run the relevant test, lint, format, build, contract, and security checks for the areas changed. A pull request description must include:

- the exact scope of the change;
- the reason for the change;
- tests and checks run, with results;
- security and tenancy considerations;
- documentation or ADR changes;
- known limitations and deliberately deferred work.

Do not state that a change is complete when relevant checks were skipped without explaining why.
