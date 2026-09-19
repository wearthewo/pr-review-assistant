import type { Metadata } from "next";

import { UsageView } from "@/components/usage-view";
import { loadUsageDashboard } from "@/lib/server/dashboard-usage";

export const metadata: Metadata = {
  title: "Usage",
  robots: { index: false, follow: false },
};

interface UsagePageProps {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}

export default async function UsagePage({ searchParams }: UsagePageProps) {
  const parameters = await searchParams;
  const tenant = singleParameter(parameters.tenant);
  return <UsageView state={await loadUsageDashboard(tenant)} />;
}

function singleParameter(value: string | string[] | undefined): string | null {
  if (value === undefined) return null;
  return typeof value === "string" ? value : "";
}
