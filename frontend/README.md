# Frontend

This directory contains the integrated authenticated dashboard: server-only GitHub connection routes plus read-only Overview, Repositories, Reviews, and Usage pages. M13G adds browser-level security/integration coverage. Auth0 remains the SaaS identity provider; GitHub authorization is temporary ownership proof. Repository mutation, review details, billing, settings, quota mutation, and tenant management remain absent.

## Toolchain

- Node.js 24 LTS (`.node-version` and `package.json` engines)
- npm 11.19.1
- Next.js 16.3.5
- React and React DOM 19.3.0
- TypeScript 5.9.3
- ESLint 9.39.5 with the Next.js 16.3.5 configuration
- Playwright 1.63.0 as a development-only Chromium E2E runner

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
npm run test:e2e
npm audit --omit=dev
```

M19 runs these checks as separate `Frontend` and `E2E` Linux jobs. Both use Node 24 from `.node-version`, activate the declared npm 11.19.1 through Corepack, and install exclusively with `npm ci`. CI production builds use fixed `.invalid` origins and non-secret placeholders only; browser tests use the existing deterministic Playwright environment. Failed E2E runs retain `test-results` for seven days, while successful runs upload nothing. See [the CI contract](../docs/ci-cd.md).

## Trust and security boundary

Pages are Server Components unless browser behavior requires otherwise; only the framework-required error boundaries are Client Components. Auth configuration, SDK session access, access tokens, backend origin, and the backend call live under `src/lib/server` with `server-only` guards. No bearer token is passed to a Client Component or browser storage, and the SDK's browser access-token route is disabled and blocked. Never add product or auth secrets to `NEXT_PUBLIC_*`.

`src/proxy.ts` creates a fresh nonce per application request and applies a strict production Content Security Policy without `unsafe-inline` or `unsafe-eval`. Development alone permits `unsafe-eval` for Next.js tooling. Baseline headers disable framing, MIME sniffing, sensitive referrers, and unused browser capabilities. Repository and GitHub-derived text must continue to use React's escaped text rendering; raw HTML and dynamic code execution are prohibited.

The browser is not a tenant authority. An unbound authenticated user submits a same-origin POST to `/github/connect`; Origin and Fetch Metadata are checked before Next.js asks the protected Spring start endpoint for a fixed GitHub authorization URL. `/github/callback` validates bounded code/state and performs the protected Spring callback server-to-server. Tokens and OAuth secrets never become Client Component props, HTML, URLs, or browser storage. `/github/setup` ignores the spoofable `installation_id` and only returns to fixed onboarding. All completion redirects are fixed dashboard states.

The frontend introduces no CORS path: the browser talks to Next.js, and Next.js talks to Spring. M13C may receive `?tenant=<uuid>` to identify a preferred workspace, but its Server Component selects only an exact member of the authenticated backend response. It chooses the lexicographically first authorized tenant when no preference exists and fails closed for unknown, malformed, or duplicated values. The query parameter never grants authorization and is not browser-persisted.

The desktop shell uses a sidebar and the mobile shell uses native `details`/`summary` navigation. Overview, Repositories, Reviews, and Usage are active; Settings remains visibly disabled. Navigation carries only the currently selected authorized workspace identifier so a legitimate multi-tenant selection remains consistent across pages; each page and Spring endpoint reauthorize it. The resource pages are Server Components: they resolve authenticated membership, retain the bearer token exclusively server-side, and validate bounded Spring DTOs before rendering. `/dashboard/usage` shows the backend-provided UTC period, limit, quota units used, and remaining units. It never calculates an accounting month from the browser clock or presents reserved usage as completed work. Ordinary rendering makes no browser-to-Spring, GitHub, OpenAI, worker, advisory-lock, or mutation call.

Review pagination uses ordinary GET navigation with a tenant identifier and bounded opaque cursor. The tenant is checked first against the authenticated membership DTO and Spring independently reauthorizes it; the cursor only identifies a keyset boundary. Timeout, unavailable, authorization, malformed/oversized-response, malformed-cursor, empty, unbound, and invalid-selection states use bounded messages without backend bodies or foreign-resource disclosure. Extra DTO fields are discarded and React text escaping remains the display boundary.

All four server-side dashboard clients use a shared streaming response reader. It checks declared lengths, counts each received byte chunk, cancels an over-limit stream, and decodes only a bounded complete UTF-8 body. Current ceilings are 64 KiB for session, 128 KiB for repositories, 192 KiB for reviews, and 32 KiB for usage.

Known dashboard session failures emit only the controlled `dashboard_session_load_failed` event and failure category. Unexpected Auth0, configuration, or framework exceptions are not relabeled as backend outages; they propagate to the Next.js production error boundary and server logs while the browser continues to receive a detail-free error page. Tokens, cookies, backend bodies, exception messages, and auth claims are never included in the controlled dashboard event.

`npm run test:e2e` builds and starts the real Next.js application on a fixed local port and runs Chromium at desktop and Pixel 7 viewports. Real unauthenticated routes, CSP, security headers, and CSRF behavior are exercised directly. Authenticated presentation states use a separate test-only rendering process built from the production components; it has no production route, flag, or auth bypass. Spring membership and tenant-resource enforcement are exercised by the backend PostgreSQL integration suite. A real Auth0/GitHub smoke test is intentionally deferred to the deployment/provider phase.

## Production image and runtime

`frontend/Dockerfile` builds with the committed lockfile on Node 24.21.0/npm 11.19.1 and runs Next.js standalone output as the unprivileged `node` user. Runtime configuration remains server-only and is injected by Render; build-time `.invalid` placeholders are non-secret and cannot authenticate anywhere. The container binds `0.0.0.0:${PORT}`. Production still requires HTTPS `APP_BASE_URL` and `BACKEND_BASE_URL`, so deployment does not weaken the existing Auth0/session or server-to-server trust boundary. See [production deployment](../docs/deployment.md).
