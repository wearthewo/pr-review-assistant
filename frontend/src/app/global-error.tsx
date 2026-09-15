"use client";

export default function GlobalError({ reset }: Readonly<{ error: Error & { digest?: string }; reset: () => void }>) {
  return (
    <html lang="en">
      <body>
        <main id="main-content" className="centered-state">
          <p className="eyebrow">Application interrupted</p>
          <h1>Review Assistant could not load.</h1>
          <p>No sensitive error detail is shown here.</p>
          <button className="button button-primary" type="button" onClick={reset}>Try again</button>
        </main>
      </body>
    </html>
  );
}
