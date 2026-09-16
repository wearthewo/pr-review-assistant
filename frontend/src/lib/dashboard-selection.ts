import type { DashboardMembership } from "@/lib/dashboard-session-core";

export type TenantSelection =
  | { status: "selected"; membership: DashboardMembership; memberships: readonly DashboardMembership[] }
  | { status: "invalid" };

export function selectAuthorizedTenant(
  memberships: readonly DashboardMembership[],
  requestedTenantId: string | null,
): TenantSelection {
  const ordered = [...memberships].sort((left, right) => left.tenantId.localeCompare(right.tenantId));
  if (ordered.length === 0) {
    return { status: "invalid" };
  }
  if (requestedTenantId === null) {
    return { status: "selected", membership: ordered[0], memberships: ordered };
  }
  const selected = ordered.find(({ tenantId }) => tenantId === requestedTenantId);
  return selected === undefined
    ? { status: "invalid" }
    : { status: "selected", membership: selected, memberships: ordered };
}
