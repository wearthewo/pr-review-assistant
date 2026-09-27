package io.prreviewassistant.dashboard.github;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import io.prreviewassistant.identity.TenantMembershipRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcGitHubOwnershipBootstrapStore implements GitHubOwnershipBootstrapStore {
    private final JdbcClient jdbc;

    public JdbcGitHubOwnershipBootstrapStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> findTenantForInstallation(long githubInstallationId) {
        if (githubInstallationId <= 0) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT tenant_id FROM github_installations WHERE github_installation_id = :id")
                .param("id", githubInstallationId).query(UUID.class).optional();
    }

    @Override
    @Transactional
    public MembershipBindingResult bindOwner(UUID tenantId, UUID applicationUserId, Instant now) {
        validateBinding(tenantId, applicationUserId, now);
        return bindOwnerLocked(tenantId, applicationUserId, now);
    }

    @Override
    @Transactional
    public MembershipBindingResult provisionPersonalInstallationOwner(
            long githubInstallationId, UUID applicationUserId, Instant now) {
        if (githubInstallationId <= 0 || applicationUserId == null || now == null) {
            throw new IllegalArgumentException("installation ownership binding input is invalid");
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(:installationId)")
                .param("installationId", githubInstallationId)
                .query((resultSet, row) -> Boolean.TRUE).single();
        UUID tenantId = findTenantForInstallation(githubInstallationId).orElseGet(() -> {
            UUID createdTenantId = UUID.randomUUID();
            OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
            jdbc.sql("INSERT INTO tenants (id, created_at, updated_at) VALUES (:id, :now, :now)")
                    .param("id", createdTenantId).param("now", timestamp).update();
            jdbc.sql("""
                    INSERT INTO github_installations
                      (id, tenant_id, github_installation_id, created_at, updated_at)
                    VALUES (:id, :tenantId, :githubInstallationId, :now, :now)
                    """).param("id", UUID.randomUUID()).param("tenantId", createdTenantId)
                    .param("githubInstallationId", githubInstallationId).param("now", timestamp).update();
            return createdTenantId;
        });
        return bindOwnerLocked(tenantId, applicationUserId, now);
    }

    private MembershipBindingResult bindOwnerLocked(UUID tenantId, UUID applicationUserId, Instant now) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:tenant, 0))")
                .param("tenant", tenantId.toString())
                .query((resultSet, row) -> Boolean.TRUE).single();

        Optional<String> existingRole = jdbc.sql("""
                SELECT role FROM tenant_memberships
                WHERE tenant_id = :tenantId AND user_id = :userId
                """).param("tenantId", tenantId).param("userId", applicationUserId)
                .query(String.class).optional();
        if (existingRole.isPresent()) {
            return MembershipBindingResult.ALREADY_MEMBER;
        }
        long owners = jdbc.sql("""
                SELECT count(*) FROM tenant_memberships
                WHERE tenant_id = :tenantId AND role = 'OWNER'
                """).param("tenantId", tenantId).query(Long.class).single();
        if (owners != 0) {
            return MembershipBindingResult.OWNERSHIP_CONFLICT;
        }
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO tenant_memberships (id, tenant_id, user_id, role, created_at, updated_at)
                VALUES (:id, :tenantId, :userId, :role, :now, :now)
                """).param("id", UUID.randomUUID()).param("tenantId", tenantId)
                .param("userId", applicationUserId).param("role", TenantMembershipRole.OWNER.name())
                .param("now", timestamp).update();
        return MembershipBindingResult.CREATED;
    }

    private static void validateBinding(UUID tenantId, UUID applicationUserId, Instant now) {
        if (tenantId == null || applicationUserId == null || now == null) {
            throw new IllegalArgumentException("installation ownership binding input is invalid");
        }
    }
}
