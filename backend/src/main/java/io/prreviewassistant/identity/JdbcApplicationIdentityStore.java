package io.prreviewassistant.identity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcApplicationIdentityStore implements ApplicationIdentityStore {
    private final JdbcClient jdbc;

    public JdbcApplicationIdentityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ApplicationUser provisionUser(AuthenticatedUserIdentity identity, Instant now) {
        if (identity == null || now == null) {
            throw new IllegalArgumentException("authenticated user provisioning input is invalid");
        }
        OffsetDateTime timestamp = utc(now);
        jdbc.sql("""
                INSERT INTO application_users
                  (id, auth_issuer, auth_subject, created_at, updated_at)
                VALUES (:id, :issuer, :subject, :now, :now)
                ON CONFLICT (auth_issuer, auth_subject) DO NOTHING
                """).param("id", UUID.randomUUID())
                .param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .param("now", timestamp)
                .update();

        return jdbc.sql("""
                SELECT id, created_at, updated_at
                FROM application_users
                WHERE auth_issuer = :issuer AND auth_subject = :subject
                """).param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .query((resultSet, row) -> new ApplicationUser(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    @Override
    public List<TenantMembership> findMemberships(UUID applicationUserId) {
        if (applicationUserId == null) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT id, tenant_id, user_id, role, created_at, updated_at
                FROM tenant_memberships
                WHERE user_id = :userId
                ORDER BY tenant_id, id
                """).param("userId", applicationUserId)
                .query((resultSet, row) -> membership(resultSet))
                .list();
    }

    @Override
    public Optional<AuthorizedTenantContext> authorize(UUID applicationUserId, UUID tenantId) {
        if (applicationUserId == null || tenantId == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT user_id, tenant_id, role
                FROM tenant_memberships
                WHERE user_id = :userId AND tenant_id = :tenantId
                """).param("userId", applicationUserId)
                .param("tenantId", tenantId)
                .query((resultSet, row) -> new AuthorizedTenantContext(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getObject("tenant_id", UUID.class),
                        TenantMembershipRole.valueOf(resultSet.getString("role"))))
                .optional();
    }

    private static TenantMembership membership(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new TenantMembership(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                TenantMembershipRole.valueOf(resultSet.getString("role")),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
