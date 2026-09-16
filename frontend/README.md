# Frontend

This directory contains the authenticated dashboard foundation, the M13D1 server-only GitHub connection routes, the M13D2 repository page, and the M13E read-only review-history page. Auth0 remains the SaaS identity provider; GitHub authorization is temporary ownership proof. Repository mutation, review details, usage, billing, settings, and tenant management remain absent.

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

The browser is not a tenant authority. An unbound authenticated user submits a same-origin POST to `/github/connect`; Origin and Fetch Metadata are checked before Next.js asks the protected Spring start endpoint for a fixed GitHub authorization URL. `/github/callback` validates bounded code/state and performs the protected Spring callback server-to-server. Tokens and OAuth secrets never become Client Component props, HTML, URLs, or browser storage. `/github/setup` ignores the spoofable `installation_id` and only returns to fixed onboarding. All completion redirects are fixed dashboard states.

The frontend introduces no CORS path: the browser talks to Next.js, and Next.js talks to Spring. M13C may receive `?tenant=<uuid>` to identify a preferred workspace, but its Server Component selects only an exact member of the authenticated backend response. It chooses the lexicographically first authorized tenant when no preference exists and fails closed for unknown, malformed, or duplicated values. The query parameter never grants authorization and is not browser-persisted.

The desktop shell uses a sidebar and the mobile shell uses native `details`/`summary` navigation. Overview, Repositories, and Reviews are active; Usage and Settings remain visibly disabled. `/dashboard/repositories` displays the bounded M14 ownership read model. `/dashboard/reviews` is also a Server Component: it resolves the authenticated membership, retains the bearer token exclusively server-side, and validates the bounded Spring DTO before rendering. Entries show only numeric repository identity, PR number, abbreviated exact head SHA, timestamp, controlled state, and a publishable finding count when a durable publication proves it. Ordinary rendering makes no browser-to-Spring, GitHub, OpenAI, worker, or mutation call.

Review pagination uses ordinary GET navigation with a tenant identifier and bounded opaque cursor. The tenant is checked first against the authenticated membership DTO and Spring independently reauthorizes it; the cursor only identifies a keyset boundary. Timeout, unavailable, authorization, malformed/oversized-response, malformed-cursor, empty, unbound, and invalid-selection states use bounded messages without backend bodies or foreign-resource disclosure. Extra DTO fields are discarded and React text escaping remains the display boundary.
