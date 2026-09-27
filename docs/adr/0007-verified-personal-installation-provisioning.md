# ADR 0007: Provision personal installation ownership from verified GitHub authority

- Status: Accepted
- Date: 2026-09-28
- Supersedes: the existing-M14-installation prerequisite in ADR 0006

## Context

ADR 0006 required a personal GitHub App installation to exist in the M14 ownership tables before a human owner could bind it. M14 creates that mapping lazily only when a signed, reviewable pull-request webhook arrives. GitHub App installation and setup events are intentionally not ownership authorities in the dashboard flow. Consequently, a user who installed the App correctly could not connect the dashboard until later pull-request activity happened.

The GitHub App authorization-code flow already provides stronger evidence for the supported personal-account case: a freshly authenticated stable GitHub user ID and the installations of this App accessible to that user. The installation response also identifies the App and target account.

## Decision

After exchanging the one-time OAuth code, the backend continues to call `GET /user` and bounded pages of `GET /user/installations`. A candidate is eligible only when its numeric `app_id` matches configured `GITHUB_APP_ID`, its `account.id` matches the verified GitHub user ID, and both `account.type` and `target_type` are `User`.

When exactly one candidate exists, the backend transactionally resolves or creates the minimal M14 tenant and installation records and binds the authenticated application user as `OWNER`. A PostgreSQL transaction advisory lock on the GitHub installation ID plus existing uniqueness constraints makes concurrent callbacks converge. Existing ownership conflicts still fail closed.

The callback does not create repository rows. Repository ownership remains established from a signature-verified, reviewable pull-request webhook containing the installation and numeric repository IDs. Organization installations and multiple eligible personal installations remain unsupported.

## Consequences

- A new personal installation can connect immediately without waiting for pull-request activity.
- App-ID mismatch, account mismatch, organization targets, and ambiguous multiple candidates create no ownership state.
- The connection flow still creates no installation access token and persists no GitHub token.
- A connected tenant can initially contain zero repositories; repositories appear after authoritative signed reviewable webhook activity.
- Installation lifecycle reconciliation, uninstall handling, organization ownership, and repository synchronization remain deferred.
