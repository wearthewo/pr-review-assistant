import {
  DashboardFrame,
  DashboardView,
  TenantSelector,
} from "@/components/dashboard-view";
import type { ReviewAnalysisUsage, UsageFailureKind } from "@/lib/dashboard-usage-core";
import { usagePercentage } from "@/lib/dashboard-usage-core";
import { selectAuthorizedTenant } from "@/lib/dashboard-selection";
import type { UsageDashboardState } from "@/lib/server/dashboard-usage";

export function UsageView({ state }: Readonly<{ state: UsageDashboardState }>) {
  if (state.status === "gate") return <DashboardView state={state.dashboard} />;
  if (state.status === "invalid-tenant") {
    return <DashboardView state={state.dashboard} requestedTenantId="invalid-selection" />;
  }
  const selection = selectAuthorizedTenant(state.dashboard.memberships, state.selectedTenantId);
  if (selection.status === "invalid") {
    return <DashboardView state={state.dashboard} requestedTenantId="invalid-selection" />;
  }
  return (
    <DashboardFrame membership={selection.membership} activeSection="Usage">
      <header className="usage-header">
        <div>
          <p className="eyebrow">Usage</p>
          <h1>Review-analysis quota</h1>
          <p className="overview-lede">
            Authoritative current-period quota usage from durable tenant accounting.
          </p>
        </div>
        <TenantSelector membership={selection.membership} memberships={selection.memberships}
          action="/dashboard/usage" />
      </header>
      {state.status === "usage-error"
        ? <UsageError kind={state.kind} />
        : <UsagePanel usage={state.usage.reviewAnalysis} />}
    </DashboardFrame>
  );
}

export function UsagePanel({ usage }: Readonly<{ usage: ReviewAnalysisUsage }>) {
  const percentage = usagePercentage(usage);
  const exhausted = usage.used >= usage.limit;
  return (
    <section className="usage-panel" aria-labelledby="usage-title">
      <div className="section-heading">
        <div>
          <p className="section-kicker">{formatMonth(usage.periodStart)}</p>
          <h2 id="usage-title">Review analysis</h2>
        </div>
        <span className={`quota-state ${exhausted ? "quota-exhausted" : ""}`}>
          {exhausted ? "Quota exhausted" : `${usage.remaining} remaining`}
        </span>
      </div>
      <p className="quota-total">
        <strong>{usage.used}</strong> of <strong>{usage.limit}</strong> review-analysis quota units used
      </p>
      <progress aria-label="Review-analysis quota used"
        value={Math.min(usage.used, usage.limit)} max={usage.limit} />
      <div className="quota-details">
        <span>{percentage}% of the current quota</span>
        <span>{usage.remaining} remaining</span>
      </div>
      <dl className="usage-period">
        <div><dt>Current period</dt><dd>{formatPeriod(usage.periodStart, usage.periodEnd)}</dd></div>
        <div><dt>Accounting boundary</dt><dd>UTC, start inclusive and end exclusive</dd></div>
      </dl>
      <p className="usage-note">
        Usage includes reserved and consumed review-analysis units. Released reservations do not count.
        Reserved usage can include analyses whose provider outcome is uncertain; it does not mean a review was published.
      </p>
    </section>
  );
}

function UsageError({ kind }: Readonly<{ kind: UsageFailureKind }>) {
  const content: Record<UsageFailureKind, readonly [string, string]> = {
    AUTHORIZATION_FAILED: ["Usage access could not be authorized.", "No tenant usage information was disclosed."],
    BACKEND_RESPONSE_INVALID: ["Usage information could not be verified.", "The response was rejected safely."],
    BACKEND_TIMEOUT: ["The usage request took too long.", "The request was stopped safely. Try again shortly."],
    BACKEND_UNAVAILABLE: ["Usage information is temporarily unavailable.", "Your workspace remains protected. Try again shortly."],
  };
  const [title, description] = content[kind];
  return (
    <section className="usage-state" aria-labelledby="usage-error-title">
      <p className="section-kicker">Usage unavailable</p>
      <h2 id="usage-error-title">{title}</h2>
      <p>{description}</p>
    </section>
  );
}

function formatMonth(value: string): string {
  return new Intl.DateTimeFormat("en", { month: "long", year: "numeric", timeZone: "UTC" })
    .format(new Date(value));
}

function formatPeriod(start: string, end: string): string {
  const formatter = new Intl.DateTimeFormat("en", {
    month: "short", day: "numeric", year: "numeric", timeZone: "UTC",
  });
  return `${formatter.format(new Date(start))} – ${formatter.format(new Date(end))} UTC`;
}
