const MAX_RESPONSE_BYTES = 64 * 1024;
const MAX_MEMBERSHIPS = 100;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export type MembershipRole = "OWNER" | "MEMBER";

export interface DashboardMembership {
  tenantId: string;
  role: MembershipRole;
}

export interface AuthenticatedDashboardSession {
  status: "authenticated";
  applicationUserId: string;
  memberships: readonly DashboardMembership[];
  onboardingRequired: boolean;
}

export interface UnauthenticatedDashboardSession {
  status: "unauthenticated";
}

export type DashboardState = AuthenticatedDashboardSession | UnauthenticatedDashboardSession;

export interface ServerAuthentication {
  hasSession(): Promise<boolean>;
  accessToken(): Promise<string>;
}

export async function loadDashboardStateWith(
  authentication: ServerAuthentication,
  requestSession: (token: string) => Promise<AuthenticatedDashboardSession>,
): Promise<DashboardState> {
  if (!await authentication.hasSession()) {
    return { status: "unauthenticated" };
  }
  return requestSession(await authentication.accessToken());
}

export async function requestDashboardSession(
  url: URL,
  token: string,
  fetchImplementation: typeof fetch,
): Promise<AuthenticatedDashboardSession> {
  if (!token) {
    throw new Error("Authenticated backend session is unavailable");
  }
  const response = await fetchImplementation(url, {
    method: "GET",
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${token}`,
    },
    cache: "no-store",
    redirect: "error",
    signal: AbortSignal.timeout(5_000),
  });
  if (!response.ok) {
    throw new Error("Authenticated backend session request failed");
  }
  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_RESPONSE_BYTES) {
    throw new Error("Authenticated backend session response is invalid");
  }
  const body = await response.text();
  if (new TextEncoder().encode(body).byteLength > MAX_RESPONSE_BYTES) {
    throw new Error("Authenticated backend session response is invalid");
  }

  let value: unknown;
  try {
    value = JSON.parse(body);
  } catch {
    throw new Error("Authenticated backend session response is invalid");
  }
  return validateSession(value);
}

function validateSession(value: unknown): AuthenticatedDashboardSession {
  if (!isObject(value)
      || !isUuid(value.applicationUserId)
      || !Array.isArray(value.memberships)
      || value.memberships.length > MAX_MEMBERSHIPS
      || typeof value.onboardingRequired !== "boolean") {
    throw new Error("Authenticated backend session response is invalid");
  }
  const memberships = value.memberships.map((entry) => {
    if (!isObject(entry) || !isUuid(entry.tenantId) || (entry.role !== "OWNER" && entry.role !== "MEMBER")) {
      throw new Error("Authenticated backend session response is invalid");
    }
    const role: MembershipRole = entry.role;
    return { tenantId: entry.tenantId, role };
  });
  if (value.onboardingRequired !== (memberships.length === 0)) {
    throw new Error("Authenticated backend session response is invalid");
  }
  return {
    status: "authenticated",
    applicationUserId: value.applicationUserId,
    memberships,
    onboardingRequired: value.onboardingRequired,
  };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}
