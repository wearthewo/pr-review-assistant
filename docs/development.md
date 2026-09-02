# Development Guide

## Current state

The repository is at Milestone M0 and contains documentation and empty component boundaries only. There is no application to build, run, test, or deploy. Do not infer missing bootstrap files: Maven, Spring Boot, Next.js, Docker Compose, PostgreSQL services, Flyway migrations, OpenAPI specifications, and GitHub Actions workflows are intentionally absent.

## Planned prerequisites

Later milestones are expected to require Java 21, the repository Maven Wrapper, a supported Node.js toolchain, Docker with Compose support, and Git. Exact versions and setup commands must be added only when the corresponding project files exist. PostgreSQL 18 is the planned local and production-compatible database version.

## Working in the monorepo

- Start with [AGENTS.md](../AGENTS.md), then read architecture, security, testing, and all ADRs.
- Work only within the current milestone and keep changes focused.
- Use repository-owned wrappers and scripts once introduced; do not require undocumented global tools.
- Keep local secrets in ignored environment files or an approved secret mechanism. `.env.example` must contain safe placeholders only.
- Use synthetic data and controlled external-service doubles for routine development.
- Update documentation alongside behavior and record material architecture changes with a superseding ADR.

## Expected future local workflow

The concrete commands will be documented after each module is initialized. The intended shape is:

1. configure safe local environment values from the example file;
2. start only the local dependencies needed for the task;
3. run backend or frontend through repository-owned commands;
4. run focused tests during development and the relevant full checks before completion;
5. stop local services without deleting data unless deletion is explicitly intended.

This is guidance, not a claim that these commands or services exist in M0.

## Configuration principles

Configuration is introduced only for a demonstrated runtime need. Names should describe purpose, safe defaults should be explicit, required values should fail fast, and secrets must never be committed, logged, exposed to the frontend, or passed to AI models. Tenant-specific policy belongs in tenant-scoped data rather than process-global environment variables when dynamic administration is required.

## Database evolution

Flyway will own schema evolution once persistence is introduced. Add a new versioned migration for every schema change; never edit a migration that may have run outside a disposable local environment. Integration tests will apply migrations to PostgreSQL through Testcontainers.

## Troubleshooting and completion

When a command or test fails, preserve the original error, isolate whether the cause is code, configuration, dependency, or environment, and document any unmet prerequisite. Before handing off work, inspect the diff and report exact changes, verification performed, skipped checks, unresolved issues, and architectural impact.
