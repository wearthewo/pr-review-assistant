# ADR 0001: Use a monorepo

- Status: Accepted
- Date: 2026-09-02

## Context

The product will include a backend API and worker, a web frontend, infrastructure definitions, contracts, and shared engineering documentation. These parts evolve around the same GitHub review workflow and security invariants. During early development, changes are likely to span contracts, implementation, tests, and documentation.

## Decision

Keep backend, frontend, infrastructure definitions, documentation, and repository-wide policy in one repository with explicit top-level boundaries. Each component retains its own build and dependency definitions when initialized; the monorepo does not require a universal build system or indiscriminate code sharing.

## Rationale

- Cross-component changes and contract reviews can be atomic.
- Security rules, ADRs, and developer guidance have one authoritative home.
- A single pull request can show the full effect of a product change.
- Early-stage operational overhead is lower than coordinating multiple repositories.
- Component boundaries can remain clear through directory ownership and dependency rules.

## Consequences

- CI must eventually select relevant work while retaining repository-wide checks.
- Broad repository access may expose multiple components, so access controls and secret separation still matter.
- Contributors must avoid accidental coupling merely because code is co-located.
- Build outputs and dependencies remain component-local.

## Alternatives considered

- Separate repositories per component: stronger physical separation, but greater coordination and contract-versioning overhead before independent release needs exist.
- Backend-only repository: simpler initially, but would fragment product documentation and future frontend/infrastructure changes.

## Revisit when

A component requires materially different access control, compliance, release independence, scale, or ownership that cannot be handled cleanly inside the monorepo.
