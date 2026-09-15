import type { Metadata } from "next";
import { DashboardView } from "@/components/dashboard-view";
import { loadDashboardState } from "@/lib/server/dashboard-session";

export const metadata: Metadata = {
  title: "Dashboard foundation",
  robots: { index: false, follow: false },
};

export default async function DashboardPage() {
  const state = await loadDashboardState();
  return <DashboardView state={state} />;
}
