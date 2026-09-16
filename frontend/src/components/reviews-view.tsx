import Link from "next/link";

import { DashboardFrame, DashboardView, TenantSelector } from "@/components/dashboard-view";
import type {
  DashboardReview,
  DashboardReviewPage,
  DashboardReviewState,
  ReviewFailureKind,
} from "@/lib/dashboard-reviews-core";
import { selectAuthorizedTenant } from "@/lib/dashboard-selection";
import type { ReviewsDashboardState } from "@/lib/server/dashboard-reviews";

const STATE_LABELS: Record<DashboardReviewState, string> = {
  QUEUED: "Queued",
  ANALYZING: "Analysis in progress",
  ANALYSIS_FAILED: "Analysis failed",
  COMPLETED_WITHOUT_PUBLICATION: "Completed without a publication record",
  PUBLICATION_PENDING: "Publication pending",
  PUBLICATION_UNCERTAIN: "Publication outcome uncertain",
  PUBLISHED: "Published to GitHub",
  PUBLICATION_FAILED: "Publication failed",
};

export function ReviewsView({ state }: Readonly<{ state: ReviewsDashboardState }>) {
  if (state.status === "gate") return <DashboardView state={state.dashboard} />;
  if (state.status === "invalid-tenant") {
    return <DashboardView state={state.dashboard} requestedTenantId="invalid-selection" />;
  }
  const selection = selectAuthorizedTenant(state.dashboard.memberships, state.selectedTenantId);
  if (selection.status === "invalid") {
    return <DashboardView state={state.dashboard} requestedTenantId="invalid-selection" />;
  }
  return (
    <DashboardFrame membership={selection.membership} activeSection="Reviews">
      <header className="reviews-header">
        <div>
          <p className="eyebrow">Reviews</p>
          <h1>Review history</h1>
          <p className="overview-lede">
            Durable analysis and publication states for exact pull request revisions.
          </p>
        </div>
        <TenantSelector membership={selection.membership} memberships={selection.memberships}
          action="/dashboard/reviews" />
      </header>
      {state.status === "invalid-cursor"
        ? <ReviewError kind="INVALID_CURSOR" />
        : state.status === "review-error"
          ? <ReviewError kind={state.kind} />
          : <ReviewHistory page={state.page} tenantId={state.selectedTenantId} />}
    </DashboardFrame>
  );
}

export function ReviewHistory({ page, tenantId }: Readonly<{
  page: DashboardReviewPage; tenantId: string;
}>) {
  if (page.reviews.length === 0) {
    return (
      <section className="review-state" aria-labelledby="review-empty-title">
        <p className="section-kicker">No persisted reviews</p>
        <h2 id="review-empty-title">No review history is currently known for this workspace.</h2>
        <p>Entries appear only when tenant-owned review jobs have been durably created.</p>
      </section>
    );
  }
  return (
    <section className="review-section" aria-labelledby="review-list-title">
      <div className="section-heading">
        <div>
          <p className="section-kicker">Newest first</p>
          <h2 id="review-list-title">Persisted review revisions</h2>
        </div>
        <p>Each row identifies one exact repository, pull request, and head revision.</p>
      </div>
      <ol className="review-list">
        {page.reviews.map((review) => <ReviewRow key={reviewKey(review)} review={review} />)}
      </ol>
      {page.hasMore && page.nextCursor ? (
        <nav className="review-pagination" aria-label="Review history pages">
          <Link className="button button-secondary" href={nextPageHref(tenantId, page.nextCursor)}>
            Older reviews
          </Link>
        </nav>
      ) : null}
      <p className="review-footnote">
        History is derived from durable job and publication state. Source patches, prompts,
        provider responses, failure details, and publication payloads are never exposed here.
      </p>
    </section>
  );
}

function ReviewRow({ review }: Readonly<{ review: DashboardReview }>) {
  return (
    <li>
      <div className="review-identity">
        <span className="repository-label">Repository #{review.repositoryId}</span>
        <strong>Pull request #{review.pullRequestNumber}</strong>
        <code title="Exact reviewed head revision">{review.headSha.slice(0, 12)}</code>
      </div>
      <div className="review-outcome">
        <span className={`review-state-badge review-state-${review.state.toLowerCase()}`}>
          {STATE_LABELS[review.state]}
        </span>
        {review.publishableFindingCount !== null ? (
          <span>{review.publishableFindingCount} publishable {review.publishableFindingCount === 1 ? "finding" : "findings"}</span>
        ) : null}
      </div>
      <time dateTime={review.createdAt}>{formatTimestamp(review.createdAt)}</time>
    </li>
  );
}

function ReviewError({ kind }: Readonly<{ kind: ReviewFailureKind }>) {
  const content: Record<ReviewFailureKind, readonly [string, string]> = {
    AUTHORIZATION_FAILED: ["Review access could not be authorized.", "No review information was disclosed."],
    BACKEND_RESPONSE_INVALID: ["Review history could not be verified.", "The response was rejected safely."],
    BACKEND_TIMEOUT: ["The review-history request took too long.", "The request was stopped safely. Try again shortly."],
    BACKEND_UNAVAILABLE: ["Review history is temporarily unavailable.", "Your workspace remains protected. Try again shortly."],
    INVALID_CURSOR: ["That review-history page cannot be opened.", "Return to the newest reviews and try again."],
  };
  const [title, description] = content[kind];
  return (
    <section className="review-state" aria-labelledby="review-error-title">
      <p className="section-kicker">Reviews unavailable</p>
      <h2 id="review-error-title">{title}</h2>
      <p>{description}</p>
      <Link className="button button-secondary" href="/dashboard/reviews">View newest reviews</Link>
    </section>
  );
}

function nextPageHref(tenantId: string, cursor: string): string {
  const parameters = new URLSearchParams({ tenant: tenantId, cursor });
  return `/dashboard/reviews?${parameters.toString()}`;
}

function reviewKey(review: DashboardReview): string {
  return `${review.repositoryId}:${review.pullRequestNumber}:${review.headSha}:${review.createdAt}`;
}

function formatTimestamp(value: string): string {
  return new Intl.DateTimeFormat("en", {
    dateStyle: "medium", timeStyle: "short", timeZone: "UTC",
  }).format(new Date(value)) + " UTC";
}
