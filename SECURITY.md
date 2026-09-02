# Security Policy

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Report it privately to the repository owners through the private security reporting mechanism configured for the project. Include affected components, reproduction steps, impact, and any suggested mitigation. Do not include live credentials, private source code, or tenant data beyond the minimum necessary to demonstrate the issue.

No public support or response-time commitment is established while the project is pre-alpha. Maintainers should acknowledge, triage, remediate, add regression coverage, and coordinate disclosure proportionate to severity.

## Supported versions

There are no supported production versions in M0. Security expectations for future implementation are defined in [docs/security.md](docs/security.md), [docs/threat-model.md](docs/threat-model.md), and [AGENTS.md](AGENTS.md).

## Credential incidents

If a credential is exposed, treat it as compromised: revoke or rotate it immediately, review access logs and affected tenants, preserve evidence safely, and remove it from current and historical distribution where practical. Merely deleting a credential from the latest commit is not sufficient.
