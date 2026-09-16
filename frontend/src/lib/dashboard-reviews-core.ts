import { DashboardSessionError } from "@/lib/dashboard-session-core";

const MAX_RESPONSE_BYTES = 192 * 1024;
const MAX_REVIEWS = 50;
export const MAX_REVIEW_CURSOR_LENGTH = 160;

export const DASHBOARD_REVIEW_STATES = [
  "QUEUED",
  "ANALYZING",
  "ANALYSIS_FAILED",
  "COMPLETED_WITHOUT_PUBLICATION",
  "PUBLICATION_PENDING",
  "PUBLICATION_UNCERTAIN",
  "PUBLISHED",
  "PUBLICATION_FAILED",
] as const;

export type DashboardReviewState = typeof DASHBOARD_REVIEW_STATES[number];
export type ReviewFailureKind =
  | "AUTHORIZATION_FAILED"
  | "BACKEND_RESPONSE_INVALID"
  | "BACKEND_TIMEOUT"
  | "BACKEND_UNAVAILABLE"
  | "INVALID_CURSOR";

export interface DashboardReview {
  repositoryId: number;
  pullRequestNumber: number;
  headSha: string;
  state: DashboardReviewState;
  publishableFindingCount: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface DashboardReviewPage {
  reviews: readonly DashboardReview[];
  hasMore: boolean;
  nextCursor: string | null;
}

export class DashboardReviewError extends Error {
  constructor(readonly kind: ReviewFailureKind) {
    super("Review history is unavailable");
    this.name = "DashboardReviewError";
  }

  override toString(): string {
    return `${this.name}{kind=${this.kind}}`;
  }
}

export function isReviewCursor(value: string): boolean {
  return value.length > 0 && value.length <= MAX_REVIEW_CURSOR_LENGTH
    && /^[A-Za-z0-9_-]+$/.test(value);
}

export async function requestDashboardReviews(
  url: URL,
  token: string,
  fetchImplementation: typeof fetch,
): Promise<DashboardReviewPage> {
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
      throw new DashboardReviewError("BACKEND_TIMEOUT");
    }
    throw new DashboardReviewError("BACKEND_UNAVAILABLE");
  }
  if (response.status === 400) throw new DashboardReviewError("INVALID_CURSOR");
  if (response.status === 401 || response.status === 403 || response.status === 404) {
    throw new DashboardReviewError("AUTHORIZATION_FAILED");
  }
  if (!response.ok) throw new DashboardReviewError("BACKEND_UNAVAILABLE");

  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_RESPONSE_BYTES) {
    throw new DashboardReviewError("BACKEND_RESPONSE_INVALID");
  }
  const body = await response.text();
  if (new TextEncoder().encode(body).byteLength > MAX_RESPONSE_BYTES) {
    throw new DashboardReviewError("BACKEND_RESPONSE_INVALID");
  }
  try {
    return validateReviewPage(JSON.parse(body));
  } catch (error) {
    if (error instanceof DashboardReviewError) throw error;
    throw new DashboardReviewError("BACKEND_RESPONSE_INVALID");
  }
}

function validateReviewPage(value: unknown): DashboardReviewPage {
  if (!isObject(value) || !Array.isArray(value.reviews) || value.reviews.length > MAX_REVIEWS
      || typeof value.hasMore !== "boolean") {
    throw new DashboardReviewError("BACKEND_RESPONSE_INVALID");
  }
  const nextCursor = value.nextCursor === undefined || value.nextCursor === null
    ? null : value.nextCursor;
  if ((nextCursor !== null && (typeof nextCursor !== "string" || !isReviewCursor(nextCursor)))
      || value.hasMore !== (nextCursor !== null)) {
    throw new DashboardReviewError("BACKEND_RESPONSE_INVALID");
  }
  const reviews = value.reviews.map(validateReview);
  if (reviews.some((review, index) => index > 0
      && Date.parse(reviews[index - 1].createdAt) < Date.parse(review.createdAt))) {
    throw new DashboardReviewError("BACKEND_RESPONSE_INVALID");
  }
  return { reviews, hasMore: value.hasMore, nextCursor };
}

function validateReview(value: unknown): DashboardReview {
  if (!isObject(value) || !Number.isSafeInteger(value.repositoryId) || Number(value.repositoryId) <= 0
      || !Number.isSafeInteger(value.pullRequestNumber) || Number(value.pullRequestNumber) <= 0
      || typeof value.headSha !== "string" || !/^[0-9a-f]{40,64}$/.test(value.headSha)
      || typeof value.state !== "string"
      || !DASHBOARD_REVIEW_STATES.includes(value.state as DashboardReviewState)
      || typeof value.createdAt !== "string" || !isTimestamp(value.createdAt)
      || typeof value.updatedAt !== "string" || !isTimestamp(value.updatedAt)) {
    throw new DashboardReviewError("BACKEND_RESPONSE_INVALID");
  }
  const publicationState = value.state.startsWith("PUBLICATION_") || value.state === "PUBLISHED";
  const findingCount = value.publishableFindingCount === undefined || value.publishableFindingCount === null
    ? null : value.publishableFindingCount;
  if (publicationState !== (findingCount !== null)
      || (findingCount !== null && (!Number.isSafeInteger(findingCount)
        || Number(findingCount) < 1 || Number(findingCount) > 5))) {
    throw new DashboardReviewError("BACKEND_RESPONSE_INVALID");
  }
  return {
    repositoryId: Number(value.repositoryId),
    pullRequestNumber: Number(value.pullRequestNumber),
    headSha: value.headSha,
    state: value.state as DashboardReviewState,
    publishableFindingCount: findingCount === null ? null : Number(findingCount),
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  };
}

function isTimestamp(value: string): boolean {
  return value.length > 0 && value.length <= 64 && Number.isFinite(Date.parse(value));
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
