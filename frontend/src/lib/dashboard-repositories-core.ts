import { DashboardSessionError } from "@/lib/dashboard-session-core";

const MAX_RESPONSE_BYTES = 128 * 1024;
const MAX_REPOSITORIES = 100;

export type RepositoryFailureKind =
  | "AUTHORIZATION_FAILED"
  | "BACKEND_RESPONSE_INVALID"
  | "BACKEND_TIMEOUT"
  | "BACKEND_UNAVAILABLE";

export interface DashboardRepository {
  repositoryId: number;
  connectedAt: string;
}

export interface DashboardRepositoryPage {
  repositories: readonly DashboardRepository[];
  truncated: boolean;
}

export class DashboardRepositoryError extends Error {
  constructor(readonly kind: RepositoryFailureKind) {
    super("Repository data is unavailable");
    this.name = "DashboardRepositoryError";
  }

  override toString(): string {
    return `${this.name}{kind=${this.kind}}`;
  }
}

export async function requestDashboardRepositories(
  url: URL,
  token: string,
  fetchImplementation: typeof fetch,
): Promise<DashboardRepositoryPage> {
  if (!token) throw new DashboardSessionError("SESSION_UNAVAILABLE");
  let response: Response;
  try {
    response = await fetchImplementation(url, {
      method: "GET",
      headers: { Accept: "application/json", Authorization: `Bearer ${token}` },
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(5_000),
    });
  } catch (error) {
    if (error instanceof DOMException && (error.name === "TimeoutError" || error.name === "AbortError")) {
      throw new DashboardRepositoryError("BACKEND_TIMEOUT");
    }
    throw new DashboardRepositoryError("BACKEND_UNAVAILABLE");
  }
  if (response.status === 401 || response.status === 403 || response.status === 404) {
    throw new DashboardRepositoryError("AUTHORIZATION_FAILED");
  }
  if (!response.ok) throw new DashboardRepositoryError("BACKEND_UNAVAILABLE");

  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_RESPONSE_BYTES) {
    throw new DashboardRepositoryError("BACKEND_RESPONSE_INVALID");
  }
  const body = await response.text();
  if (new TextEncoder().encode(body).byteLength > MAX_RESPONSE_BYTES) {
    throw new DashboardRepositoryError("BACKEND_RESPONSE_INVALID");
  }
  let value: unknown;
  try {
    value = JSON.parse(body);
  } catch {
    throw new DashboardRepositoryError("BACKEND_RESPONSE_INVALID");
  }
  return validateRepositoryPage(value);
}

function validateRepositoryPage(value: unknown): DashboardRepositoryPage {
  if (!isObject(value) || !Array.isArray(value.repositories)
      || value.repositories.length > MAX_REPOSITORIES || typeof value.truncated !== "boolean") {
    throw new DashboardRepositoryError("BACKEND_RESPONSE_INVALID");
  }
  const repositories = value.repositories.map((entry) => {
    if (!isObject(entry) || !Number.isSafeInteger(entry.repositoryId) || Number(entry.repositoryId) <= 0
        || typeof entry.connectedAt !== "string" || entry.connectedAt.length > 64
        || !Number.isFinite(Date.parse(entry.connectedAt))) {
      throw new DashboardRepositoryError("BACKEND_RESPONSE_INVALID");
    }
    return { repositoryId: Number(entry.repositoryId), connectedAt: entry.connectedAt };
  });
  if (repositories.some((repository, index) => index > 0
      && repositories[index - 1].repositoryId >= repository.repositoryId)) {
    throw new DashboardRepositoryError("BACKEND_RESPONSE_INVALID");
  }
  return { repositories, truncated: value.truncated };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
