import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { NextRequest, NextResponse } from "next/server";

import HomePage from "@/app/page";
import { DashboardView } from "@/components/dashboard-view";
import { DisplayText } from "@/components/display-text";
import { resolveAuthEnvironment } from "@/lib/auth-environment-validation";
import { resolveBackendOrigin } from "@/lib/backend-origin-validation";
import { loadDashboardStateWith, requestDashboardSession, type AuthenticatedDashboardSession } from "@/lib/dashboard-session-core";
import { secureProxy } from "@/lib/proxy-core";
import { BASE_SECURITY_HEADERS, buildContentSecurityPolicy } from "@/lib/security-headers";

const frontendRoot = process.cwd();
const USER_ID = "9e02b328-f02a-4c77-a754-b3d88a7a6a92";
const TENANT_ID = "4bd9bba9-5c17-47f1-a5af-1666fd68d0c7";

test("root and unauthenticated dashboard render without protected data", () => {
  const home = renderToStaticMarkup(<HomePage />);
  const dashboard = renderToStaticMarkup(<DashboardView state={{ status: "unauthenticated" }} />);
  assert.match(home, /<main/);
  assert.match(home, /Signal for the changes that matter/);
  assert.match(dashboard, /Sign in to establish a protected application session/);
  assert.doesNotMatch(dashboard, /Authorized tenant|OWNER|MEMBER/);
});

test("untrusted display text is escaped and remains inert", () => {
  const rendered = renderToStaticMarkup(<DisplayText value={'<img src=x onerror="alert(1)"><script>steal()</script>'} />);
  assert.doesNotMatch(rendered, /<img|<script/);
  assert.match(rendered, /&lt;img/);
});

test("production CSP remains strict and nonce based", () => {
  const policy = buildContentSecurityPolicy("fixed-test-nonce", false);
  assert.match(policy, /script-src 'self' 'nonce-fixed-test-nonce' 'strict-dynamic'/);
  assert.match(policy, /object-src 'none'/);
  assert.match(policy, /frame-ancestors 'none'/);
  assert.doesNotMatch(policy, /'unsafe-inline'|'unsafe-eval'/);
});

test("development CSP permits tooling eval without permitting inline scripts", () => {
  const policy = buildContentSecurityPolicy("fixed-test-nonce", true);
  assert.match(policy, /'unsafe-eval'/);
  assert.doesNotMatch(policy, /'unsafe-inline'/);
});

test("baseline response headers preserve the M13A security policy", () => {
  assert.equal(BASE_SECURITY_HEADERS["X-Content-Type-Options"], "nosniff");
  assert.equal(BASE_SECURITY_HEADERS["X-Frame-Options"], "DENY");
  assert.equal(BASE_SECURITY_HEADERS["Referrer-Policy"], "strict-origin-when-cross-origin");
  assert.match(BASE_SECURITY_HEADERS["Permissions-Policy"], /camera=\(\)/);
});

test("authenticated proxy attaches nonce and security headers", async () => {
  let forwardedNonce: string | null = null;
  const response = await secureProxy(new NextRequest("https://frontend.example/dashboard"), async (request) => {
    forwardedNonce = request.headers.get("x-nonce");
    return NextResponse.next();
  });
  assert.ok(forwardedNonce);
  assert.match(response.headers.get("Content-Security-Policy") ?? "", /'nonce-[A-Za-z0-9+/=]+'/);
  assert.equal(response.headers.get("X-Content-Type-Options"), "nosniff");
});

test("token-mediating SDK routes are not exposed", async () => {
  let calls = 0;
  const authenticate = async () => { calls += 1; return NextResponse.next(); };
  for (const route of ["/auth/access-token", "/auth/profile", "/auth/unknown"]) {
    assert.equal((await secureProxy(new NextRequest(`https://frontend.example${route}`), authenticate)).status, 404);
  }
  assert.equal(calls, 0);
});

test("login return target is fixed and rejects open redirects", async () => {
  const authenticate = async () => NextResponse.next();
  for (const target of ["https://attacker.example", "//attacker.example", "/another-page"]) {
    const url = new URL("https://frontend.example/auth/login");
    url.searchParams.set("returnTo", target);
    assert.equal((await secureProxy(new NextRequest(url), authenticate)).status, 400);
  }
  const safe = new URL("https://frontend.example/auth/login");
  safe.searchParams.set("returnTo", "/dashboard");
  assert.equal((await secureProxy(new NextRequest(safe), authenticate)).status, 200);

  const unsafeLogout = new URL("https://frontend.example/auth/logout");
  unsafeLogout.searchParams.set("returnTo", "https://attacker.example");
  assert.equal((await secureProxy(new NextRequest(unsafeLogout), authenticate)).status, 400);
});

test("production backend origin is required, HTTPS, and credential free", () => {
  assert.throws(() => resolveBackendOrigin(undefined, "production"), /BACKEND_BASE_URL is required/);
  assert.throws(() => resolveBackendOrigin("http://backend.example", "production"), /HTTPS/);
  assert.throws(() => resolveBackendOrigin("https://user:pass@backend.example", "production"), /credentials/);
  assert.equal(resolveBackendOrigin("https://backend.example", "production"), "https://backend.example");
});

test("safe local backend origin exists only outside production", () => {
  assert.equal(resolveBackendOrigin(undefined, "development"), "http://127.0.0.1:8080");
  assert.equal(resolveBackendOrigin(undefined, "test"), "http://127.0.0.1:8080");
});

test("Auth0 production environment is strongly validated", () => {
  assert.throws(() => resolveAuthEnvironment({ NODE_ENV: "production" }), /AUTH0_DOMAIN is required/);
  const environment: NodeJS.ProcessEnv = {
    NODE_ENV: "production", AUTH0_DOMAIN: "tenant.example.auth0.com", AUTH0_CLIENT_ID: "client-id",
    AUTH0_CLIENT_SECRET: "client-secret", AUTH0_SECRET: "a".repeat(64),
    AUTH0_AUDIENCE: "https://api.example", APP_BASE_URL: "https://app.example",
  };
  const resolved = resolveAuthEnvironment(environment);
  assert.equal(resolved.production, true);
  assert.equal(resolved.appBaseUrl, "https://app.example");
  assert.throws(() => resolveAuthEnvironment({ ...environment, APP_BASE_URL: "http://app.example" }), /HTTPS/);
});

test("unauthenticated dashboard never requests a token or backend session", async () => {
  let requestedToken = false;
  let requestedBackend = false;
  const state = await loadDashboardStateWith({
    hasSession: async () => false,
    accessToken: async () => { requestedToken = true; return "not-issued"; },
  }, async () => { requestedBackend = true; throw new Error("must not run"); });
  assert.deepEqual(state, { status: "unauthenticated" });
  assert.equal(requestedToken, false);
  assert.equal(requestedBackend, false);
});

test("authenticated dashboard passes bearer token only through server callback", async () => {
  const secretToken = "server-only-access-token";
  const session: AuthenticatedDashboardSession = {
    status: "authenticated", applicationUserId: USER_ID, memberships: [], onboardingRequired: true,
  };
  let receivedToken = "";
  const state = await loadDashboardStateWith({
    hasSession: async () => true,
    accessToken: async () => secretToken,
  }, async (token) => { receivedToken = token; return session; });
  assert.equal(receivedToken, secretToken);
  const html = renderToStaticMarkup(<DashboardView state={state} />);
  assert.doesNotMatch(html, new RegExp(secretToken));
  assert.match(html, /not securely linked to a tenant/);
});

test("backend request uses bearer server side and strictly validates its DTO", async () => {
  const token = "never-render-this-token";
  let authorization = "";
  const fetchImplementation: typeof fetch = async (_input, init) => {
    authorization = new Headers(init?.headers).get("Authorization") ?? "";
    return new Response(JSON.stringify({ applicationUserId: USER_ID,
      memberships: [{ tenantId: TENANT_ID, role: "OWNER" }], onboardingRequired: false }), { status: 200 });
  };
  const session = await requestDashboardSession(new URL("https://backend.example/api/dashboard/session"), token, fetchImplementation);
  assert.equal(authorization, `Bearer ${token}`);
  assert.deepEqual(session.memberships, [{ tenantId: TENANT_ID, role: "OWNER" }]);
  assert.doesNotMatch(JSON.stringify(session), /never-render-this-token/);
});

test("backend failures never expose response bodies or bearer tokens", async () => {
  const fakeSecret = "provider-response-secret";
  const fetchImplementation: typeof fetch = async () => new Response(fakeSecret, { status: 401 });
  await assert.rejects(
    requestDashboardSession(new URL("https://backend.example/api/dashboard/session"), "bearer-secret", fetchImplementation),
    (error: Error) => !error.message.includes(fakeSecret) && !error.message.includes("bearer-secret"),
  );
});

test("membership-less state is explicit and never fabricates a tenant", () => {
  const html = renderToStaticMarkup(<DashboardView state={{ status: "authenticated",
    applicationUserId: USER_ID, memberships: [], onboardingRequired: true }} />);
  assert.match(html, /No tenant data is available/);
  assert.doesNotMatch(html, /Authorized tenant|OWNER|MEMBER/);
});

test("server-only boundaries and Auth0 session protections are configured", () => {
  const backendBoundary = readFileSync(join(frontendRoot, "src/lib/server/backend-api.ts"), "utf8");
  const authBoundary = readFileSync(join(frontendRoot, "src/lib/server/auth0.ts"), "utf8");
  assert.match(backendBoundary, /import "server-only"/);
  assert.match(authBoundary, /import "server-only"/);
  assert.match(authBoundary, /enableAccessTokenEndpoint: false/);
  assert.match(authBoundary, /signInReturnToPath: "\/dashboard"/);
  assert.match(authBoundary, /sameSite: "lax"/);
  assert.match(authBoundary, /secure: environment\.production/);
  assert.match(authBoundary, /user: \{ sub: session\.user\.sub \}/);
});

test("production source has no browser token storage, raw HTML, or public secrets", () => {
  const files = ["src/app/page.tsx", "src/app/dashboard/page.tsx", "src/app/error.tsx",
    "src/app/global-error.tsx", "src/components/display-text.tsx", "src/components/dashboard-view.tsx",
    "src/lib/server/auth0.ts", "src/lib/server/dashboard-session.ts", "src/lib/dashboard-session-core.ts"];
  const source = files.map((file) => readFileSync(join(frontendRoot, file), "utf8")).join("\n");
  const example = readFileSync(join(frontendRoot, "../.env.example"), "utf8");
  assert.doesNotMatch(source, /localStorage|sessionStorage|dangerouslySetInnerHTML|\beval\s*\(|new\s+Function\b/);
  assert.doesNotMatch(source, /NEXT_PUBLIC_/);
  assert.doesNotMatch(example, /NEXT_PUBLIC_.*(?:SECRET|TOKEN|KEY)/);
});
