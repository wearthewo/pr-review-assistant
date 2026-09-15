package io.prreviewassistant.identity;

import java.util.List;
import java.util.Objects;

public record DashboardSession(ApplicationUser user, List<TenantMembership> memberships) {
    public DashboardSession {
        Objects.requireNonNull(user, "user is required");
        memberships = List.copyOf(Objects.requireNonNull(memberships, "memberships are required"));
    }

    public boolean onboardingRequired() {
        return memberships.isEmpty();
    }
}
