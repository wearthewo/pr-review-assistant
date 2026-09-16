package io.prreviewassistant.dashboard.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDashboardRepositoryStore implements DashboardRepositoryStore {
    private final JdbcClient jdbc;

    public JdbcDashboardRepositoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RepositoryRecord> findByTenant(UUID tenantId, int limit) {
        if (tenantId == null || limit < 1 || limit > DashboardRepositoryService.QUERY_LIMIT) {
            throw new IllegalArgumentException("dashboard repository query is invalid");
        }
        return jdbc.sql("""
                SELECT github_repository_id, created_at
                FROM tenant_repositories
                WHERE tenant_id = :tenantId
                ORDER BY github_repository_id
                LIMIT :limit
                """).param("tenantId", tenantId).param("limit", limit)
                .query((resultSet, row) -> new RepositoryRecord(
                        resultSet.getLong("github_repository_id"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
