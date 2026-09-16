import "server-only";

import type { Auth0Client } from "@auth0/nextjs-auth0/server";

import {
  DashboardRepositoryError,
  type DashboardRepositoryPage,
  type RepositoryFailureKind,
  requestDashboardRepositories,
} from "@/lib/dashboard-repositories-core";
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

export type RepositoriesDashboardState =
  | { status: "gate"; dashboard: DashboardState }
  | { status: "invalid-tenant"; dashboard: AuthenticatedDashboardSession }
  | { status: "repository-error"; dashboard: AuthenticatedDashboardSession;
      selectedTenantId: string; kind: RepositoryFailureKind }
  | { status: "ready"; dashboard: AuthenticatedDashboardSession;
      selectedTenantId: string; repositories: DashboardRepositoryPage };

export async function loadRepositoriesDashboard(
  requestedTenantId: string | null,
  authentication: ServerAuthClient = auth0,
  fetchImplementation: typeof fetch = fetch,
): Promise<RepositoriesDashboardState> {
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
  if (selection.status === "invalid") {
    return { status: "invalid-tenant", dashboard };
  }
  const selectedTenantId = selection.membership.tenantId;
  try {
    const repositories = await requestDashboardRepositories(createBackendUrl(
      `/api/dashboard/tenants/${selectedTenantId}/repositories`), token, fetchImplementation);
    return { status: "ready", dashboard, selectedTenantId, repositories };
  } catch (error) {
    return { status: "repository-error", dashboard, selectedTenantId,
      kind: error instanceof DashboardRepositoryError ? error.kind : "BACKEND_UNAVAILABLE" };
  }
}
