import Link from "next/link";

export default function NotFound() {
  return (
    <main id="main-content" className="centered-state">
      <p className="eyebrow">404</p>
      <h1>That page does not exist.</h1>
      <p className="muted">Check the address or return to the product overview.</p>
      <Link className="button button-primary" href="/">Return home</Link>
    </main>
  );
}
