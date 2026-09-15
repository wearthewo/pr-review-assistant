# Frontend

This directory contains the M13B Next.js App Router authentication foundation. Auth0 Universal Login provides one OIDC path, while the dashboard remains a server-rendered shell with no repository, review, usage, billing, or tenant-management feature.

## Toolchain

- Node.js 24 LTS (`.node-version` and `package.json` engines)
- npm 11.19.1
- Next.js 16.3.5
- React and React DOM 19.3.0
- TypeScript 5.9.3
- ESLint 9.39.5 with the Next.js 16.3.5 configuration

Use the committed `package-lock.json`; do not replace exact versions casually.

## Local development

From `frontend`:

```sh
npm ci
npm run dev
```

The site is then available at `http://localhost:3000`. Configure an Auth0 Regular Web Application and API using the root `.env.example`: `AUTH0_DOMAIN`, `AUTH0_CLIENT_ID`, `AUTH0_CLIENT_SECRET`, a 32-byte hex `AUTH0_SECRET`, `AUTH0_AUDIENCE`, and `APP_BASE_URL`. Register `/auth/callback` as the callback and `/` as the logout destination for the application origin. `BACKEND_BASE_URL` may use the loopback default only outside production; production requires HTTPS.

Run the full frontend checks with:

```sh
npm run lint
npm run typecheck
npm test
npm run build
npm audit --omit=dev
```

## Trust and security boundary

Pages are Server Components unless browser behavior requires otherwise; only the framework-required error boundaries are Client Components. Auth configuration, SDK session access, access tokens, backend origin, and the backend call live under `src/lib/server` with `server-only` guards. No bearer token is passed to a Client Component or browser storage, and the SDK's browser access-token route is disabled and blocked. Never add product or auth secrets to `NEXT_PUBLIC_*`.

`src/proxy.ts` creates a fresh nonce per application request and applies a strict production Content Security Policy without `unsafe-inline` or `unsafe-eval`. Development alone permits `unsafe-eval` for Next.js tooling. Baseline headers disable framing, MIME sniffing, sensitive referrers, and unused browser capabilities. Repository and GitHub-derived text must continue to use React's escaped text rendering; raw HTML and dynamic code execution are prohibited.

The browser is not a tenant authority. `/dashboard` asks the server-side Auth0 session for an access token, calls Spring server-to-server, and displays only Spring's bounded internal user/membership DTO. Missing authentication shows sign-in; an authenticated user without a membership sees a fail-closed onboarding state. There are no application mutation endpoints in M13B. Auth0 retains OAuth state/PKCE protections; encrypted cookies are HttpOnly, `SameSite=Lax`, and `Secure` in production. Future browser-cookie mutations must validate origin/fetch metadata and use a CSRF token where appropriate. Login return destinations are fixed to `/dashboard`, and token/profile helper routes are unavailable.

The frontend introduces no CORS path: the browser talks to Next.js, and Next.js talks to Spring. Tenant UUIDs in later routes may identify a requested resource but can never authorize it; Spring must resolve membership for the verified issuer/subject on every tenant-scoped operation.
