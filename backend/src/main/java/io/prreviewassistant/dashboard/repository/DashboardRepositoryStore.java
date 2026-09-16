package io.prreviewassistant.dashboard.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DashboardRepositoryStore {
    List<RepositoryRecord> findByTenant(UUID tenantId, int limit);

    record RepositoryRecord(long githubRepositoryId, Instant connectedAt) {
        public RepositoryRecord {
            if (githubRepositoryId <= 0 || connectedAt == null) {
                throw new IllegalArgumentException("dashboard repository record is invalid");
            }
        }
    }
}
