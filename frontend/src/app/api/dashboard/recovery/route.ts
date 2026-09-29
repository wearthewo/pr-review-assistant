import { dashboardRecoveryStatus } from "@/lib/dashboard-recovery-core";
import { loadDashboardState } from "@/lib/server/dashboard-session";

export const dynamic = "force-dynamic";
export const revalidate = 0;

export async function GET() {
  const status = dashboardRecoveryStatus(await loadDashboardState());
  return new Response(null, {
    status,
    headers: {
      "Cache-Control": "no-store, max-age=0",
    },
  });
}
