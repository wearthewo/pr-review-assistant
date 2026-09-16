import Link from "next/link";

import { DashboardFrame, DashboardView, TenantSelector, tenantLabel } from "@/components/dashboard-view";
import type { DashboardRepositoryPage, RepositoryFailureKind } from "@/lib/dashboard-repositories-core";
import { selectAuthorizedTenant } from "@/lib/dashboard-selection";
import type { RepositoriesDashboardState } from "@/lib/server/dashboard-repositories";

export function RepositoriesView({ state }: Readonly<{ state: RepositoriesDashboardState }>) {
  if (state.status === "gate") return <DashboardView state={state.dashboard} />;
  if (state.status === "invalid-tenant") {
    return <DashboardView state={state.dashboard} requestedTenantId="invalid-selection" />;
  }
  const selection = selectAuthorizedTenant(state.dashboard.memberships, state.selectedTenantId);
  if (selection.status === "invalid") {
    return <DashboardView state={state.dashboard} requestedTenantId="invalid-selection" />;
  }
  return (
    <DashboardFrame membership={selection.membership} activeSection="Repositories">
      <header className="repositories-header">
        <div>
          <p className="eyebrow">Repositories</p>
          <h1>Connected repository identities</h1>
          <p className="overview-lede">
            A read-only view of repositories already recorded for this workspace.
          </p>
        </div>
        <TenantSelector membership={selection.membership} memberships={selection.memberships}
          action="/dashboard/repositories" />
      </header>
      {state.status === "repository-error"
        ? <RepositoryError kind={state.kind} />
        : <RepositoryList page={state.repositories} tenantId={state.selectedTenantId} />}
    </DashboardFrame>
  );
}

export function RepositoryList({ page, tenantId }: Readonly<{
  page: DashboardRepositoryPage; tenantId: string;
}>) {
  if (page.repositories.length === 0) {
    return (
      <section className="repository-state" aria-labelledby="repository-empty-title">
        <p className="section-kicker">No persisted repositories</p>
        <h2 id="repository-empty-title">No repositories are currently known for this workspace.</h2>
        <p>
          Repositories appear after signed GitHub activity provisions them through the existing
          installation ownership flow. This page does not query GitHub or invent sample data.
        </p>
      </section>
    );
  }
  return (
    <section className="repository-section" aria-labelledby="repository-list-title">
      <div className="section-heading">
        <div>
          <p className="section-kicker">{tenantLabel(tenantId)}</p>
          <h2 id="repository-list-title">Persisted repositories</h2>
        </div>
        <p>Numeric GitHub IDs are durable identity. Display names are not stored yet.</p>
      </div>
      <ol className="repository-list">
        {page.repositories.map((repository) => (
          <li key={repository.repositoryId}>
            <div>
              <span className="repository-label">GitHub repository</span>
              <strong>#{repository.repositoryId}</strong>
            </div>
            <div>
              <span className="repository-label">First recorded</span>
              <time dateTime={repository.connectedAt}>{repository.connectedAt.slice(0, 10)} UTC</time>
            </div>
          </li>
        ))}
      </ol>
      {page.truncated && (
        <p className="repository-limit" role="status">
          Showing the first 100 repositories in numeric ID order. Additional repositories are not displayed.
        </p>
      )}
      <p className="repository-footnote">
        Review behavior is controlled by <code>.reviewbot.yml</code> at each pull request&apos;s exact base revision.
        No current configuration or activation status is inferred here.
      </p>
    </section>
  );
}

function RepositoryError({ kind }: Readonly<{ kind: RepositoryFailureKind }>) {
  const content: Record<RepositoryFailureKind, readonly [string, string]> = {
    AUTHORIZATION_FAILED: ["Repository access could not be authorized.", "No repository information was disclosed."],
    BACKEND_RESPONSE_INVALID: ["Repository data could not be verified.", "The response was rejected safely."],
    BACKEND_TIMEOUT: ["The repository request took too long.", "The request was stopped safely. Try again shortly."],
    BACKEND_UNAVAILABLE: ["Repositories are temporarily unavailable.", "Your workspace remains protected. Try again shortly."],
  };
  const [title, description] = content[kind];
  return (
    <section className="repository-state" aria-labelledby="repository-error-title">
      <p className="section-kicker">Repositories unavailable</p>
      <h2 id="repository-error-title">{title}</h2>
      <p>{description}</p>
      <Link className="button button-secondary" href="/dashboard/repositories">Try again</Link>
    </section>
  );
}
