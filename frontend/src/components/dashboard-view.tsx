import Link from "next/link";
import type { ReactNode } from "react";

import type { DashboardFailureKind, DashboardState } from "@/lib/dashboard-session-core";
import { selectAuthorizedTenant } from "@/lib/dashboard-selection";

const workflow = [
  ["01", "Pull request", "A signed GitHub event identifies the exact revision."],
  ["02", "Repository context", "Bounded changed code and relevant context are retrieved."],
  ["03", "AI analysis", "Provider-neutral analysis evaluates concrete risks."],
  ["04", "Evidence validation", "Every candidate is checked against the changed lines."],
  ["05", "False-positive suppression", "Weak, duplicate, and low-confidence findings are removed."],
  ["06", "GitHub comments", "Only the remaining high-confidence findings are published."],
] as const;

const navigation = ["Overview", "Repositories", "Reviews", "Usage", "Settings"] as const;
export type DashboardSection = typeof navigation[number];
export type Membership = Readonly<{ tenantId: string; role: "OWNER" | "MEMBER" }>;

export function DashboardView({
  state,
  requestedTenantId = null,
  connectionMessage = null,
}: Readonly<{ state: DashboardState; requestedTenantId?: string | null;
  connectionMessage?: readonly [string, string] | null }>) {
  if (state.status === "unauthenticated") {
    return <UnauthenticatedState />;
  }
  if (state.status === "error") {
    return <DashboardError kind={state.kind} />;
  }
  if (state.onboardingRequired) {
    return <UnboundState connectionMessage={connectionMessage} />;
  }

  const selection = selectAuthorizedTenant(state.memberships, requestedTenantId);
  if (selection.status === "invalid") {
    return <InvalidTenantState />;
  }

  return <OverviewDashboard membership={selection.membership} memberships={selection.memberships}
    connectionMessage={connectionMessage} />;
}

function UnauthenticatedState() {
  return (
    <main id="main-content" className="dashboard-gate">
      <Brand />
      <section className="gate-panel" aria-labelledby="dashboard-title">
        <p className="eyebrow">Protected workspace</p>
        <h1 id="dashboard-title">Sign in to your dashboard</h1>
        <p className="gate-copy">
          Authentication is required before any tenant or product information can be loaded.
        </p>
        <a className="button button-primary" href="/auth/login">Sign in securely</a>
      </section>
    </main>
  );
}

function UnboundState({ connectionMessage }: Readonly<{ connectionMessage: readonly [string, string] | null }>) {
  return (
    <main id="main-content" className="dashboard-gate">
      <div className="gate-header">
        <Brand />
        <a className="text-link" href="/auth/logout">Sign out</a>
      </div>
      <section className="gate-panel onboarding-panel" aria-labelledby="dashboard-title">
        {connectionMessage && <ConnectionNotice message={connectionMessage} />}
        <span className="state-indicator" aria-hidden="true">01</span>
        <p className="eyebrow">Account authenticated</p>
        <h1 id="dashboard-title">Connect your GitHub workspace next</h1>
        <p className="gate-copy">
          Your identity is verified, but no GitHub installation has been securely linked to this
          dashboard. No tenant, repository, review, or usage data is available yet.
        </p>
        <form method="post" action="/github/connect" className="deferred-action">
          <button className="button button-primary" type="submit">Connect GitHub</button>
          <span>GitHub authorization verifies eligible personal installations server-side.</span>
        </form>
        <p className="security-note">
          Review Assistant will never ask you to claim access with a tenant UUID, installation ID,
          repository ID, or organization name.
        </p>
      </section>
    </main>
  );
}

function OverviewDashboard({
  membership,
  memberships,
  connectionMessage,
}: Readonly<{ membership: Membership; memberships: readonly Membership[];
  connectionMessage: readonly [string, string] | null }>) {
  return (
    <DashboardFrame membership={membership} activeSection="Overview">
          {connectionMessage && <ConnectionNotice message={connectionMessage} />}
          <header className="overview-header">
            <div>
              <p className="eyebrow">Overview</p>
              <h1>Signal for the changes that matter.</h1>
              <p className="overview-lede">
                An AI reviewer designed to comment less, validate evidence, and surface the risks
                worth an engineer&apos;s attention.
              </p>
            </div>
            <TenantSelector membership={membership} memberships={memberships} />
          </header>

          <section className="overview-grid" aria-label="Workspace overview">
            <article className="status-panel">
              <div className="section-heading">
                <div>
                  <p className="section-kicker">Workspace access</p>
                  <h2>{tenantLabel(membership.tenantId)}</h2>
                </div>
                <span className="role-badge">{membership.role}</span>
              </div>
              <dl className="status-list">
                <div><dt>Membership</dt><dd>Verified by the backend</dd></div>
                <div><dt>Review engine</dt><dd>Pipeline defined; live status not exposed here</dd></div>
                <div><dt>Operational data</dt><dd>Available in later dashboard milestones</dd></div>
              </dl>
            </article>

            <article className="next-step-panel">
              <p className="section-kicker">Next step</p>
              <h2>Inspect connected repositories</h2>
              <p>
                View the repositories already recorded for this workspace. This overview still
                shows no fabricated repository, review, or usage totals.
              </p>
              <Link className="button button-secondary" href={`/dashboard/repositories?tenant=${membership.tenantId}`}>
                View repositories
              </Link>
            </article>
          </section>

          <section className="workflow-section" aria-labelledby="workflow-title">
            <div className="section-heading">
              <div>
                <p className="section-kicker">Review pipeline</p>
                <h2 id="workflow-title">From pull request to useful signal</h2>
              </div>
              <p>Deterministic boundaries surround probabilistic analysis.</p>
            </div>
            <ol className="workflow-list">
              {workflow.map(([number, title, description]) => (
                <li key={number}>
                  <span className="workflow-number">{number}</span>
                  <div><h3>{title}</h3><p>{description}</p></div>
                </li>
              ))}
            </ol>
          </section>
    </DashboardFrame>
  );
}

export function DashboardFrame({
  membership,
  activeSection,
  children,
}: Readonly<{ membership: Membership; activeSection: DashboardSection; children: ReactNode }>) {
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <Brand />
        <DashboardNavigation activeSection={activeSection} />
        <div className="sidebar-account">
          <span className="account-label">Current workspace</span>
          <strong>{tenantLabel(membership.tenantId)}</strong>
          <span className="role-badge">{membership.role}</span>
          <a className="text-link" href="/auth/logout">Sign out</a>
        </div>
      </aside>
      <div className="workspace">
        <header className="mobile-header">
          <Brand />
          <details className="mobile-navigation">
            <summary>Navigation</summary>
            <DashboardNavigation activeSection={activeSection} />
          </details>
        </header>
        <main id="main-content" className="dashboard-main">
          {children}
        </main>
      </div>
    </div>
  );
}

function ConnectionNotice({ message }: Readonly<{ message: readonly [string, string] }>) {
  return <aside className="connection-notice" aria-live="polite"><strong>{message[0]}</strong><span>{message[1]}</span></aside>;
}

export function TenantSelector({
  membership,
  memberships,
  action = "/dashboard",
}: Readonly<{ membership: Membership; memberships: readonly Membership[]; action?: string }>) {
  if (memberships.length === 1) {
    return (
      <div className="tenant-summary">
        <span>Workspace</span>
        <strong>{tenantLabel(membership.tenantId)}</strong>
      </div>
    );
  }
  return (
    <form className="tenant-selector" method="get" action={action}>
      <label htmlFor="tenant-selection">Workspace</label>
      <div>
        <select id="tenant-selection" name="tenant" defaultValue={membership.tenantId}>
          {memberships.map((entry) => (
            <option key={entry.tenantId} value={entry.tenantId}>
              {tenantLabel(entry.tenantId)} · {entry.role}
            </option>
          ))}
        </select>
        <button className="button button-secondary" type="submit">Switch</button>
      </div>
      <p>The server verifies every selection against your memberships.</p>
    </form>
  );
}

function DashboardNavigation({ activeSection }: Readonly<{ activeSection: DashboardSection }>) {
  return (
    <nav className="dashboard-navigation" aria-label="Primary">
      <ul>
        <li><Link href="/dashboard" aria-current={activeSection === "Overview" ? "page" : undefined}>Overview</Link></li>
        <li><Link href="/dashboard/repositories"
          aria-current={activeSection === "Repositories" ? "page" : undefined}>Repositories</Link></li>
        <li><Link href="/dashboard/reviews"
          aria-current={activeSection === "Reviews" ? "page" : undefined}>Reviews</Link></li>
        <li><Link href="/dashboard/usage"
          aria-current={activeSection === "Usage" ? "page" : undefined}>Usage</Link></li>
        {navigation.filter((item) => !["Overview", "Repositories", "Reviews", "Usage"].includes(item)).map((item) => (
          <li key={item}><span aria-disabled="true">{item}<small>Deferred</small></span></li>
        ))}
      </ul>
    </nav>
  );
}

function InvalidTenantState() {
  return (
    <main id="main-content" className="centered-state">
      <p className="eyebrow">Access not available</p>
      <h1>That workspace cannot be opened.</h1>
      <p className="muted">
        The requested identifier did not match an authorized membership. No tenant information was disclosed.
      </p>
      <Link className="button button-primary" href="/dashboard">Return to your dashboard</Link>
    </main>
  );
}

function DashboardError({ kind }: Readonly<{ kind: DashboardFailureKind }>) {
  const content: Record<DashboardFailureKind, readonly [string, string]> = {
    AUTHORIZATION_FAILED: ["Your dashboard session could not be authorized.", "Sign in again to refresh your protected session."],
    BACKEND_RESPONSE_INVALID: ["The dashboard received an invalid response.", "No unverified data was displayed. Try again shortly."],
    BACKEND_TIMEOUT: ["The dashboard took too long to respond.", "The request was stopped safely. Try again shortly."],
    BACKEND_UNAVAILABLE: ["The dashboard service is temporarily unavailable.", "Your account data remains protected. Try again shortly."],
    SESSION_UNAVAILABLE: ["Your protected session is unavailable.", "Sign in again to continue."],
  };
  const [title, description] = content[kind];
  return (
    <main id="main-content" className="centered-state">
      <p className="eyebrow">Dashboard unavailable</p>
      <h1>{title}</h1>
      <p className="muted">{description}</p>
      <div className="state-actions">
        <Link className="button button-primary" href="/dashboard">Try again</Link>
        <a className="text-link" href="/auth/logout">Sign out</a>
      </div>
    </main>
  );
}

function Brand() {
  return (
    <Link className="brand" href="/">
      <span className="brand-mark" aria-hidden="true">RA</span>
      <span>Review Assistant</span>
    </Link>
  );
}

export function tenantLabel(tenantId: string): string {
  return `Workspace ${tenantId.slice(0, 8).toUpperCase()}`;
}
