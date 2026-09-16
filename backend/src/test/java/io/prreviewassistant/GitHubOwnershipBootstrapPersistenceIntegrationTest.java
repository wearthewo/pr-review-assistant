package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import io.prreviewassistant.dashboard.github.GitHubConnectionStateStore;
import io.prreviewassistant.dashboard.github.GitHubOwnershipBootstrapStore;
import io.prreviewassistant.identity.ApplicationIdentityStore;
import io.prreviewassistant.identity.ApplicationUser;
import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import io.prreviewassistant.tenant.TenantContext;
import io.prreviewassistant.tenant.TenantOwnershipStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {"REVIEW_WORKER_ENABLED=false", "REVIEW_PUBLICATION_ENABLED=false",
        "DB_JDBC_URL=jdbc:postgresql://unused", "DB_USERNAME=unused", "DB_PASSWORD=unused",
        "GITHUB_APP_ID=1", "GITHUB_PRIVATE_KEY_PATH=unused",
        "GITHUB_WEBHOOK_SECRET=test-secret-that-is-long-enough"})
@Import(PostgreSqlTestConfiguration.class)
class GitHubOwnershipBootstrapPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    @Autowired GitHubConnectionStateStore states;
    @Autowired GitHubOwnershipBootstrapStore bootstrap;
    @Autowired ApplicationIdentityStore identities;
    @Autowired TenantOwnershipStore tenants;
    @Autowired JdbcClient jdbc;

    @BeforeEach @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM github_connection_states").update();
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
    void stateIsHashedUserBoundExpiringAndSingleUse() throws Exception {
        ApplicationUser first = user("first");
        ApplicationUser other = user("other");
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("raw-secret-state".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        states.create(hash, first.id(), "v".repeat(43), NOW.plusSeconds(60), NOW);
        assertThat(jdbc.sql("SELECT state_hash FROM github_connection_states").query(String.class).single().trim())
                .isEqualTo(hash).doesNotContain("raw-secret-state");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name IN ('github_connection_states', 'application_users', 'tenant_memberships')
                  AND column_name IN ('access_token', 'refresh_token', 'github_token', 'authorization_code')
                """).query(Long.class).single()).isZero();
        assertThat(states.consume(hash, other.id(), NOW.plusSeconds(1))).isEmpty();
        assertThat(states.consume(hash, first.id(), NOW.plusSeconds(61))).isEmpty();
        assertThat(states.consume(hash, first.id(), NOW.plusSeconds(2))).contains("v".repeat(43));
        assertThat(states.consume(hash, first.id(), NOW.plusSeconds(3))).isEmpty();
    }

    @Test
    void ownerBindingIsIdempotentAndOtherOwnerFailsClosed() {
        TenantContext context = tenants.provision(123, 456, NOW);
        ApplicationUser first = user("first");
        ApplicationUser other = user("other");
        assertThat(bootstrap.findTenantForInstallation(123)).contains(context.tenantId());
        assertThat(bootstrap.findTenantForInstallation(999)).isEmpty();
        assertThat(bootstrap.bindOwner(context.tenantId(), first.id(), NOW))
                .isEqualTo(GitHubOwnershipBootstrapStore.MembershipBindingResult.CREATED);
        assertThat(bootstrap.bindOwner(context.tenantId(), first.id(), NOW.plusSeconds(1)))
                .isEqualTo(GitHubOwnershipBootstrapStore.MembershipBindingResult.ALREADY_MEMBER);
        assertThat(bootstrap.bindOwner(context.tenantId(), other.id(), NOW.plusSeconds(2)))
                .isEqualTo(GitHubOwnershipBootstrapStore.MembershipBindingResult.OWNERSHIP_CONFLICT);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_memberships").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentOwnerBindingConvergesWithoutDuplicateMemberships() throws Exception {
        TenantContext context = tenants.provision(123, 456, NOW);
        ApplicationUser user = user("first");
        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(callers)) {
            var futures = java.util.stream.IntStream.range(0, callers).mapToObj(index -> executor.submit(() -> {
                ready.countDown(); go.await(); return bootstrap.bindOwner(context.tenantId(), user.id(), NOW);
            })).toList();
            ready.await(); go.countDown();
            for (var future : futures) assertThat(future.get()).isIn(
                    GitHubOwnershipBootstrapStore.MembershipBindingResult.CREATED,
                    GitHubOwnershipBootstrapStore.MembershipBindingResult.ALREADY_MEMBER);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_memberships").query(Long.class).single()).isEqualTo(1);
    }

    private ApplicationUser user(String subject) {
        return identities.provisionUser(new AuthenticatedUserIdentity("https://issuer.example/", subject), NOW);
    }
}
