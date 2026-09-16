import "server-only";

import type { Auth0Client } from "@auth0/nextjs-auth0/server";

import { dashboardPathForConnectionResult, validateGitHubAuthorizationUrl } from "@/lib/github-connection-core";
import { auth0 } from "@/lib/server/auth0";
import { loadAuthEnvironment } from "@/lib/server/auth-environment";
import { createBackendUrl } from "@/lib/server/backend-api";

type ServerAuthClient = Pick<Auth0Client, "getSession" | "getAccessToken">;
const MAX_RESPONSE_BYTES = 64 * 1024;

export async function beginGitHubConnection(authentication: ServerAuthClient = auth0): Promise<URL | null> {
  if (await authentication.getSession() === null) return null;
  const token = (await authentication.getAccessToken()).token;
  const body = await backendJson("/api/dashboard/github-connection/start", token);
  const oauthOrigin = new URL(process.env.GITHUB_OAUTH_BASE_URL ?? "https://github.com").origin;
  return validateGitHubAuthorizationUrl(body.authorizationUrl, oauthOrigin);
}

export async function completeGitHubConnection(
  code: string,
  state: string,
  authentication: ServerAuthClient = auth0,
): Promise<string | null> {
  if (await authentication.getSession() === null) return null;
  const token = (await authentication.getAccessToken()).token;
  const body = await backendJson("/api/dashboard/github-connection/callback", token, { code, state });
  return dashboardPathForConnectionResult(body.result);
}

async function backendJson(path: `/${string}`, token: string, payload?: object): Promise<Record<string, unknown>> {
  if (!token) throw new Error("Protected session is unavailable");
  const response = await fetch(createBackendUrl(path), {
    method: "POST",
    headers: { Accept: "application/json", Authorization: `Bearer ${token}`,
      ...(payload ? { "Content-Type": "application/json" } : {}) },
    body: payload ? JSON.stringify(payload) : undefined,
    cache: "no-store",
    redirect: "error",
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error("GitHub connection backend request failed");
  const declared = Number(response.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > MAX_RESPONSE_BYTES) throw new Error("GitHub connection response is invalid");
  const text = await response.text();
  if (new TextEncoder().encode(text).byteLength > MAX_RESPONSE_BYTES) throw new Error("GitHub connection response is invalid");
  const value: unknown = JSON.parse(text);
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("GitHub connection response is invalid");
  return value as Record<string, unknown>;
}

export function applicationOrigin(): string {
  return loadAuthEnvironment().appBaseUrl;
}
