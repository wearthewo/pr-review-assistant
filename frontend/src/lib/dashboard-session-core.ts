import { readBoundedResponseBody } from "@/lib/bounded-response";

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
  | "BACKEND_STARTING"
  | "BACKEND_TIMEOUT"
  | "BACKEND_UNAVAILABLE"
  | "SESSION_UNAVAILABLE";

export type DashboardBackendFailure =
  | "NETWORK_ERROR"
  | "TIMEOUT"
  | "HTTP_401"
  | "HTTP_403"
  | "HTTP_4XX"
  | "HTTP_502"
  | "HTTP_503"
  | "HTTP_504"
  | "HTTP_5XX"
  | "MALFORMED_RESPONSE";

export type DashboardBackendFailureReporter = (failure: DashboardBackendFailure) => void;

export interface DashboardBackendAttemptDiagnostic {
  targetOrigin: string;
  targetHost: string;
  pathname: string;
  method: "GET";
  startedAt: string;
  elapsedMs: number;
  upstreamStatus: number | null;
  abortFired: boolean;
  errorName: string | null;
  failure: DashboardBackendFailure | "NONE";
}

export type DashboardBackendAttemptReporter = (diagnostic: DashboardBackendAttemptDiagnostic) => void;

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
    if (!(error instanceof DashboardSessionError)) {
      throw error;
    }
    return {
      status: "error",
      kind: error.kind,
    };
  }
}

export async function requestDashboardSession(
  url: URL,
  token: string,
  fetchImplementation: typeof fetch,
  reportFailure: DashboardBackendFailureReporter = () => undefined,
  reportAttempt: DashboardBackendAttemptReporter = () => undefined,
): Promise<AuthenticatedDashboardSession> {
  if (!token) {
    throw new DashboardSessionError("SESSION_UNAVAILABLE");
  }
  const startedAtMillis = Date.now();
  const startedAt = new Date(startedAtMillis).toISOString();
  const timeoutSignal = AbortSignal.timeout(5_000);
  let attemptFinished = false;
  const finishAttempt = (
    upstreamStatus: number | null,
    failure: DashboardBackendFailure | "NONE",
    errorName: string | null = null,
  ) => {
    if (attemptFinished) return;
    attemptFinished = true;
    reportAttempt({
      targetOrigin: url.origin,
      targetHost: url.host,
      pathname: url.pathname,
      method: "GET",
      startedAt,
      elapsedMs: Math.max(0, Date.now() - startedAtMillis),
      upstreamStatus,
      abortFired: timeoutSignal.aborted,
      errorName,
      failure,
    });
  };
  let response: Response;
  try {
    response = await fetchImplementation(url, {
      method: "GET",
      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${token}`,
        "Cache-Control": "no-cache, no-store",
        Pragma: "no-cache",
      },
      cache: "no-store",
      redirect: "error",
      signal: timeoutSignal,
    });
  } catch (error) {
    if (error instanceof DOMException && (error.name === "TimeoutError" || error.name === "AbortError")) {
      reportFailure("TIMEOUT");
      finishAttempt(null, "TIMEOUT", safeNetworkErrorName(error));
      throw new DashboardSessionError("BACKEND_STARTING");
    }
    reportFailure("NETWORK_ERROR");
    finishAttempt(null, "NETWORK_ERROR", safeNetworkErrorName(error));
    throw new DashboardSessionError("BACKEND_STARTING");
  }
  if (response.status === 401) {
    reportFailure("HTTP_401");
    finishAttempt(response.status, "HTTP_401");
    throw new DashboardSessionError("AUTHORIZATION_FAILED");
  }
  if (response.status === 403) {
    reportFailure("HTTP_403");
    finishAttempt(response.status, "HTTP_403");
    throw new DashboardSessionError("AUTHORIZATION_FAILED");
  }
  if (response.status === 502 || response.status === 503 || response.status === 504) {
    const failure = `HTTP_${response.status}` as "HTTP_502" | "HTTP_503" | "HTTP_504";
    reportFailure(failure);
    finishAttempt(response.status, failure);
    throw new DashboardSessionError("BACKEND_STARTING");
  }
  if (!response.ok) {
    const failure = response.status >= 500 ? "HTTP_5XX" : "HTTP_4XX";
    reportFailure(failure);
    finishAttempt(response.status, failure);
    throw new DashboardSessionError("BACKEND_UNAVAILABLE");
  }
  try {
    const body = await readBoundedResponseBody(response, MAX_RESPONSE_BYTES,
      () => new DashboardSessionError("BACKEND_RESPONSE_INVALID"));

    let value: unknown;
    try {
      value = JSON.parse(body);
    } catch {
      throw new DashboardSessionError("BACKEND_RESPONSE_INVALID");
    }
    const session = validateSession(value);
    finishAttempt(response.status, "NONE");
    return session;
  } catch (error) {
    if (error instanceof DashboardSessionError && error.kind === "BACKEND_RESPONSE_INVALID") {
      reportFailure("MALFORMED_RESPONSE");
      finishAttempt(response.status, "MALFORMED_RESPONSE");
    } else {
      finishAttempt(response.status, "NETWORK_ERROR", safeNetworkErrorName(error));
    }
    throw error;
  }
}

const SAFE_NETWORK_ERROR_CODES = new Set([
  "EAI_AGAIN",
  "ECONNREFUSED",
  "ECONNRESET",
  "ENETUNREACH",
  "ENOTFOUND",
  "ETIMEDOUT",
  "UND_ERR_CONNECT_TIMEOUT",
  "UND_ERR_HEADERS_TIMEOUT",
  "UND_ERR_SOCKET",
]);

function safeNetworkErrorName(error: unknown): string {
  const name = error instanceof Error && /^[A-Za-z][A-Za-z0-9]*$/.test(error.name)
    ? error.name
    : "UnknownError";
  const cause = error instanceof Error ? error.cause : undefined;
  const code = isObject(cause) && typeof cause.code === "string" && SAFE_NETWORK_ERROR_CODES.has(cause.code)
    ? cause.code
    : null;
  return code === null ? name : `${name}:${code}`;
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
