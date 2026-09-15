export default function Loading() {
  return (
    <main id="main-content" className="centered-state" aria-live="polite" aria-busy="true">
      <div className="loading-mark" aria-hidden="true" />
      <p>Loading Review Assistant…</p>
    </main>
  );
}
