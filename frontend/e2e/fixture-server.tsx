import { randomBytes } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

import { renderToStaticMarkup } from "react-dom/server";

import { DashboardView } from "@/components/dashboard-view";
import { RepositoriesView } from "@/components/repositories-view";
import { ReviewsView } from "@/components/reviews-view";
import { UsageView } from "@/components/usage-view";
import type { AuthenticatedDashboardSession, DashboardState } from "@/lib/dashboard-session-core";
import { selectAuthorizedTenant } from "@/lib/dashboard-selection";
import { BASE_SECURITY_HEADERS, buildContentSecurityPolicy } from "@/lib/security-headers";

const PORT = Number(process.env.M13G_FIXTURE_PORT ?? "3101");
const TENANT_A = "11111111-1111-4111-8111-111111111111";
const TENANT_B = "22222222-2222-4222-8222-222222222222";
const USER_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

type Actor = "owner" | "member" | "multi" | "unbound";

void startFixtureServer();

async function startFixtureServer() {
  const stylesheet = await readFile(fileURLToPath(new URL("../src/app/globals.css", import.meta.url)));
  createServer(async (request, response) => {
    const url = new URL(request.url ?? "/", `http://127.0.0.1:${PORT}`);
    if (url.pathname === "/__health") return send(response, 200, "text/plain; charset=utf-8", "ok");
    if (url.pathname === "/globals.css") return send(response, 200, "text/css; charset=utf-8", stylesheet);
    if (!url.pathname.startsWith("/dashboard")) return send(response, 404, "text/plain; charset=utf-8", "");

    const actor = readActor(request);
    const dashboard = dashboardState(actor);
    const requestedTenant = singleParameter(url, "tenant");
    const markup = renderRoute(url, dashboard, requestedTenant);
    const nonce = randomBytes(18).toString("base64");
    response.statusCode = 200;
    response.setHeader("Content-Type", "text/html; charset=utf-8");
    response.setHeader("Content-Security-Policy", buildContentSecurityPolicy(nonce, false));
    for (const [name, value] of Object.entries(BASE_SECURITY_HEADERS)) response.setHeader(name, value);
    response.end(document(markup));
  }).listen(PORT, "127.0.0.1");
}

function renderRoute(url: URL, dashboard: DashboardState, requestedTenant: string | null): string {
  if (url.pathname === "/dashboard") {
    return renderToStaticMarkup(<DashboardView state={dashboard} requestedTenantId={requestedTenant} />);
  }
  if (dashboard.status !== "authenticated" || dashboard.onboardingRequired) {
    return renderToStaticMarkup(gatedRouteView(url.pathname, dashboard));
  }
  const selection = selectAuthorizedTenant(dashboard.memberships, requestedTenant);
  if (selection.status === "invalid") {
    return renderToStaticMarkup(invalidTenantRouteView(url.pathname, dashboard));
  }
  const tenantId = selection.membership.tenantId;
  if (url.pathname === "/dashboard/repositories") {
    const repositoryId = tenantId === TENANT_A ? 101001 : 202002;
    return renderToStaticMarkup(<RepositoriesView state={{ status: "ready", dashboard,
      selectedTenantId: tenantId, repositories: { repositories: [{ repositoryId,
        connectedAt: "2026-09-18T12:00:00Z" }], truncated: false } }} />);
  }
  if (url.pathname === "/dashboard/reviews") {
    const repositoryId = tenantId === TENANT_A ? 101001 : 202002;
    const pullRequestNumber = tenantId === TENANT_A ? 41 : 82;
    return renderToStaticMarkup(<ReviewsView state={{ status: "ready", dashboard,
      selectedTenantId: tenantId, page: { reviews: [{ repositoryId, pullRequestNumber,
        headSha: tenantId === TENANT_A ? "a".repeat(40) : "b".repeat(40), state: "PUBLISHED",
        publishableFindingCount: tenantId === TENANT_A ? 1 : 2,
        createdAt: "2026-09-18T12:00:00Z", updatedAt: "2026-09-18T12:01:00Z" }],
      hasMore: false, nextCursor: null } }} />);
  }
  if (url.pathname === "/dashboard/usage") {
    const used = tenantId === TENANT_A ? 7 : 19;
    return renderToStaticMarkup(<UsageView state={{ status: "ready", dashboard,
      selectedTenantId: tenantId, usage: { reviewAnalysis: {
        periodStart: "2026-09-01T00:00:00Z", periodEnd: "2026-10-01T00:00:00Z",
        limit: 50, used, remaining: 50 - used,
      } } }} />);
  }
  return renderToStaticMarkup(<DashboardView state={dashboard} requestedTenantId="invalid" />);
}

function gatedRouteView(pathname: string, dashboard: DashboardState) {
  const state = { status: "gate" as const, dashboard };
  if (pathname === "/dashboard/repositories") return <RepositoriesView state={state} />;
  if (pathname === "/dashboard/reviews") return <ReviewsView state={state} />;
  if (pathname === "/dashboard/usage") return <UsageView state={state} />;
  return <DashboardView state={state.dashboard} requestedTenantId="invalid" />;
}

function invalidTenantRouteView(pathname: string, dashboard: AuthenticatedDashboardSession) {
  const state = { status: "invalid-tenant" as const, dashboard };
  if (pathname === "/dashboard/repositories") return <RepositoriesView state={state} />;
  if (pathname === "/dashboard/reviews") return <ReviewsView state={state} />;
  if (pathname === "/dashboard/usage") return <UsageView state={state} />;
  return <DashboardView state={dashboard} requestedTenantId="invalid" />;
}

function dashboardState(actor: Actor): DashboardState {
  if (actor === "unbound") {
    return { status: "authenticated", applicationUserId: USER_ID, memberships: [], onboardingRequired: true };
  }
  const memberships = actor === "multi"
    ? [{ tenantId: TENANT_A, role: "OWNER" as const }, { tenantId: TENANT_B, role: "MEMBER" as const }]
    : [{ tenantId: actor === "owner" ? TENANT_A : TENANT_B,
      role: actor === "owner" ? "OWNER" as const : "MEMBER" as const }];
  return { status: "authenticated", applicationUserId: USER_ID, memberships, onboardingRequired: false };
}

function readActor(request: IncomingMessage): Actor {
  const match = /(?:^|;\s*)m13g_actor=(owner|member|multi|unbound)(?:;|$)/.exec(request.headers.cookie ?? "");
  return (match?.[1] as Actor | undefined) ?? "owner";
}

function singleParameter(url: URL, name: string): string | null {
  const values = url.searchParams.getAll(name);
  if (values.length === 0) return null;
  return values.length === 1 ? values[0] : "";
}

function document(markup: string): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>M13G fixture</title><link rel="stylesheet" href="/globals.css"></head><body><a class="skip-link" href="#main-content">Skip to content</a>${markup}</body></html>`;
}

function send(response: ServerResponse, status: number, contentType: string, body: string | Buffer) {
  response.statusCode = status;
  response.setHeader("Content-Type", contentType);
  response.end(body);
}
