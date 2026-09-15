import type { Metadata } from "next";
import Link from "next/link";

import { DisplayText } from "@/components/display-text";

export const metadata: Metadata = {
  title: "Dashboard foundation",
  robots: { index: false, follow: false },
};

export default function DashboardPage() {
  return (
    <main id="main-content" className="dashboard-shell">
      <header className="dashboard-header">
        <Link className="brand" href="/">
          <span className="brand-mark" aria-hidden="true">RA</span>
          <span>Review Assistant</span>
        </Link>
        <span className="status-chip">Foundation only</span>
      </header>

      <section className="empty-panel" aria-labelledby="dashboard-title">
        <p className="eyebrow">M13A frontend boundary</p>
        <h1 id="dashboard-title">Dashboard shell</h1>
        <p>
          <DisplayText value="Authentication and tenant authorization are not implemented yet." />
        </p>
        <p className="muted">
          This route exposes no tenant, repository, review, publication, or usage data. M13B will
          establish authenticated server-side context before product data is introduced.
        </p>
        <Link className="text-link" href="/">Return to the product overview</Link>
      </section>
    </main>
  );
}
