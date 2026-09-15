import Link from "next/link";

import type { DashboardState } from "@/lib/dashboard-session-core";

export function DashboardView({ state }: Readonly<{ state: DashboardState }>) {
  return (
    <main id="main-content" className="dashboard-shell">
      <header className="dashboard-header">
        <Link className="brand" href="/">
          <span className="brand-mark" aria-hidden="true">RA</span>
          <span>Review Assistant</span>
        </Link>
        <span className="status-chip">Authentication foundation</span>
      </header>

      <section className="empty-panel" aria-labelledby="dashboard-title">
        <p className="eyebrow">M13B identity boundary</p>
        <h1 id="dashboard-title">Dashboard access</h1>
        {state.status === "unauthenticated" ? (
          <>
            <p>Sign in to establish a protected application session.</p>
            <p className="muted">No tenant or product data is available before authentication.</p>
            <a className="button button-primary" href="/auth/login">Sign in</a>
          </>
        ) : state.onboardingRequired ? (
          <>
            <p>Authentication succeeded.</p>
            <p className="muted">
              This account is not securely linked to a tenant. No tenant data is available until a
              future server-verified GitHub ownership flow creates a membership.
            </p>
            <a className="text-link" href="/auth/logout">Sign out</a>
          </>
        ) : (
          <>
            <p>Authentication and tenant membership verification succeeded.</p>
            <ul className="membership-list">
              {state.memberships.map((membership) => (
                <li key={membership.tenantId}>
                  <span>Authorized tenant</span><strong>{membership.role}</strong>
                </li>
              ))}
            </ul>
            <a className="text-link" href="/auth/logout">Sign out</a>
          </>
        )}
        <Link className="text-link" href="/">Return to the product overview</Link>
      </section>
    </main>
  );
}
