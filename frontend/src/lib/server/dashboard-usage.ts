import "server-only";

import type { Auth0Client } from "@auth0/nextjs-auth0/server";

import {
  DashboardUsageError,
  type DashboardUsage,
  type UsageFailureKind,
  requestDashboardUsage,
} from "@/lib/dashboard-usage-core";
import { selectAuthorizedTenant } from "@/lib/dashboard-selection";
import {
  type AuthenticatedDashboardSession,
  type DashboardState,
  loadDashboardStateWith,
  requestDashboardSession,
} from "@/lib/dashboard-session-core";
import { auth0 } from "@/lib/server/auth0";
import { createBackendUrl } from "@/lib/server/backend-api";

type ServerAuthClient = Pick<Auth0Client, "getSession" | "getAccessToken">;

export type UsageDashboardState =
  | { status: "gate"; dashboard: DashboardState }
  | { status: "invalid-tenant"; dashboard: AuthenticatedDashboardSession }
  | { status: "usage-error"; dashboard: AuthenticatedDashboardSession;
      selectedTenantId: string; kind: UsageFailureKind }
  | { status: "ready"; dashboard: AuthenticatedDashboardSession;
      selectedTenantId: string; usage: DashboardUsage };

export async function loadUsageDashboard(
  requestedTenantId: string | null,
  authentication: ServerAuthClient = auth0,
  fetchImplementation: typeof fetch = fetch,
): Promise<UsageDashboardState> {
  let token = "";
  const dashboard = await loadDashboardStateWith({
    hasSession: async () => (await authentication.getSession()) !== null,
    accessToken: async () => {
      token = (await authentication.getAccessToken()).token;
      return token;
    },
  }, (accessToken) => requestDashboardSession(
    createBackendUrl("/api/dashboard/session"), accessToken, fetchImplementation));

  if (dashboard.status !== "authenticated" || dashboard.onboardingRequired) {
    return { status: "gate", dashboard };
  }
  const selection = selectAuthorizedTenant(dashboard.memberships, requestedTenantId);
  if (selection.status === "invalid") return { status: "invalid-tenant", dashboard };
  const selectedTenantId = selection.membership.tenantId;
  try {
    return { status: "ready", dashboard, selectedTenantId,
      usage: await requestDashboardUsage(
        createBackendUrl(`/api/dashboard/tenants/${selectedTenantId}/usage`), token, fetchImplementation) };
  } catch (error) {
    return { status: "usage-error", dashboard, selectedTenantId,
      kind: error instanceof DashboardUsageError ? error.kind : "BACKEND_UNAVAILABLE" };
  }
}
