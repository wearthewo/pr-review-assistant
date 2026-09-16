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

export type DashboardFailureKind =
  | "AUTHORIZATION_FAILED"
  | "BACKEND_RESPONSE_INVALID"
  | "BACKEND_TIMEOUT"
  | "BACKEND_UNAVAILABLE"
  | "SESSION_UNAVAILABLE";

export interface FailedDashboardSession {
  status: "error";
  kind: DashboardFailureKind;
}

export type DashboardState =
  | AuthenticatedDashboardSession
  | FailedDashboardSession
  | UnauthenticatedDashboardSession;

export class DashboardSessionError extends Error {
  constructor(readonly kind: DashboardFailureKind) {
    super("Dashboard session is unavailable");
    this.name = "DashboardSessionError";
  }

  override toString(): string {
    return `${this.name}{kind=${this.kind}}`;
  }
}

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
  try {
    return await requestSession(await authentication.accessToken());
  } catch (error) {
    return {
      status: "error",
      kind: error instanceof DashboardSessionError ? error.kind : "BACKEND_UNAVAILABLE",
    };
  }
}

export async function requestDashboardSession(
  url: URL,
  token: string,
  fetchImplementation: typeof fetch,
): Promise<AuthenticatedDashboardSession> {
  if (!token) {
    throw new DashboardSessionError("SESSION_UNAVAILABLE");
  }
  let response: Response;
  try {
    response = await fetchImplementation(url, {
      method: "GET",
      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${token}`,
      },
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(5_000),
    });
  } catch (error) {
    if (error instanceof DOMException && (error.name === "TimeoutError" || error.name === "AbortError")) {
      throw new DashboardSessionError("BACKEND_TIMEOUT");
    }
    throw new DashboardSessionError("BACKEND_UNAVAILABLE");
  }
  if (response.status === 401 || response.status === 403) {
    throw new DashboardSessionError("AUTHORIZATION_FAILED");
  }
  if (!response.ok) {
    throw new DashboardSessionError("BACKEND_UNAVAILABLE");
  }
  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_RESPONSE_BYTES) {
    throw new DashboardSessionError("BACKEND_RESPONSE_INVALID");
  }
  const body = await response.text();
  if (new TextEncoder().encode(body).byteLength > MAX_RESPONSE_BYTES) {
    throw new DashboardSessionError("BACKEND_RESPONSE_INVALID");
  }

  let value: unknown;
  try {
    value = JSON.parse(body);
  } catch {
    throw new DashboardSessionError("BACKEND_RESPONSE_INVALID");
  }
  return validateSession(value);
}

function validateSession(value: unknown): AuthenticatedDashboardSession {
  if (!isObject(value)
      || !isUuid(value.applicationUserId)
      || !Array.isArray(value.memberships)
      || value.memberships.length > MAX_MEMBERSHIPS
      || typeof value.onboardingRequired !== "boolean") {
    throw new DashboardSessionError("BACKEND_RESPONSE_INVALID");
  }
  const memberships = value.memberships.map((entry) => {
    if (!isObject(entry) || !isUuid(entry.tenantId) || (entry.role !== "OWNER" && entry.role !== "MEMBER")) {
      throw new DashboardSessionError("BACKEND_RESPONSE_INVALID");
    }
    const role: MembershipRole = entry.role;
    return { tenantId: entry.tenantId, role };
  });
  if (new Set(memberships.map(({ tenantId }) => tenantId)).size !== memberships.length) {
    throw new DashboardSessionError("BACKEND_RESPONSE_INVALID");
  }
  if (value.onboardingRequired !== (memberships.length === 0)) {
    throw new DashboardSessionError("BACKEND_RESPONSE_INVALID");
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
