# Frontend

This directory contains the M13A Next.js App Router and TypeScript foundation. It provides a responsive product entry page, a clearly unauthenticated dashboard shell, route loading/error/not-found boundaries, strict lint/type checks, and nonce-based security headers. It intentionally provides no authentication, tenant data, dashboard business feature, API route, billing, or deployment configuration.

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

The site is then available at `http://localhost:3000`. The current pages make no backend call. `BACKEND_BASE_URL` defaults to `http://127.0.0.1:8080` only in development/test; production code that uses the future server boundary fails unless an HTTPS origin is configured.

Run the full frontend checks with:

```sh
npm run lint
npm run typecheck
npm test
npm run build
npm audit --omit=dev
```

## Trust and security boundary

Pages are Server Components unless browser behavior requires otherwise; only the framework-required error boundaries are Client Components. `src/lib/server/backend-api.ts` imports `server-only`, accepts no browser-provided backend origin, rejects cross-origin/path escape, and performs no request in M13A. Never add product secrets to `NEXT_PUBLIC_*`. GitHub credentials, OpenAI credentials, webhook secrets, installation tokens, and database credentials belong only to backend/runtime secret configuration.

`src/proxy.ts` creates a fresh nonce per application request and applies a strict production Content Security Policy without `unsafe-inline` or `unsafe-eval`. Development alone permits `unsafe-eval` for Next.js tooling. Baseline headers disable framing, MIME sniffing, sensitive referrers, and unused browser capabilities. Repository and GitHub-derived text must continue to use React's escaped text rendering; raw HTML and dynamic code execution are prohibited.

The browser is not a tenant authority. M13B must establish authentication and server-derived authorization before the dashboard can read product data. It must never trust a browser-supplied tenant ID.
