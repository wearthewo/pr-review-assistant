# ADR 0002: Integrate through a GitHub App

- Status: Accepted
- Date: 2026-09-02

## Context

The service needs installation-scoped access to receive pull request events, retrieve permitted repository context, and publish reviews for multiple organizations. Asking users to supply personal access tokens (PATs) would couple service identity to individuals, encourage broad and long-lived credentials, and make lifecycle management and auditability weaker.

## Decision

Use a GitHub App as the product's GitHub integration and normal authentication model. Tenants install the app on selected organizations or repositories. The backend validates signed webhooks and generates short-lived installation access tokens with the minimum required permissions. PATs are not a supported substitute for normal product operation.

## Rationale

- Permissions can be declared, reviewed, and limited by installation.
- Installations map naturally to tenant authorization and repository selection.
- Short-lived installation tokens reduce exposure compared with long-lived user credentials.
- Webhook delivery and app actions have a first-class GitHub identity.
- Access is not disrupted merely because an employee leaves or rotates a personal token.

## Consequences

- The service must protect the app private key and webhook secret, validate raw-body signatures, and manage installation token generation securely.
- Tenant-to-installation mapping and installation suspension/deletion require explicit lifecycle handling.
- GitHub App permission changes require careful review and may require tenant approval.
- Local and automated tests need synthetic signatures and controlled GitHub API doubles.

## Alternatives considered

- User PATs: operationally simple for a prototype, but long-lived, user-bound, frequently over-scoped, difficult to rotate centrally, and unsuitable as the multi-tenant production baseline.
- GitHub OAuth App tokens: useful for user-delegated actions, but less appropriate for installation-scoped automated repository operations and webhook identity.

## Revisit when

A required capability cannot be provided through GitHub App permissions, or a distinct user-delegated workflow is introduced. Any exception needs a separate security review and ADR.
