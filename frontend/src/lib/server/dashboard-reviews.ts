import "server-only";

import type { Auth0Client } from "@auth0/nextjs-auth0/server";

import {
  DashboardReviewError,
  isReviewCursor,
  type DashboardReviewPage,
  type ReviewFailureKind,
  requestDashboardReviews,
} from "@/lib/dashboard-reviews-core";
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

export type ReviewsDashboardState =
  | { status: "gate"; dashboard: DashboardState }
  | { status: "invalid-tenant"; dashboard: AuthenticatedDashboardSession }
  | { status: "invalid-cursor"; dashboard: AuthenticatedDashboardSession; selectedTenantId: string }
  | { status: "review-error"; dashboard: AuthenticatedDashboardSession;
      selectedTenantId: string; kind: ReviewFailureKind }
  | { status: "ready"; dashboard: AuthenticatedDashboardSession;
      selectedTenantId: string; page: DashboardReviewPage };

export async function loadReviewsDashboard(
  requestedTenantId: string | null,
  requestedCursor: string | null,
  authentication: ServerAuthClient = auth0,
  fetchImplementation: typeof fetch = fetch,
): Promise<ReviewsDashboardState> {
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
  if (requestedCursor !== null && !isReviewCursor(requestedCursor)) {
    return { status: "invalid-cursor", dashboard, selectedTenantId };
  }
  const url = createBackendUrl(`/api/dashboard/tenants/${selectedTenantId}/reviews`);
  if (requestedCursor !== null) url.searchParams.set("cursor", requestedCursor);
  try {
    return { status: "ready", dashboard, selectedTenantId,
      page: await requestDashboardReviews(url, token, fetchImplementation) };
  } catch (error) {
    return { status: "review-error", dashboard, selectedTenantId,
      kind: error instanceof DashboardReviewError ? error.kind : "BACKEND_UNAVAILABLE" };
  }
}
