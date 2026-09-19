import { DashboardSessionError } from "@/lib/dashboard-session-core";

const MAX_RESPONSE_BYTES = 32 * 1024;

export type UsageFailureKind =
  | "AUTHORIZATION_FAILED"
  | "BACKEND_RESPONSE_INVALID"
  | "BACKEND_TIMEOUT"
  | "BACKEND_UNAVAILABLE";

export interface ReviewAnalysisUsage {
  periodStart: string;
  periodEnd: string;
  limit: number;
  used: number;
  remaining: number;
}

export interface DashboardUsage {
  reviewAnalysis: ReviewAnalysisUsage;
}

export class DashboardUsageError extends Error {
  constructor(readonly kind: UsageFailureKind) {
    super("Usage information is unavailable");
    this.name = "DashboardUsageError";
  }

  override toString(): string {
    return `${this.name}{kind=${this.kind}}`;
  }
}

export async function requestDashboardUsage(
  url: URL,
  token: string,
  fetchImplementation: typeof fetch,
): Promise<DashboardUsage> {
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
      throw new DashboardUsageError("BACKEND_TIMEOUT");
    }
    throw new DashboardUsageError("BACKEND_UNAVAILABLE");
  }
  if (response.status === 401 || response.status === 403 || response.status === 404) {
    throw new DashboardUsageError("AUTHORIZATION_FAILED");
  }
  if (!response.ok) throw new DashboardUsageError("BACKEND_UNAVAILABLE");

  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_RESPONSE_BYTES) {
    throw new DashboardUsageError("BACKEND_RESPONSE_INVALID");
  }
  const body = await readBoundedBody(response);
  try {
    return validateDashboardUsage(JSON.parse(body));
  } catch (error) {
    if (error instanceof DashboardUsageError) throw error;
    throw new DashboardUsageError("BACKEND_RESPONSE_INVALID");
  }
}

async function readBoundedBody(response: Response): Promise<string> {
  if (response.body === null) return "";
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_RESPONSE_BYTES) {
        await reader.cancel();
        throw new DashboardUsageError("BACKEND_RESPONSE_INVALID");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}

export function usagePercentage(usage: ReviewAnalysisUsage): number {
  if (usage.limit <= 0 || usage.used <= 0) return 0;
  return Math.min(100, Math.max(0, Math.floor((usage.used / usage.limit) * 100)));
}

function validateDashboardUsage(value: unknown): DashboardUsage {
  if (!isObject(value) || !isObject(value.reviewAnalysis)) {
    throw new DashboardUsageError("BACKEND_RESPONSE_INVALID");
  }
  const usage = value.reviewAnalysis;
  if (typeof usage.periodStart !== "string" || !isTimestamp(usage.periodStart)
      || typeof usage.periodEnd !== "string" || !isTimestamp(usage.periodEnd)
      || Date.parse(usage.periodEnd) <= Date.parse(usage.periodStart)
      || !isNonNegativeSafeInteger(usage.limit) || Number(usage.limit) < 1
      || !isNonNegativeSafeInteger(usage.used)
      || !isNonNegativeSafeInteger(usage.remaining)
      || Number(usage.remaining) > Number(usage.limit)
      || Number(usage.remaining) !== Math.max(0, Number(usage.limit) - Number(usage.used))) {
    throw new DashboardUsageError("BACKEND_RESPONSE_INVALID");
  }
  return { reviewAnalysis: {
    periodStart: usage.periodStart,
    periodEnd: usage.periodEnd,
    limit: Number(usage.limit),
    used: Number(usage.used),
    remaining: Number(usage.remaining),
  } };
}

function isNonNegativeSafeInteger(value: unknown): boolean {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function isTimestamp(value: string): boolean {
  return value.length > 0 && value.length <= 64 && Number.isFinite(Date.parse(value));
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
