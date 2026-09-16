import type { Metadata } from "next";

import { RepositoriesView } from "@/components/repositories-view";
import { loadRepositoriesDashboard } from "@/lib/server/dashboard-repositories";

export const metadata: Metadata = {
  title: "Repositories",
  robots: { index: false, follow: false },
};

interface RepositoriesPageProps {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}

export default async function RepositoriesPage({ searchParams }: RepositoriesPageProps) {
  const requested = (await searchParams).tenant;
  const requestedTenantId = typeof requested === "string" ? requested : requested === undefined ? null : "";
  return <RepositoriesView state={await loadRepositoriesDashboard(requestedTenantId)} />;
}
