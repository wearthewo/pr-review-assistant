"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import { probeDashboardRecovery, startDashboardRecovery } from "@/lib/dashboard-recovery-core";

export function DashboardRecovery() {
  const router = useRouter();
  const [cycle, setCycle] = useState(0);
  const [exhausted, setExhausted] = useState(false);

  useEffect(() => {
    const recovery = startDashboardRecovery({
      probe: (signal) => probeDashboardRecovery(fetch, signal),
      onReady: () => router.refresh(),
      onTerminal: () => router.refresh(),
      onExhausted: () => setExhausted(true),
    });
    return () => recovery.cancel();
  }, [cycle, router]);

  if (exhausted) {
    return <DashboardRecoveryUnavailable onRetry={() => {
      setExhausted(false);
      setCycle((value) => value + 1);
    }} />;
  }

  return <DashboardRecoveryStarting />;
}

export function DashboardRecoveryStarting() {
  return (
    <main id="main-content" className="centered-state" aria-live="polite" aria-busy="true">
      <div className="loading-mark" aria-hidden="true" />
      <p className="eyebrow">Attempting to reconnect</p>
      <h1>Starting PullSage</h1>
      <p className="muted">
        PullSage is starting up. This may take a minute or two. You can keep this page open—we&apos;ll continue automatically.
      </p>
    </main>
  );
}

export function DashboardRecoveryUnavailable({ onRetry }: Readonly<{ onRetry(): void }>) {
  return (
    <main id="main-content" className="centered-state">
      <p className="eyebrow">Dashboard unavailable</p>
      <h1>The dashboard service is temporarily unavailable.</h1>
      <p className="muted">PullSage could not reconnect within the recovery window. Your account data remains protected.</p>
      <div className="state-actions">
        <button className="button button-primary" type="button" onClick={onRetry}>Try again</button>
        <a className="text-link" href="/auth/logout">Sign out</a>
      </div>
    </main>
  );
}
