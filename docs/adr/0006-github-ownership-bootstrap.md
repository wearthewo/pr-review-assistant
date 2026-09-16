# ADR 0006: Bootstrap dashboard ownership with verified GitHub user authority

- Status: Accepted
- Date: 2026-09-16

## Context

M14 can map a GitHub App installation to a tenant, while M13B can authenticate a human and authorize existing memberships. Neither proves that a newly authenticated SaaS user owns an existing installation. GitHub explicitly warns that an `installation_id` delivered to a setup URL can be spoofed. GitHub's `GET /user/installations` proves that a GitHub user can access an installation, but that access may arise from repository collaboration or organization membership and is not, by itself, organization-owner authority.

## Decision

Use the existing GitHub App's authorization-code flow as a second, temporary proof of GitHub human identity. The backend creates hashed, expiring, user-bound, single-use state and PKCE, exchanges the code server-side, calls `GET /user`, and retrieves all bounded pages of `GET /user/installations`. GitHub user tokens are used only during the callback and are never persisted.

M13D1 creates `OWNER` only for an installation whose target/account type is `User` and whose stable numeric account ID exactly equals the authenticated GitHub user's numeric ID. This is a narrowly defined personal-installation owner rule. Organization installations fail closed because installation accessibility does not prove organization ownership. Multiple matching installations also fail closed until explicit selection can be added safely.

The installation must already exist in M14. Its database mapping derives the tenant; no browser or setup parameter supplies authority. Binding uses the authenticated application user, a server-controlled `OWNER` role, a tenant-scoped transaction lock, membership uniqueness, and an existing-owner conflict check.

## Consequences

Personal-account installations can securely bootstrap the first dashboard owner. Organization onboarding and multi-installation selection remain unavailable rather than granting excessive authority. A future decision may add organization-owner/team verification, but it must not weaken this proof or trust browser-provided installation identifiers.
