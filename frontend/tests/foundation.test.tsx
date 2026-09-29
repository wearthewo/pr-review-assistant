import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { NextRequest, NextResponse } from "next/server";

import HomePage from "@/app/page";
import { DashboardRecoveryStarting, DashboardRecoveryUnavailable } from "@/components/dashboard-recovery";
import { DashboardView } from "@/components/dashboard-view";
import { DisplayText } from "@/components/display-text";
import { RepositoriesView, RepositoryList } from "@/components/repositories-view";
import { ReviewHistory, ReviewsView } from "@/components/reviews-view";
import { UsagePanel, UsageView } from "@/components/usage-view";
import { resolveAuthEnvironment } from "@/lib/auth-environment-validation";
import { resolveBackendOrigin } from "@/lib/backend-origin-validation";
import {
  DASHBOARD_RECOVERY_DELAYS_MS,
  DASHBOARD_RECOVERY_REQUEST_TIMEOUT_MS,
  dashboardRecoveryStatus,
  probeDashboardRecovery,
  startDashboardRecovery,
  type DashboardRecoveryProbeResult,
} from "@/lib/dashboard-recovery-core";
import {
  DashboardSessionError,
  loadDashboardStateWith,
  requestDashboardSession,
  type AuthenticatedDashboardSession,
} from "@/lib/dashboard-session-core";
import { selectAuthorizedTenant } from "@/lib/dashboard-selection";
import {
  DashboardRepositoryError,
  requestDashboardRepositories,
} from "@/lib/dashboard-repositories-core";
import {
  DashboardReviewError,
  requestDashboardReviews,
  type DashboardReview,
} from "@/lib/dashboard-reviews-core";
import {
  DashboardUsageError,
  requestDashboardUsage,
  usagePercentage,
  type ReviewAnalysisUsage,
} from "@/lib/dashboard-usage-core";
import {
  connectionMessage,
  dashboardPathForConnectionResult,
  isTrustedMutationRequest,
  parseGitHubCallback,
  redirectToGitHubAuthorization,
  validateCallbackValue,
  validateGitHubAuthorizationUrl,
} from "@/lib/github-connection-core";
import { secureProxy } from "@/lib/proxy-core";
import { BASE_SECURITY_HEADERS, buildContentSecurityPolicy } from "@/lib/security-headers";

const frontendRoot = process.cwd();
const USER_ID = "9e02b328-f02a-4c77-a754-b3d88a7a6a92";
const TENANT_ID = "4bd9bba9-5c17-47f1-a5af-1666fd68d0c7";
const SECOND_TENANT_ID = "113f2e2a-66b3-4fb8-9f49-212bac94d255";

test("root and unauthenticated dashboard render without protected data", () => {
  const home = renderToStaticMarkup(<HomePage />);
  const dashboard = renderToStaticMarkup(<DashboardView state={{ status: "unauthenticated" }} />);
  assert.match(home, /<main/);
  assert.match(home, /Signal for the changes that matter/);
  assert.match(dashboard, /Sign in to your dashboard/);
  assert.doesNotMatch(dashboard, /Workspace [0-9A-F]{8}|OWNER|MEMBER/);
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
  assert.match(policy, /form-action 'self'(?:;|$)/);
  assert.doesNotMatch(policy, /form-action[^;]*https:\/\//);
  assert.doesNotMatch(policy, /form-action[^;]*\*/);
  assert.doesNotMatch(policy, /'unsafe-inline'|'unsafe-eval'/);
});

test("dashboard CSP narrowly allows the validated GitHub authorization redirect", () => {
  const policy = buildContentSecurityPolicy("fixed-test-nonce", false, true);
  assert.match(policy, /form-action 'self' https:\/\/github\.com(?:;|$)/);
  assert.doesNotMatch(policy, /form-action[^;]*\*/);
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
  const policy = response.headers.get("Content-Security-Policy") ?? "";
  assert.match(policy, /'nonce-[A-Za-z0-9+/=]+'/);
  assert.match(policy, /form-action 'self' https:\/\/github\.com(?:;|$)/);
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
  assert.equal(resolveBackendOrigin("https://pr-review-assistant-backend.onrender.com", "production"),
    "https://pr-review-assistant-backend.onrender.com");
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
  assert.match(html, /no GitHub installation has been securely linked/);
});

test("backend request uses bearer server side and strictly validates its DTO", async () => {
  const token = "never-render-this-token";
  let authorization = "";
  let cacheMode: RequestCache | undefined;
  let requestCacheControl = "";
  const fetchImplementation: typeof fetch = async (_input, init) => {
    const headers = new Headers(init?.headers);
    authorization = headers.get("Authorization") ?? "";
    requestCacheControl = headers.get("Cache-Control") ?? "";
    cacheMode = init?.cache;
    return new Response(JSON.stringify({ applicationUserId: USER_ID,
      memberships: [{ tenantId: TENANT_ID, role: "OWNER" }], onboardingRequired: false }), { status: 200 });
  };
  const session = await requestDashboardSession(new URL("https://backend.example/api/dashboard/session"), token, fetchImplementation);
  assert.equal(authorization, `Bearer ${token}`);
  assert.equal(cacheMode, "no-store");
  assert.equal(requestCacheControl, "no-cache, no-store");
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

test("dashboard backend diagnostics distinguish bounded failure classes", async () => {
  const url = new URL("https://backend.example/api/dashboard/session");
  const cases: readonly [
    fetchImplementation: typeof fetch,
    expected: string,
  ][] = [
    [async () => { throw new TypeError("connect failed"); }, "NETWORK_ERROR"],
    [async () => { throw new DOMException("timed out", "TimeoutError"); }, "TIMEOUT"],
    [async () => new Response(null, { status: 401 }), "HTTP_401"],
    [async () => new Response(null, { status: 403 }), "HTTP_403"],
    [async () => new Response(null, { status: 422 }), "HTTP_4XX"],
    [async () => new Response(null, { status: 502 }), "HTTP_502"],
    [async () => new Response(null, { status: 503 }), "HTTP_503"],
    [async () => new Response(null, { status: 504 }), "HTTP_504"],
    [async () => new Response(null, { status: 500 }), "HTTP_5XX"],
    [async () => new Response("not-json", { status: 200 }), "MALFORMED_RESPONSE"],
  ];

  for (const [fetchImplementation, expected] of cases) {
    const diagnostics: string[] = [];
    await assert.rejects(requestDashboardSession(url, "server-only-token", fetchImplementation,
      (failure) => diagnostics.push(failure)));
    assert.deepEqual(diagnostics, [expected]);
  }
});

test("every transient dashboard retry performs a fresh uncached backend request", async () => {
  const url = new URL("https://api.pullsage.com/api/dashboard/session");
  const statuses = [503, 504, 200];
  const requests: { url: string; cache: RequestCache | undefined; cacheControl: string }[] = [];
  const fetchImplementation: typeof fetch = async (input, init) => {
    const status = statuses.shift();
    assert.notEqual(status, undefined);
    requests.push({
      url: input.toString(),
      cache: init?.cache,
      cacheControl: new Headers(init?.headers).get("Cache-Control") ?? "",
    });
    if (status !== 200) return new Response(null, { status });
    return new Response(JSON.stringify({ applicationUserId: USER_ID,
      memberships: [], onboardingRequired: true }), { status: 200 });
  };

  await assert.rejects(requestDashboardSession(url, "server-only-token", fetchImplementation),
    (error: DashboardSessionError) => error.kind === "BACKEND_STARTING");
  await assert.rejects(requestDashboardSession(url, "server-only-token", fetchImplementation),
    (error: DashboardSessionError) => error.kind === "BACKEND_STARTING");
  const session = await requestDashboardSession(url, "server-only-token", fetchImplementation);

  assert.equal(session.onboardingRequired, true);
  assert.equal(requests.length, 3);
  assert.deepEqual(requests.map(({ url: requestedUrl }) => requestedUrl), [url.href, url.href, url.href]);
  assert.deepEqual(requests.map(({ cache }) => cache), ["no-store", "no-store", "no-store"]);
  assert.deepEqual(requests.map(({ cacheControl }) => cacheControl), [
    "no-cache, no-store", "no-cache, no-store", "no-cache, no-store",
  ]);
});

test("dashboard session cold-start failures are recoverable but auth, other HTTP, and malformed responses are terminal", async () => {
  const url = new URL("https://backend.example/api/dashboard/session");
  const cases: readonly [fetchImplementation: typeof fetch, expected: string][] = [
    [async () => { throw new TypeError("connect failed"); }, "BACKEND_STARTING"],
    [async () => { throw new DOMException("timed out", "TimeoutError"); }, "BACKEND_STARTING"],
    [async () => new Response(null, { status: 502 }), "BACKEND_STARTING"],
    [async () => new Response(null, { status: 503 }), "BACKEND_STARTING"],
    [async () => new Response(null, { status: 504 }), "BACKEND_STARTING"],
    [async () => new Response(null, { status: 401 }), "AUTHORIZATION_FAILED"],
    [async () => new Response(null, { status: 403 }), "AUTHORIZATION_FAILED"],
    [async () => new Response(null, { status: 422 }), "BACKEND_UNAVAILABLE"],
    [async () => new Response(null, { status: 500 }), "BACKEND_UNAVAILABLE"],
    [async () => new Response("not-json", { status: 200 }), "BACKEND_RESPONSE_INVALID"],
  ];

  for (const [fetchImplementation, expected] of cases) {
    await assert.rejects(requestDashboardSession(url, "server-only-token", fetchImplementation),
      (error: DashboardSessionError) => error.kind === expected);
  }
});

test("dashboard recovery uses the bounded 150-second schedule and succeeds after transient failures", async () => {
  assert.equal(DASHBOARD_RECOVERY_DELAYS_MS.reduce((total, delay) => total + delay, 0), 150_000);
  const outcomes: DashboardRecoveryProbeResult[] = ["RETRYABLE", "RETRYABLE", "READY"];
  const delays: number[] = [];
  let ready = 0;
  let exhausted = 0;
  const recovery = startDashboardRecovery({
    delays: DASHBOARD_RECOVERY_DELAYS_MS,
    wait: async (delay) => { delays.push(delay); },
    probe: async () => outcomes.shift() ?? "RETRYABLE",
    onReady: () => { ready += 1; },
    onTerminal: () => assert.fail("transient recovery must not become terminal"),
    onExhausted: () => { exhausted += 1; },
  });
  await recovery.done;
  assert.deepEqual(delays, [5_000, 10_000, 15_000]);
  assert.equal(ready, 1);
  assert.equal(exhausted, 0);
});

test("dashboard recovery stops immediately for terminal responses and after success", async () => {
  for (const outcome of ["TERMINAL", "READY"] as const) {
    let probes = 0;
    let terminal = 0;
    let ready = 0;
    const recovery = startDashboardRecovery({
      delays: [1, 1, 1],
      wait: async () => undefined,
      probe: async () => { probes += 1; return outcome; },
      onReady: () => { ready += 1; },
      onTerminal: () => { terminal += 1; },
      onExhausted: () => assert.fail("completed recovery must not exhaust"),
    });
    await recovery.done;
    assert.equal(probes, 1);
    assert.equal(ready, outcome === "READY" ? 1 : 0);
    assert.equal(terminal, outcome === "TERMINAL" ? 1 : 0);
  }
});

test("dashboard recovery exhaustion exposes Try Again and a fresh controller can recover", async () => {
  let exhausted = 0;
  const first = startDashboardRecovery({
    delays: [1, 1],
    wait: async () => undefined,
    probe: async () => "RETRYABLE",
    onReady: () => assert.fail("exhausted sequence cannot be ready"),
    onTerminal: () => assert.fail("exhausted sequence cannot be terminal"),
    onExhausted: () => { exhausted += 1; },
  });
  await first.done;
  assert.equal(exhausted, 1);
  assert.match(renderToStaticMarkup(<DashboardRecoveryUnavailable onRetry={() => undefined} />), /Try again/);

  let ready = 0;
  const second = startDashboardRecovery({
    delays: [1],
    wait: async () => undefined,
    probe: async () => "READY",
    onReady: () => { ready += 1; },
    onTerminal: () => assert.fail("fresh sequence must recover"),
    onExhausted: () => assert.fail("fresh sequence must not exhaust"),
  });
  await second.done;
  assert.equal(ready, 1);
});

test("dashboard recovery cancellation aborts pending work without stale callbacks", async () => {
  const observed: { waitSignal: AbortSignal | null } = { waitSignal: null };
  let callbackCount = 0;
  const recovery = startDashboardRecovery({
    delays: [5_000],
    wait: (_delay, signal) => new Promise((resolve) => {
      observed.waitSignal = signal;
      signal.addEventListener("abort", () => resolve(), { once: true });
    }),
    probe: async () => { callbackCount += 100; return "READY"; },
    onReady: () => { callbackCount += 1; },
    onTerminal: () => { callbackCount += 1; },
    onExhausted: () => { callbackCount += 1; },
  });
  recovery.cancel();
  await recovery.done;
  assert.equal(observed.waitSignal?.aborted, true);
  assert.equal(callbackCount, 0);
});

test("same-origin recovery probe sends no bearer token and classifies only temporary availability responses", async () => {
  assert.equal(DASHBOARD_RECOVERY_REQUEST_TIMEOUT_MS, 8_000);
  for (const [status, expected] of [[204, "READY"], [502, "RETRYABLE"], [503, "RETRYABLE"],
    [504, "RETRYABLE"], [401, "TERMINAL"], [403, "TERMINAL"], [422, "TERMINAL"],
    [500, "TERMINAL"]] as const) {
    let authorization: string | null = "not-called";
    const result = await probeDashboardRecovery(async (input, init) => {
      assert.equal(input, "/api/dashboard/recovery");
      authorization = new Headers(init?.headers).get("Authorization");
      return new Response(null, { status });
    }, new AbortController().signal);
    assert.equal(result, expected);
    assert.equal(authorization, null);
  }
  assert.equal(await probeDashboardRecovery(async () => { throw new TypeError("offline"); },
    new AbortController().signal), "RETRYABLE");

  const timeout = AbortSignal.abort(new DOMException("timed out", "TimeoutError"));
  assert.equal(await probeDashboardRecovery(async (_input, init) => {
    assert.equal(init?.signal?.aborted, true);
    throw init?.signal?.reason;
  }, new AbortController().signal, timeout), "RETRYABLE");
});

test("recovery BFF status exposes no dashboard data and preserves terminal distinctions", () => {
  assert.equal(dashboardRecoveryStatus({ status: "authenticated", applicationUserId: USER_ID,
    memberships: [], onboardingRequired: true }), 204);
  assert.equal(dashboardRecoveryStatus({ status: "unauthenticated" }), 401);
  assert.equal(dashboardRecoveryStatus({ status: "error", kind: "BACKEND_STARTING" }), 503);
  assert.equal(dashboardRecoveryStatus({ status: "error", kind: "AUTHORIZATION_FAILED" }), 409);
  assert.equal(dashboardRecoveryStatus({ status: "error", kind: "BACKEND_RESPONSE_INVALID" }), 409);
});

test("startup UI is calm, accessible, and cannot start GitHub connection until recovery succeeds", () => {
  const starting = renderToStaticMarkup(<DashboardRecoveryStarting />);
  assert.match(starting, /Starting PullSage/);
  assert.match(starting, /aria-live="polite"/);
  assert.match(starting, /aria-busy="true"/);
  assert.match(starting, /keep this page open/);
  assert.doesNotMatch(starting, /Connect GitHub|Render|api\.pullsage\.com|percentage/);

  const recovered = renderToStaticMarkup(<DashboardView state={{ status: "authenticated",
    applicationUserId: USER_ID, memberships: [], onboardingRequired: true }} />);
  assert.match(recovered, /Connect GitHub/);
});

test("dashboard backend diagnostics never contain credentials or backend content", async () => {
  const token = "server-only-diagnostic-token";
  const backendBody = "sensitive-backend-response";
  const diagnostics: string[] = [];

  await assert.rejects(requestDashboardSession(
    new URL("https://backend.example/api/dashboard/session"), token,
    async () => new Response(backendBody, { status: 500 }),
    (failure) => diagnostics.push(JSON.stringify({ event: "dashboard_backend_request_failed", failure })),
  ));

  assert.deepEqual(diagnostics, [JSON.stringify({
    event: "dashboard_backend_request_failed", failure: "HTTP_5XX",
  })]);
  assert.doesNotMatch(diagnostics[0] ?? "", new RegExp(`${token}|${backendBody}`));
});

test("membership-less state is explicit and never fabricates a tenant", () => {
  const html = renderToStaticMarkup(<DashboardView state={{ status: "authenticated",
    applicationUserId: USER_ID, memberships: [], onboardingRequired: true }} />);
  assert.match(html, /Connect your GitHub workspace next/);
  assert.match(html, /action="\/github\/connect"/);
  assert.doesNotMatch(html, /action="https?:\/\//);
  assert.match(html, /GitHub authorization verifies eligible personal installations server-side/);
  assert.doesNotMatch(html, /Workspace [0-9A-F]{8}|OWNER|MEMBER/);
});

test("GitHub connection mutation requires same-origin browser proof", () => {
  const valid = new Request("https://app.example/github/connect", { method: "POST", headers: {
    Origin: "https://app.example", "Sec-Fetch-Site": "same-origin",
  } });
  const crossSite = new Request("https://app.example/github/connect", { method: "POST", headers: {
    Origin: "https://attacker.example", "Sec-Fetch-Site": "cross-site",
  } });
  assert.equal(isTrustedMutationRequest(valid, "https://app.example"), true);
  assert.equal(isTrustedMutationRequest(crossSite, "https://app.example"), false);
});

test("GitHub authorization and callback redirects are constrained", () => {
  const destination = validateGitHubAuthorizationUrl(
    "https://github.com/login/oauth/authorize?client_id=x&state=y", "https://github.com");
  assert.equal(destination.hostname, "github.com");
  const redirect = redirectToGitHubAuthorization(destination);
  assert.equal(redirect.status, 303);
  assert.equal(redirect.headers.get("location"), destination.href);
  assert.throws(() => validateGitHubAuthorizationUrl(
    "https://attacker.example/login/oauth/authorize", "https://github.com"), /not trusted/);
  assert.throws(() => validateGitHubAuthorizationUrl(
    "https://github.com.evil.example/login/oauth/authorize", "https://github.com"), /not trusted/);
  assert.equal(validateCallbackValue("bounded-code"), "bounded-code");
  assert.throws(() => validateCallbackValue("x".repeat(513)), /invalid/);
  assert.equal(dashboardPathForConnectionResult("CONNECTED"), "/dashboard?connection=connected");
  assert.equal(dashboardPathForConnectionResult("https://attacker.example"), "/dashboard?connection=failed");
});

test("connection results render bounded status without IDs or secrets", () => {
  const message = connectionMessage("no-installation");
  const html = renderToStaticMarkup(<DashboardView state={{ status: "authenticated",
    applicationUserId: USER_ID, memberships: [], onboardingRequired: true }} connectionMessage={message} />);
  assert.match(html, /No eligible installation found/);
  assert.doesNotMatch(html, /[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}|server-only-access-token/i);
});

test("GitHub callback rejects parameter pollution and provider errors", () => {
  assert.deepEqual(parseGitHubCallback(new URLSearchParams("code=one&state=two")),
    { code: "one", state: "two" });
  for (const query of [
    "code=one&code=two&state=three",
    "code=one&state=two&state=three",
    "code=one&state=two&error=access_denied",
    "code=one",
    "state=two",
  ]) {
    assert.throws(() => parseGitHubCallback(new URLSearchParams(query)), /invalid/);
  }
});

test("one authorized membership renders the tenant shell and backend role", () => {
  const html = renderToStaticMarkup(<DashboardView state={{ status: "authenticated",
    applicationUserId: USER_ID, memberships: [{ tenantId: TENANT_ID, role: "OWNER" }],
    onboardingRequired: false }} />);
  assert.match(html, /Workspace 4BD9BBA9/);
  assert.match(html, /OWNER/);
  assert.match(html, /Membership.*Verified by the backend/s);
});

test("multiple memberships select deterministically and support an authorized request", () => {
  const memberships = [
    { tenantId: TENANT_ID, role: "OWNER" as const },
    { tenantId: SECOND_TENANT_ID, role: "MEMBER" as const },
  ];
  const defaultSelection = selectAuthorizedTenant(memberships, null);
  assert.equal(defaultSelection.status, "selected");
  if (defaultSelection.status === "selected") {
    assert.equal(defaultSelection.membership.tenantId, SECOND_TENANT_ID);
  }
  const selected = selectAuthorizedTenant(memberships, TENANT_ID);
  assert.equal(selected.status, "selected");
  if (selected.status === "selected") {
    assert.equal(selected.membership.role, "OWNER");
  }
  const html = renderToStaticMarkup(<DashboardView state={{ status: "authenticated",
    applicationUserId: USER_ID, memberships, onboardingRequired: false }} requestedTenantId={TENANT_ID} />);
  assert.match(html, /name="tenant"/);
  assert.match(html, /Workspace 4BD9BBA9/);
  assert.match(html, /Workspace 113F2E2A/);
});

test("unauthorized requested tenant fails closed instead of falling back", () => {
  const unknown = "d14f6528-46b8-4927-92ac-aa79c13263ba";
  const state: AuthenticatedDashboardSession = { status: "authenticated", applicationUserId: USER_ID,
    memberships: [{ tenantId: TENANT_ID, role: "MEMBER" }], onboardingRequired: false };
  assert.deepEqual(selectAuthorizedTenant(state.memberships, unknown), { status: "invalid" });
  const html = renderToStaticMarkup(<DashboardView state={state} requestedTenantId={unknown} />);
  assert.match(html, /That workspace cannot be opened/);
  assert.doesNotMatch(html, /4BD9BBA9|OWNER|MEMBER/);
});

test("a tenant UUID alone never creates authorization", () => {
  const html = renderToStaticMarkup(<DashboardView state={{ status: "authenticated",
    applicationUserId: USER_ID, memberships: [], onboardingRequired: true }} requestedTenantId={TENANT_ID} />);
  assert.match(html, /Connect your GitHub workspace next/);
  assert.doesNotMatch(html, /4BD9BBA9|OWNER|MEMBER/);
});

test("overview renders no fabricated repository review or usage metrics", () => {
  const html = renderToStaticMarkup(<DashboardView state={{ status: "authenticated",
    applicationUserId: USER_ID, memberships: [{ tenantId: TENANT_ID, role: "MEMBER" }],
    onboardingRequired: false }} />);
  assert.doesNotMatch(html, /\d+\s+(?:repositories|reviews|findings|tokens)/i);
  assert.doesNotMatch(html, /suppression rate|monthly usage|AI spend/i);
  assert.match(html, /no fabricated repository, review, or usage totals/);
});

test("implemented dashboard navigation is functional while settings remains deferred", () => {
  const html = renderToStaticMarkup(<DashboardView state={{ status: "authenticated",
    applicationUserId: USER_ID, memberships: [{ tenantId: TENANT_ID, role: "MEMBER" }],
    onboardingRequired: false }} />);
  assert.match(html, /aria-current="page"[^>]*>Overview/);
  assert.match(html, new RegExp(`href="/dashboard/repositories\\?tenant=${TENANT_ID}"[^>]*>Repositories`));
  assert.match(html, new RegExp(`href="/dashboard/reviews\\?tenant=${TENANT_ID}"[^>]*>Reviews`));
  assert.match(html, new RegExp(`href="/dashboard/usage\\?tenant=${TENANT_ID}"[^>]*>Usage`));
  assert.doesNotMatch(html, /href="\/dashboard\/settings"/);
});

test("repositories route gates unauthenticated and unbound users without repository data", () => {
  const unauthenticated = renderToStaticMarkup(<RepositoriesView state={{ status: "gate",
    dashboard: { status: "unauthenticated" } }} />);
  const unbound = renderToStaticMarkup(<RepositoriesView state={{ status: "gate", dashboard: {
    status: "authenticated", applicationUserId: USER_ID, memberships: [], onboardingRequired: true,
  } }} />);
  assert.match(unauthenticated, /Sign in to your dashboard/);
  assert.match(unbound, /Connect your GitHub workspace next/);
  assert.doesNotMatch(`${unauthenticated}${unbound}`, /GitHub repository|#123456/);
});

test("authorized repository DTO renders truthfully with active navigation and no fake status", () => {
  const html = renderToStaticMarkup(<RepositoriesView state={{ status: "ready", selectedTenantId: TENANT_ID,
    dashboard: { status: "authenticated", applicationUserId: USER_ID,
      memberships: [{ tenantId: TENANT_ID, role: "MEMBER" }], onboardingRequired: false },
    repositories: { repositories: [{ repositoryId: 123456,
      connectedAt: "2026-09-16T12:00:00Z" }], truncated: false },
  }} />);
  assert.match(html, /aria-current="page"[^>]*>Repositories/);
  assert.match(html, /#123456/);
  assert.match(html, /2026-09-16 UTC/);
  assert.match(html, /MEMBER/);
  assert.doesNotMatch(html, /Enabled|Active|review count|findings|health|score|monthly usage|AI spend/i);
  assert.match(html, new RegExp(`href="/dashboard/reviews\\?tenant=${TENANT_ID}"[^>]*>Reviews`));
  assert.match(html, new RegExp(`href="/dashboard/usage\\?tenant=${TENANT_ID}"[^>]*>Usage`));
  assert.match(html, /aria-disabled="true">Settings/);
});

test("reviews route gates unauthenticated and unbound users without history data", () => {
  const unauthenticated = renderToStaticMarkup(<ReviewsView state={{ status: "gate",
    dashboard: { status: "unauthenticated" } }} />);
  const unbound = renderToStaticMarkup(<ReviewsView state={{ status: "gate", dashboard: {
    status: "authenticated", applicationUserId: USER_ID, memberships: [], onboardingRequired: true,
  } }} />);
  assert.match(unauthenticated, /Sign in to your dashboard/);
  assert.match(unbound, /Connect your GitHub workspace next/);
  assert.doesNotMatch(`${unauthenticated}${unbound}`, /Pull request #42|Published to GitHub/);
});

test("authorized review history renders exact revisions and controlled truthful states", () => {
  const reviews: DashboardReview[] = [
    review({ state: "PUBLISHED", publishableFindingCount: 2, pullRequestNumber: 42 }),
    review({ state: "PUBLICATION_PENDING", publishableFindingCount: 1,
      pullRequestNumber: 41, createdAt: "2026-09-16T11:00:00Z" }),
    review({ state: "PUBLICATION_UNCERTAIN", publishableFindingCount: 3,
      pullRequestNumber: 40, createdAt: "2026-09-16T10:00:00Z" }),
    review({ state: "PUBLICATION_FAILED", publishableFindingCount: 2,
      pullRequestNumber: 39, createdAt: "2026-09-16T09:00:00Z" }),
    review({ state: "COMPLETED_WITHOUT_PUBLICATION", pullRequestNumber: 38,
      createdAt: "2026-09-16T08:00:00Z" }),
    review({ state: "ANALYSIS_FAILED", pullRequestNumber: 37,
      createdAt: "2026-09-16T07:00:00Z" }),
    review({ state: "ANALYZING", pullRequestNumber: 36, createdAt: "2026-09-16T06:00:00Z" }),
    review({ state: "QUEUED", pullRequestNumber: 35, createdAt: "2026-09-16T05:00:00Z" }),
  ];
  const html = renderToStaticMarkup(<ReviewsView state={{ status: "ready", selectedTenantId: TENANT_ID,
    dashboard: { status: "authenticated", applicationUserId: USER_ID,
      memberships: [{ tenantId: TENANT_ID, role: "MEMBER" }], onboardingRequired: false },
    page: { reviews, hasMore: false, nextCursor: null },
  }} />);
  assert.match(html, /aria-current="page"[^>]*>Reviews/);
  assert.match(html, /Repository #123456/);
  assert.match(html, /Pull request #42/);
  assert.match(html, /000000000000/);
  for (const label of ["Published to GitHub", "Publication pending", "Publication outcome uncertain",
    "Publication failed", "Completed without a publication record", "Analysis failed",
    "Analysis in progress", "Queued"]) assert.match(html, new RegExp(label));
  assert.match(html, /2 publishable findings/);
  assert.doesNotMatch(html, /zero findings|review summary|Failure:|Provider:|Model:|token usage/i);
  assert.doesNotMatch(html, /PR title|author|avatar|repository name|branch/i);
  assert.ok(html.indexOf("Pull request #42") < html.indexOf("Pull request #41"));
  assert.match(html, new RegExp(`href="/dashboard/usage\\?tenant=${TENANT_ID}"[^>]*>Usage`));
  assert.match(html, /aria-disabled="true">Settings/);
});

test("usage route gates unauthenticated and unbound users without quota data", () => {
  const unauthenticated = renderToStaticMarkup(<UsageView state={{ status: "gate",
    dashboard: { status: "unauthenticated" } }} />);
  const unbound = renderToStaticMarkup(<UsageView state={{ status: "gate", dashboard: {
    status: "authenticated", applicationUserId: USER_ID, memberships: [], onboardingRequired: true,
  } }} />);
  assert.match(unauthenticated, /Sign in to your dashboard/);
  assert.match(unbound, /Connect your GitHub workspace next/);
  assert.doesNotMatch(`${unauthenticated}${unbound}`, /quota units used|remaining/);
});

test("authorized usage renders real quota semantics UTC period and active navigation", () => {
  const html = renderToStaticMarkup(<UsageView state={{ status: "ready", selectedTenantId: TENANT_ID,
    dashboard: { status: "authenticated", applicationUserId: USER_ID,
      memberships: [{ tenantId: TENANT_ID, role: "MEMBER" }], onboardingRequired: false },
    usage: { reviewAnalysis: usage({ used: 31, remaining: 19 }) },
  }} />);
  assert.match(html, /aria-current="page"[^>]*>Usage/);
  assert.match(html, /31<\/strong> of <strong>50/);
  assert.match(html, /review-analysis quota units used/);
  assert.match(html, /62% of the current quota/);
  assert.match(html, /19 remaining/);
  assert.match(html, /Sep 1, 2026.*Oct 1, 2026 UTC/);
  assert.match(html, /UTC, start inclusive and end exclusive/);
  assert.match(html, /reserved and consumed/i);
  assert.match(html, /Released reservations do not count/);
  assert.match(html, new RegExp(`href="/dashboard/repositories\\?tenant=${TENANT_ID}"[^>]*>Repositories`));
  assert.match(html, new RegExp(`href="/dashboard/reviews\\?tenant=${TENANT_ID}"[^>]*>Reviews`));
  assert.match(html, /aria-disabled="true">Settings/);
  assert.doesNotMatch(html, /completed reviews|Free plan|pricing|upgrade|Stripe|\$\d|tokens used/i);
});

test("usage progress safely represents zero exhausted and over-limit states", () => {
  const zero = renderToStaticMarkup(<UsagePanel usage={usage({ used: 0, remaining: 50 })} />);
  const exhausted = renderToStaticMarkup(<UsagePanel usage={usage({ used: 50, remaining: 0 })} />);
  const over = renderToStaticMarkup(<UsagePanel usage={usage({ used: 57, remaining: 0 })} />);
  assert.match(zero, /value="0" max="50"/);
  assert.match(zero, /0% of the current quota/);
  assert.match(exhausted, /value="50" max="50"/);
  assert.match(exhausted, /Quota exhausted/);
  assert.match(over, /57<\/strong> of <strong>50/);
  assert.match(over, /value="50" max="50"/);
  assert.match(over, /100% of the current quota/);
  assert.equal(usagePercentage(usage({ limit: 0, used: 0, remaining: 0 })), 0);
});

test("invalid usage selection and authorization failure fail closed", () => {
  const dashboard = { status: "authenticated" as const, applicationUserId: USER_ID,
    memberships: [{ tenantId: TENANT_ID, role: "OWNER" as const }], onboardingRequired: false };
  const invalid = renderToStaticMarkup(<UsageView state={{ status: "invalid-tenant", dashboard }} />);
  const denied = renderToStaticMarkup(<UsageView state={{ status: "usage-error", dashboard,
    selectedTenantId: TENANT_ID, kind: "AUTHORIZATION_FAILED" }} />);
  assert.match(invalid, /That workspace cannot be opened/);
  assert.match(denied, /could not be authorized/);
  assert.doesNotMatch(`${invalid}${denied}`, /quota units used|\d+ remaining/);
});

test("usage client keeps bearer token server-side and allowlists DTO fields", async () => {
  const secretToken = "usage-server-token";
  let authorization = "";
  const result = await requestDashboardUsage(new URL("https://backend.example/usage"), secretToken,
    async (_input, init) => {
      authorization = new Headers(init?.headers).get("Authorization") ?? "";
      return new Response(JSON.stringify({ reviewAnalysis: { ...usage({ used: 2, remaining: 48 }),
        provider: "secret-provider", tokenUsage: 123 }, tenantId: TENANT_ID }), { status: 200 });
    });
  assert.equal(authorization, `Bearer ${secretToken}`);
  assert.deepEqual(Object.keys(result.reviewAnalysis).sort(),
    ["periodStart", "periodEnd", "limit", "used", "remaining"].sort());
  const html = renderToStaticMarkup(<UsagePanel usage={result.reviewAnalysis} />);
  assert.doesNotMatch(html, /secret-provider|tokenUsage|usage-server-token|tenantId/);
});

test("usage client rejects malformed oversized and impossible quota responses", async () => {
  const url = new URL("https://backend.example/usage");
  const token = "never-expose-usage-token";
  const invalidBodies = [
    "not-json",
    JSON.stringify({ reviewAnalysis: usage({ used: -1, remaining: 50 }) }),
    JSON.stringify({ reviewAnalysis: usage({ limit: Number.MAX_SAFE_INTEGER + 1 }) }),
    JSON.stringify({ reviewAnalysis: usage({ periodStart: "not-a-date" }) }),
    JSON.stringify({ reviewAnalysis: usage({ periodEnd: "2026-09-01T00:00:00Z" }) }),
    JSON.stringify({ reviewAnalysis: usage({ used: 31, remaining: 20 }) }),
  ];
  for (const body of invalidBodies) {
    await assert.rejects(requestDashboardUsage(url, token,
      async () => new Response(body, { status: 200 })),
    (error: DashboardUsageError) => error.kind === "BACKEND_RESPONSE_INVALID"
      && !error.toString().includes(token));
  }
  await assert.rejects(requestDashboardUsage(url, token, async () => new Response("x", {
    status: 200, headers: { "Content-Length": String(33 * 1024) },
  })), (error: DashboardUsageError) => error.kind === "BACKEND_RESPONSE_INVALID");
  await assert.rejects(requestDashboardUsage(url, token,
    async () => new Response("x".repeat(33 * 1024), { status: 200 })),
  (error: DashboardUsageError) => error.kind === "BACKEND_RESPONSE_INVALID");
});

test("usage timeout unavailable and authorization responses are safely classified", async () => {
  const url = new URL("https://backend.example/usage");
  const token = "usage-secret";
  const cases: readonly [() => Promise<Response>, string][] = [
    [async () => { throw new DOMException("timed out", "TimeoutError"); }, "BACKEND_TIMEOUT"],
    [async () => new Response("failure", { status: 500 }), "BACKEND_UNAVAILABLE"],
    [async () => new Response("denied", { status: 404 }), "AUTHORIZATION_FAILED"],
  ];
  for (const [fetcher, kind] of cases) {
    await assert.rejects(requestDashboardUsage(url, token, fetcher),
      (error: DashboardUsageError) => error.kind === kind && !error.toString().includes(token));
  }
});

test("review empty state and keyset continuation are explicit", () => {
  const empty = renderToStaticMarkup(<ReviewHistory tenantId={TENANT_ID}
    page={{ reviews: [], hasMore: false, nextCursor: null }} />);
  const paged = renderToStaticMarkup(<ReviewHistory tenantId={TENANT_ID}
    page={{ reviews: [review({})], hasMore: true, nextCursor: "safe_cursor-1" }} />);
  assert.match(empty, /No review history is currently known/);
  assert.match(paged, /Older reviews/);
  assert.match(paged, /tenant=4bd9bba9-5c17-47f1-a5af-1666fd68d0c7/);
  assert.match(paged, /cursor=safe_cursor-1/);
});

test("invalid review selection cursor and authorization failure fail closed", () => {
  const dashboard = { status: "authenticated" as const, applicationUserId: USER_ID,
    memberships: [{ tenantId: TENANT_ID, role: "OWNER" as const }], onboardingRequired: false };
  const invalidTenant = renderToStaticMarkup(<ReviewsView state={{ status: "invalid-tenant", dashboard }} />);
  const invalidCursor = renderToStaticMarkup(<ReviewsView state={{ status: "invalid-cursor", dashboard,
    selectedTenantId: TENANT_ID }} />);
  const denied = renderToStaticMarkup(<ReviewsView state={{ status: "review-error", dashboard,
    selectedTenantId: TENANT_ID, kind: "AUTHORIZATION_FAILED" }} />);
  assert.match(invalidTenant, /That workspace cannot be opened/);
  assert.match(invalidCursor, /page cannot be opened/);
  assert.match(denied, /could not be authorized/);
  assert.doesNotMatch(`${invalidTenant}${invalidCursor}${denied}`, /Pull request #|Repository #/);
});

test("review client keeps bearer token server-side and allowlists DTO fields", async () => {
  const secretToken = "review-server-token";
  let authorization = "";
  const page = await requestDashboardReviews(new URL("https://backend.example/reviews"), secretToken,
    async (_input, init) => {
      authorization = new Headers(init?.headers).get("Authorization") ?? "";
      return new Response(JSON.stringify({ reviews: [{ ...review({}),
        payload: "<script>steal()</script>", lastErrorCode: "SECRET_FAILURE" }],
        hasMore: false, nextCursor: null }), { status: 200 });
    });
  assert.equal(authorization, `Bearer ${secretToken}`);
  assert.deepEqual(Object.keys(page.reviews[0]).sort(), ["createdAt", "headSha", "publishableFindingCount",
    "pullRequestNumber", "repositoryId", "state", "updatedAt"].sort());
  const html = renderToStaticMarkup(<ReviewHistory tenantId={TENANT_ID} page={page} />);
  assert.doesNotMatch(html, /script|steal|SECRET_FAILURE|review-server-token/i);
});

test("review client rejects malformed oversized out-of-order and invalid-count responses", async () => {
  const url = new URL("https://backend.example/reviews");
  const token = "never-expose-review-token";
  const invalidBodies = [
    "not-json",
    JSON.stringify({ reviews: [review({ state: "PUBLISHED", publishableFindingCount: null })],
      hasMore: false, nextCursor: null }),
    JSON.stringify({ reviews: [review({ createdAt: "2026-09-16T10:00:00Z" }),
      review({ pullRequestNumber: 2, createdAt: "2026-09-16T11:00:00Z" })], hasMore: false, nextCursor: null }),
    JSON.stringify({ reviews: [], hasMore: true, nextCursor: "bad cursor" }),
  ];
  for (const body of invalidBodies) {
    await assert.rejects(requestDashboardReviews(url, token,
      async () => new Response(body, { status: 200 })),
    (error: DashboardReviewError) => error.kind === "BACKEND_RESPONSE_INVALID"
      && !error.toString().includes(token));
  }
  await assert.rejects(requestDashboardReviews(url, token, async () => new Response("x", {
    status: 200, headers: { "Content-Length": String(193 * 1024) },
  })), (error: DashboardReviewError) => error.kind === "BACKEND_RESPONSE_INVALID");
});

test("review timeout unavailable auth and invalid cursor responses are safely classified", async () => {
  const url = new URL("https://backend.example/reviews");
  const token = "review-secret";
  const cases: readonly [() => Promise<Response>, string][] = [
    [async () => { throw new DOMException("timed out", "TimeoutError"); }, "BACKEND_TIMEOUT"],
    [async () => new Response("failure", { status: 500 }), "BACKEND_UNAVAILABLE"],
    [async () => new Response("denied", { status: 404 }), "AUTHORIZATION_FAILED"],
    [async () => new Response("invalid", { status: 400 }), "INVALID_CURSOR"],
  ];
  for (const [fetcher, kind] of cases) {
    await assert.rejects(requestDashboardReviews(url, token, fetcher),
      (error: DashboardReviewError) => error.kind === kind && !error.toString().includes(token));
  }
});

test("repository empty and bounded states are explicit", () => {
  const empty = renderToStaticMarkup(<RepositoryList tenantId={TENANT_ID}
    page={{ repositories: [], truncated: false }} />);
  const bounded = renderToStaticMarkup(<RepositoryList tenantId={TENANT_ID}
    page={{ repositories: [{ repositoryId: 1, connectedAt: "2026-09-16T12:00:00Z" }], truncated: true }} />);
  assert.match(empty, /No repositories are currently known/);
  assert.match(empty, /does not query GitHub or invent sample data/);
  assert.match(bounded, /Showing the first 100 repositories/);
});

test("invalid tenant selection and repository authorization failures fail closed", () => {
  const dashboard = { status: "authenticated" as const, applicationUserId: USER_ID,
    memberships: [{ tenantId: TENANT_ID, role: "OWNER" as const }], onboardingRequired: false };
  const invalid = renderToStaticMarkup(<RepositoriesView state={{ status: "invalid-tenant", dashboard }} />);
  const denied = renderToStaticMarkup(<RepositoriesView state={{ status: "repository-error", dashboard,
    selectedTenantId: TENANT_ID, kind: "AUTHORIZATION_FAILED" }} />);
  assert.match(invalid, /That workspace cannot be opened/);
  assert.match(denied, /could not be authorized/);
  assert.doesNotMatch(`${invalid}${denied}`, /#\d{3,}/);
});

test("repository client validates bounds, ordering and keeps the bearer token server-side", async () => {
  const secretToken = "repository-server-token";
  let authorization = "";
  const result = await requestDashboardRepositories(new URL("https://backend.example/repositories"), secretToken,
    async (_input, init) => {
      authorization = new Headers(init?.headers).get("Authorization") ?? "";
      return new Response(JSON.stringify({ repositories: [
        { repositoryId: 10, connectedAt: "2026-09-16T12:00:00Z", name: "<script>steal()</script>" },
      ], truncated: false }), { status: 200 });
    });
  assert.equal(authorization, `Bearer ${secretToken}`);
  assert.deepEqual(result.repositories, [{ repositoryId: 10, connectedAt: "2026-09-16T12:00:00Z" }]);
  const html = renderToStaticMarkup(<RepositoryList tenantId={TENANT_ID} page={result} />);
  assert.doesNotMatch(html, /script|steal|repository-server-token/i);

  await assert.rejects(requestDashboardRepositories(new URL("https://backend.example/repositories"), secretToken,
    async () => new Response(JSON.stringify({ repositories: [
      { repositoryId: 2, connectedAt: "2026-09-16T12:00:00Z" },
      { repositoryId: 1, connectedAt: "2026-09-16T12:00:00Z" },
    ], truncated: false }), { status: 200 })),
  (error: DashboardRepositoryError) => error.kind === "BACKEND_RESPONSE_INVALID" && !error.toString().includes(secretToken));
});

test("repository timeout unavailable malformed and oversized responses are safely classified", async () => {
  const url = new URL("https://backend.example/repositories");
  const token = "never-expose-repository-token";
  await assert.rejects(requestDashboardRepositories(url, token, async () => {
    throw new DOMException("timed out", "TimeoutError");
  }), (error: DashboardRepositoryError) => error.kind === "BACKEND_TIMEOUT");
  await assert.rejects(requestDashboardRepositories(url, token, async () => new Response("failure", { status: 500 })),
    (error: DashboardRepositoryError) => error.kind === "BACKEND_UNAVAILABLE");
  await assert.rejects(requestDashboardRepositories(url, token, async () => new Response("not-json", { status: 200 })),
    (error: DashboardRepositoryError) => error.kind === "BACKEND_RESPONSE_INVALID");
  await assert.rejects(requestDashboardRepositories(url, token, async () => new Response("x", {
    status: 200, headers: { "Content-Length": String(129 * 1024) },
  })), (error: DashboardRepositoryError) => error.kind === "BACKEND_RESPONSE_INVALID"
    && !error.toString().includes(token));
});

test("backend timeout, malformed response, and auth failure become bounded UI states", async () => {
  const authenticated = { hasSession: async () => true, accessToken: async () => "server-token" };
  const timeout = await loadDashboardStateWith(authenticated, async () => {
    throw new DashboardSessionError("BACKEND_TIMEOUT");
  });
  const malformed = await loadDashboardStateWith(authenticated, async () => {
    throw new DashboardSessionError("BACKEND_RESPONSE_INVALID");
  });
  const authorization = await loadDashboardStateWith(authenticated, async () => {
    throw new DashboardSessionError("AUTHORIZATION_FAILED");
  });
  const unavailable = await loadDashboardStateWith(authenticated, async () => {
    throw new DashboardSessionError("BACKEND_UNAVAILABLE");
  });
  assert.match(renderToStaticMarkup(<DashboardView state={timeout} />), /took too long/);
  assert.match(renderToStaticMarkup(<DashboardView state={malformed} />), /invalid response/);
  assert.match(renderToStaticMarkup(<DashboardView state={authorization} />), /could not be authorized/);
  assert.match(renderToStaticMarkup(<DashboardView state={unavailable} />), /temporarily unavailable/);
});

test("unexpected authentication and framework failures are not mislabeled as backend outages", async () => {
  const tokenFailure = new Error("synthetic Auth0 failure");
  await assert.rejects(loadDashboardStateWith({
    hasSession: async () => true,
    accessToken: async () => { throw tokenFailure; },
  }, async () => { throw new Error("must not run"); }), (error) => error === tokenFailure);

  const frameworkFailure = new Error("synthetic framework failure");
  await assert.rejects(loadDashboardStateWith({
    hasSession: async () => true,
    accessToken: async () => "server-token",
  }, async () => { throw frameworkFailure; }), (error) => error === frameworkFailure);
});

test("dashboard retry is ordinary navigation and never a Server Action", () => {
  const html = renderToStaticMarkup(<DashboardView state={{
    status: "error", kind: "BACKEND_UNAVAILABLE",
  }} />);
  assert.match(html, /href="\/dashboard"[^>]*>Try again<\/a>/);
  assert.doesNotMatch(html, /<form|\$ACTION_|formAction/);

  const dashboardSource = readFileSync(
    join(frontendRoot, "src/components/dashboard-view.tsx"), "utf8");
  assert.doesNotMatch(dashboardSource, /use server|formAction/);
});

test("network timeout classification does not expose request credentials", async () => {
  const secret = "server-timeout-token";
  await assert.rejects(
    requestDashboardSession(new URL("https://backend.example/api/dashboard/session"), secret,
      async () => { throw new DOMException("operation stopped", "TimeoutError"); }),
    (error: DashboardSessionError) => error.kind === "BACKEND_STARTING" && !error.toString().includes(secret),
  );
});

test("malicious requested tenant text remains inert and undisclosed", () => {
  const malicious = '<script>steal("token")</script>';
  const html = renderToStaticMarkup(<DashboardView state={{ status: "authenticated",
    applicationUserId: USER_ID, memberships: [{ tenantId: TENANT_ID, role: "MEMBER" }],
    onboardingRequired: false }} requestedTenantId={malicious} />);
  assert.doesNotMatch(html, /<script>|steal\(/);
  assert.match(html, /That workspace cannot be opened/);
});

test("desktop and mobile dashboard navigation use accessible native landmarks", () => {
  const html = renderToStaticMarkup(<DashboardView state={{ status: "authenticated",
    applicationUserId: USER_ID, memberships: [{ tenantId: TENANT_ID, role: "MEMBER" }],
    onboardingRequired: false }} />);
  assert.match(html, /<aside class="sidebar">/);
  assert.match(html, /<nav class="dashboard-navigation" aria-label="Primary">/);
  assert.match(html, /<details class="mobile-navigation"><summary>Navigation<\/summary>/);
  assert.match(html, /id="main-content"/);
});

test("server-only boundaries and Auth0 session protections are configured", () => {
  const backendBoundary = readFileSync(join(frontendRoot, "src/lib/server/backend-api.ts"), "utf8");
  const authBoundary = readFileSync(join(frontendRoot, "src/lib/server/auth0.ts"), "utf8");
  const reviewsBoundary = readFileSync(join(frontendRoot, "src/lib/server/dashboard-reviews.ts"), "utf8");
  const usageBoundary = readFileSync(join(frontendRoot, "src/lib/server/dashboard-usage.ts"), "utf8");
  assert.match(backendBoundary, /import "server-only"/);
  assert.match(authBoundary, /import "server-only"/);
  assert.match(reviewsBoundary, /import "server-only"/);
  assert.match(usageBoundary, /import "server-only"/);
  assert.doesNotMatch(reviewsBoundary, /"use client"|window\.|document\./);
  assert.doesNotMatch(usageBoundary, /"use client"|window\.|document\./);
  assert.match(authBoundary, /enableAccessTokenEndpoint: false/);
  assert.match(authBoundary, /signInReturnToPath: "\/dashboard"/);
  assert.match(authBoundary, /sameSite: "lax"/);
  assert.match(authBoundary, /secure: environment\.production/);
  assert.match(authBoundary, /user: \{ sub: session\.user\.sub \}/);
});

test("production recovery routing is dynamic and targets the verified custom backend origin", () => {
  const recoveryRoute = readFileSync(
    join(frontendRoot, "src/app/api/dashboard/recovery/route.ts"), "utf8");
  const blueprint = readFileSync(join(frontendRoot, "../render.yaml"), "utf8");
  assert.match(recoveryRoute, /export const dynamic = "force-dynamic"/);
  assert.match(recoveryRoute, /export const revalidate = 0/);
  assert.match(recoveryRoute, /"Cache-Control": "no-store, max-age=0"/);
  assert.match(blueprint, /key: BACKEND_BASE_URL\s+value: https:\/\/api\.pullsage\.com/);
  assert.doesNotMatch(blueprint, /key: BACKEND_BASE_URL\s+sync: false/);
});

test("production source has no browser token storage, raw HTML, or public secrets", () => {
  const files = ["src/app/page.tsx", "src/app/dashboard/page.tsx", "src/app/error.tsx",
    "src/app/global-error.tsx", "src/components/display-text.tsx", "src/components/dashboard-view.tsx",
    "src/lib/server/auth0.ts", "src/lib/server/dashboard-session.ts", "src/lib/dashboard-session-core.ts",
    "src/lib/dashboard-selection.ts", "src/lib/github-connection-core.ts",
    "src/lib/dashboard-repositories-core.ts", "src/lib/server/dashboard-repositories.ts",
    "src/components/repositories-view.tsx", "src/app/dashboard/repositories/page.tsx",
    "src/lib/dashboard-reviews-core.ts", "src/lib/server/dashboard-reviews.ts",
    "src/components/reviews-view.tsx", "src/app/dashboard/reviews/page.tsx",
    "src/lib/dashboard-usage-core.ts", "src/lib/server/dashboard-usage.ts",
    "src/components/usage-view.tsx", "src/app/dashboard/usage/page.tsx",
    "src/lib/server/github-connection.ts", "src/app/github/connect/route.ts",
    "src/app/github/callback/route.ts", "src/app/github/setup/route.ts"];
  const source = files.map((file) => readFileSync(join(frontendRoot, file), "utf8")).join("\n");
  const example = readFileSync(join(frontendRoot, "../.env.example"), "utf8");
  assert.doesNotMatch(source, /localStorage|sessionStorage|dangerouslySetInnerHTML|\beval\s*\(|new\s+Function\b/);
  assert.doesNotMatch(source, /NEXT_PUBLIC_/);
  assert.doesNotMatch(example, /NEXT_PUBLIC_.*(?:SECRET|TOKEN|KEY)/);
});

function review(overrides: Partial<DashboardReview>): DashboardReview {
  return {
    repositoryId: 123456,
    pullRequestNumber: 1,
    headSha: "0".repeat(40),
    state: "COMPLETED_WITHOUT_PUBLICATION",
    publishableFindingCount: null,
    createdAt: "2026-09-16T12:00:00Z",
    updatedAt: "2026-09-16T12:00:01Z",
    ...overrides,
  };
}

function usage(overrides: Partial<ReviewAnalysisUsage>): ReviewAnalysisUsage {
  return {
    periodStart: "2026-09-01T00:00:00Z",
    periodEnd: "2026-10-01T00:00:00Z",
    limit: 50,
    used: 0,
    remaining: 50,
    ...overrides,
  };
}

test("setup route ignores spoofable installation identifiers by construction", () => {
  const source = readFileSync(join(frontendRoot, "src/app/github/setup/route.ts"), "utf8");
  assert.doesNotMatch(source, /searchParams\.get\(["']installation_id/);
  assert.match(source, /\/dashboard\?connection=setup/);
});

test("dashboard navigation preserves only the selected authorized workspace identifier", () => {
  const html = renderToStaticMarkup(<DashboardView state={{ status: "authenticated",
    applicationUserId: USER_ID, memberships: [
      { tenantId: TENANT_ID, role: "OWNER" },
      { tenantId: SECOND_TENANT_ID, role: "MEMBER" },
    ], onboardingRequired: false }} requestedTenantId={TENANT_ID} />);
  for (const path of ["/dashboard", "/dashboard/repositories", "/dashboard/reviews", "/dashboard/usage"]) {
    assert.match(html, new RegExp(`href="${path.replaceAll("/", "\\/")}\\?tenant=${TENANT_ID}"`));
  }
  assert.doesNotMatch(html, /tenantOverride|accessToken|cursor=/);
});

test("all dashboard clients cancel incrementally oversized chunked responses", async () => {
  const clients: readonly [number, (fetcher: typeof fetch) => Promise<unknown>, string][] = [
    [65 * 1024, (fetcher) => requestDashboardSession(new URL("https://backend.example/session"),
      "session-token", fetcher), "BACKEND_RESPONSE_INVALID"],
    [129 * 1024, (fetcher) => requestDashboardRepositories(new URL("https://backend.example/repositories"),
      "repository-token", fetcher), "BACKEND_RESPONSE_INVALID"],
    [193 * 1024, (fetcher) => requestDashboardReviews(new URL("https://backend.example/reviews"),
      "review-token", fetcher), "BACKEND_RESPONSE_INVALID"],
    [33 * 1024, (fetcher) => requestDashboardUsage(new URL("https://backend.example/usage"),
      "usage-token", fetcher), "BACKEND_RESPONSE_INVALID"],
  ];

  for (const [size, invoke, kind] of clients) {
    let cancelled = false;
    const response = new Response(new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(new Uint8Array(size)); },
      cancel() { cancelled = true; },
    }), { status: 200 });
    await assert.rejects(invoke(async () => response),
      (error: DashboardSessionError | DashboardRepositoryError | DashboardReviewError | DashboardUsageError) =>
        error.kind === kind);
    assert.equal(cancelled, true);
  }
});
