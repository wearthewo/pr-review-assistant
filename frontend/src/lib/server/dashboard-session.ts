import "server-only";

import type { Auth0Client } from "@auth0/nextjs-auth0/server";

import {
  type AuthenticatedDashboardSession,
  type DashboardState,
  loadDashboardStateWith,
  requestDashboardSession,
} from "@/lib/dashboard-session-core";
import { auth0 } from "@/lib/server/auth0";
import { createBackendUrl } from "@/lib/server/backend-api";

type ServerAuthClient = Pick<Auth0Client, "getSession" | "getAccessToken">;

export async function loadDashboardState(
  authentication: ServerAuthClient = auth0,
  requestSession: (token: string) => Promise<AuthenticatedDashboardSession> = fetchDashboardSession,
): Promise<DashboardState> {
  return loadDashboardStateWith({
    hasSession: async () => (await authentication.getSession()) !== null,
    accessToken: async () => (await authentication.getAccessToken()).token,
  }, requestSession);
}

export async function fetchDashboardSession(
  token: string,
  fetchImplementation: typeof fetch = fetch,
): Promise<AuthenticatedDashboardSession> {
  return requestDashboardSession(
    createBackendUrl("/api/dashboard/session"), token, fetchImplementation);
}
