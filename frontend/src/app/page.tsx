import Link from "next/link";

export default function HomePage() {
  return (
    <main id="main-content" className="page-shell">
      <nav className="topbar" aria-label="Primary navigation">
        <Link className="brand" href="/" aria-label="Review Assistant home">
          <span className="brand-mark" aria-hidden="true">RA</span>
          <span>Review Assistant</span>
        </Link>
        <Link className="text-link" href="/dashboard">Dashboard foundation</Link>
      </nav>

      <section className="hero" aria-labelledby="hero-title">
        <p className="eyebrow">Production-minded pull request review</p>
        <h1 id="hero-title">Signal for the changes that matter.</h1>
        <p className="hero-copy">
          Deterministic analysis and carefully constrained AI work together to surface a small set
          of high-confidence findings for engineering teams.
        </p>
        <div className="hero-actions">
          <Link className="button button-primary" href="/dashboard">View the product shell</Link>
          <a className="button button-secondary" href="https://github.com" rel="noreferrer">
            GitHub
          </a>
        </div>
      </section>

      <section className="principles" aria-labelledby="principles-title">
        <div>
          <p className="eyebrow">Foundation</p>
          <h2 id="principles-title">Built around trustworthy boundaries.</h2>
        </div>
        <ul className="principle-grid">
          <li><strong>Exact revisions</strong><span>Review work stays tied to immutable pull request state.</span></li>
          <li><strong>Tenant isolation</strong><span>Identity belongs to authenticated server context, never browser input.</span></li>
          <li><strong>Quiet by design</strong><span>Fewer supported findings beat a wall of low-confidence comments.</span></li>
        </ul>
      </section>
    </main>
  );
}
