import type { Metadata } from "next";

import { ReviewsView } from "@/components/reviews-view";
import { loadReviewsDashboard } from "@/lib/server/dashboard-reviews";

export const metadata: Metadata = {
  title: "Reviews",
  robots: { index: false, follow: false },
};

interface ReviewsPageProps {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}

export default async function ReviewsPage({ searchParams }: ReviewsPageProps) {
  const parameters = await searchParams;
  const tenant = singleParameter(parameters.tenant);
  const cursor = singleParameter(parameters.cursor);
  return <ReviewsView state={await loadReviewsDashboard(tenant, cursor)} />;
}

function singleParameter(value: string | string[] | undefined): string | null {
  if (value === undefined) return null;
  return typeof value === "string" ? value : "";
}
