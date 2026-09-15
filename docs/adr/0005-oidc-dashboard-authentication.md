# ADR 0005: Use server-mediated Auth0 OIDC with backend JWT validation

- Status: Accepted
- Date: 2026-09-15

## Context

The dashboard needs human authentication and tenant authorization without implementing passwords, recovery, MFA, credential storage, or custom cryptography. The browser cannot become the tenant authority, and the Spring backend must not trust a Next.js assertion that a user logged in. No provider had previously been selected.

## Decision

Use Auth0 Universal Login as the first production-capable authentication provider through `@auth0/nextjs-auth0`. Next.js owns the OIDC authorization-code session and keeps it in an encrypted HttpOnly cookie. Access tokens remain server-side and are forwarded to Spring only with `Authorization: Bearer`.

Spring Security OAuth2 Resource Server independently validates Auth0 API JWTs. It accepts RS256 only and requires valid signature, expiry, not-before, issuer, and intended audience. Provider identity is adapted to the internal `(issuer, subject)` value before durable user provisioning. Tenant authorization then requires a database membership; neither the frontend nor provider-specific SDK types enter the tenant domain.

M13B creates no automatic first membership. Existing GitHub-derived tenants remain inaccessible to humans until a future server-verified GitHub ownership/installation authorization flow proves who may bind them.

## Rationale

- Auth0 supports standard OIDC/JWT semantics, Universal Login, authorization-code state/PKCE controls, and server-side Next.js sessions.
- The maintained Next.js SDK supports the current Next.js 16 and React 19 toolchain without introducing a general client state/data library.
- Spring's maintained Resource Server support avoids custom JWT parsing and cryptography.
- Independent backend validation preserves a clear trust boundary if the frontend is compromised.
- Issuer plus subject is stable and namespace-safe; email is unsuitable as authentication identity.
- Fail-closed membership bootstrap avoids inventing ownership from guessable GitHub or tenant metadata.

## Consequences

- Production requires an Auth0 Regular Web Application, an Auth0 API/audience, callback/logout configuration, frontend session secrets, and backend issuer/audience/JWK configuration.
- Dashboard availability depends on Auth0 and its key-discovery endpoint; tokens and sessions remain subject to provider revocation semantics.
- The frontend exposes only restricted login/logout/callback protocol routes; it exposes no browser token endpoint or generic backend proxy.
- Historical tenants have no dashboard owner until a later verified binding flow exists.
- Future provider support must adapt to the internal identity boundary and may require a superseding ADR if session or token semantics materially change.

## Alternatives considered

- Implement passwords and recovery internally: substantially larger credential and abuse surface without product justification.
- Trust only a Next.js session and send unsigned identity headers to Spring: makes frontend compromise equivalent to backend authentication bypass.
- Store access tokens in browser storage: exposes bearer credentials to XSS and client code.
- Use email/domain matching or submitted installation/tenant IDs for ownership: unstable and vulnerable to account takeover or IDOR.
- Add a provider-neutral frontend auth framework before a second provider exists: extra abstraction and dependency without a demonstrated need.

## Revisit when

A second provider, enterprise federation, verified GitHub tenant claiming, stronger session revocation, organization-specific policy, or direct browser-to-backend topology becomes a demonstrated requirement.
