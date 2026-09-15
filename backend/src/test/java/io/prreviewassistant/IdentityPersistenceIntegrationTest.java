package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.prreviewassistant.identity.ApplicationIdentityStore;
import io.prreviewassistant.identity.ApplicationUser;
import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import io.prreviewassistant.identity.TenantAccessDeniedException;
import io.prreviewassistant.identity.TenantAuthorizationService;
import io.prreviewassistant.tenant.TenantContext;
import io.prreviewassistant.tenant.TenantOwnershipStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {
        "REVIEW_WORKER_ENABLED=false",
        "REVIEW_PUBLICATION_ENABLED=false",
        "DB_JDBC_URL=jdbc:postgresql://unused",
        "DB_USERNAME=unused",
        "DB_PASSWORD=unused",
        "GITHUB_APP_ID=1",
        "GITHUB_PRIVATE_KEY_PATH=unused",
        "GITHUB_WEBHOOK_SECRET=test-secret-that-is-long-enough"
})
@Import(PostgreSqlTestConfiguration.class)
class IdentityPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final AuthenticatedUserIdentity USER_A =
            new AuthenticatedUserIdentity("https://issuer.example/", "subject-a");

    @Autowired
    private ApplicationIdentityStore store;
    @Autowired
    private TenantAuthorizationService authorization;
    @Autowired
    private TenantOwnershipStore ownership;
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM tenant_memberships").update();
        jdbc.sql("DELETE FROM application_users").update();
        jdbc.sql("DELETE FROM tenant_usage_events").update();
        jdbc.sql("DELETE FROM publication_jobs").update();
        jdbc.sql("DELETE FROM review_publications").update();
        jdbc.sql("DELETE FROM review_jobs").update();
        jdbc.sql("DELETE FROM tenant_repositories").update();
        jdbc.sql("DELETE FROM github_installations").update();
        jdbc.sql("DELETE FROM tenants").update();
    }

    @Test
    void identityUsesIssuerAndSubjectAndNeverEmailOrTokens() {
        ApplicationUser first = store.provisionUser(USER_A, NOW);
        ApplicationUser same = store.provisionUser(USER_A, NOW.plusSeconds(10));
        ApplicationUser otherIssuer = store.provisionUser(
                new AuthenticatedUserIdentity("https://other-issuer.example/", "subject-a"), NOW);

        assertThat(same.id()).isEqualTo(first.id());
        assertThat(otherIssuer.id()).isNotEqualTo(first.id());
        assertThat(jdbc.sql("SELECT count(*) FROM application_users").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'application_users'
                  AND column_name IN ('email', 'access_token', 'refresh_token', 'id_token', 'jwt')
                """).query(Long.class).single()).isZero();
        assertThat(USER_A.toString()).isEqualTo("AuthenticatedUserIdentity[externalIdentity=<redacted>]")
                .doesNotContain("subject-a", "issuer.example");
    }

    @Test
    void concurrentFirstLoginConvergesOnOneApplicationUser() throws Exception {
        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(callers)) {
            var futures = java.util.stream.IntStream.range(0, callers).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                go.await();
                return store.provisionUser(USER_A, NOW).id();
            })).toList();
            ready.await();
            go.countDown();
            List<UUID> ids = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            assertThat(ids).allMatch(ids.getFirst()::equals);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM application_users").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void membershipAuthorizesOnlyItsUserAndTenantAndUnknownOrUnownedTenantsFailClosed() {
        TenantContext tenantA = ownership.provision(101, 201, NOW);
        TenantContext tenantB = ownership.provision(102, 202, NOW);
        ApplicationUser userA = store.provisionUser(USER_A, NOW);
        insertMembership(userA.id(), tenantA.tenantId(), "OWNER");

        assertThat(authorization.authorize(USER_A, tenantA.tenantId()).tenantId()).isEqualTo(tenantA.tenantId());
        assertThatThrownBy(() -> authorization.authorize(USER_A, tenantB.tenantId()))
                .isInstanceOf(TenantAccessDeniedException.class)
                .hasMessage("TENANT_ACCESS_DENIED")
                .hasMessageNotContaining(tenantB.tenantId().toString());
        assertThatThrownBy(() -> authorization.authorize(USER_A, UUID.randomUUID()))
                .isInstanceOf(TenantAccessDeniedException.class);
        assertThat(authorization.session(USER_A).memberships()).hasSize(1);
    }

    @Test
    void membershipUniquenessForeignKeysAndControlledRolesAreDatabaseEnforced() {
        TenantContext tenant = ownership.provision(103, 203, NOW);
        ApplicationUser user = store.provisionUser(USER_A, NOW);
        insertMembership(user.id(), tenant.tenantId(), "MEMBER");

        assertThatThrownBy(() -> insertMembership(user.id(), tenant.tenantId(), "OWNER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertMembership(user.id(), tenant.tenantId(), "ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertMembership(UUID.randomUUID(), tenant.tenantId(), "MEMBER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void historicalTenantWithoutMembershipIsNotVisibleToAuthenticatedUser() {
        ownership.provision(104, 204, NOW);
        var session = authorization.session(USER_A);

        assertThat(session.onboardingRequired()).isTrue();
        assertThat(session.memberships()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_memberships").query(Long.class).single()).isZero();
    }

    private void insertMembership(UUID userId, UUID tenantId, String role) {
        OffsetDateTime timestamp = NOW.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO tenant_memberships (id, tenant_id, user_id, role, created_at, updated_at)
                VALUES (:id, :tenantId, :userId, :role, :now, :now)
                """).param("id", UUID.randomUUID()).param("tenantId", tenantId)
                .param("userId", userId).param("role", role).param("now", timestamp).update();
    }
}
