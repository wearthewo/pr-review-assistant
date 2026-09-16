import type { Metadata } from "next";
import { DashboardView } from "@/components/dashboard-view";
import { connectionMessage } from "@/lib/github-connection-core";
import { loadDashboardState } from "@/lib/server/dashboard-session";

export const metadata: Metadata = {
  title: "Overview",
  robots: { index: false, follow: false },
};

interface DashboardPageProps {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}

export default async function DashboardPage({ searchParams }: DashboardPageProps) {
  const requested = (await searchParams).tenant;
  const connection = (await searchParams).connection;
  const state = await loadDashboardState();
  return (
    <DashboardView
      state={state}
      requestedTenantId={typeof requested === "string" ? requested : requested === undefined ? null : ""}
      connectionMessage={connectionMessage(typeof connection === "string" ? connection : null)}
    />
  );
}
