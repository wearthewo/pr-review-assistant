"use client";

export default function ErrorPage({ reset }: Readonly<{ error: Error & { digest?: string }; reset: () => void }>) {
  return (
    <main id="main-content" className="centered-state">
      <p className="eyebrow">Request interrupted</p>
      <h1>Something went wrong.</h1>
      <p className="muted">No sensitive error detail is shown here.</p>
      <button className="button button-primary" type="button" onClick={reset}>Try again</button>
    </main>
  );
}
