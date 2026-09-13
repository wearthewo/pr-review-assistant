package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.prreviewassistant.tenant.GitHubInstallationOwnership;
import io.prreviewassistant.tenant.Tenant;
import io.prreviewassistant.tenant.TenantContext;
import io.prreviewassistant.tenant.TenantOwnershipError;
import io.prreviewassistant.tenant.TenantOwnershipException;
import io.prreviewassistant.tenant.TenantOwnershipStore;
import io.prreviewassistant.tenant.TenantRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
class TenantOwnershipPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    @Autowired
    private TenantOwnershipStore store;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM publication_jobs").update();
        jdbc.sql("DELETE FROM review_publications").update();
        jdbc.sql("DELETE FROM review_jobs").update();
        jdbc.sql("DELETE FROM tenant_repositories").update();
        jdbc.sql("DELETE FROM github_installations").update();
        jdbc.sql("DELETE FROM tenants").update();
    }

    @Test
    void firstUseCreatesOneTenantInstallationAndRepositoryWithStableIdentifiersAndTimes() {
        TenantContext first = store.provision(101, 202, NOW);
        TenantContext second = store.provision(101, 202, NOW.plusSeconds(30));

        assertThat(second).isEqualTo(first);
        assertThat(store.resolve(101, 202)).contains(first);
        assertThat(store.resolve(101, 999)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM tenants").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM github_installations").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_repositories").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT created_at FROM tenants WHERE id=:id")
                .param("id", first.tenantId()).query(OffsetDateTime.class).single().toInstant()).isEqualTo(NOW);
        assertThat(jdbc.sql("SELECT created_at FROM github_installations WHERE id=:id")
                .param("id", first.installationId()).query(OffsetDateTime.class).single().toInstant()).isEqualTo(NOW);
        assertThat(jdbc.sql("SELECT created_at FROM tenant_repositories WHERE id=:id")
                .param("id", first.repositoryId()).query(OffsetDateTime.class).single().toInstant()).isEqualTo(NOW);
    }

    @Test
    void concurrentFirstUseResolvesOneDurableOwnershipMapping() throws Exception {
        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(callers)) {
            var futures = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        go.await();
                        return store.provision(303, 404, NOW);
                    })).toList();
            ready.await();
            go.countDown();
            List<TenantContext> contexts = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(contexts).allMatch(contexts.getFirst()::equals);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM tenants").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM github_installations").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_repositories").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void unexpectedRepositoryReassignmentFailsSafelyAndRollsBackNewInstallation() {
        TenantContext original = store.provision(501, 601, NOW);

        assertThatThrownBy(() -> store.provision(502, 601, NOW.plusSeconds(1)))
                .isInstanceOfSatisfying(TenantOwnershipException.class, exception ->
                        assertThat(exception.error())
                                .isEqualTo(TenantOwnershipError.TENANT_REPOSITORY_OWNERSHIP_MISMATCH))
                .hasMessage("TENANT_REPOSITORY_OWNERSHIP_MISMATCH")
                .hasMessageNotContaining(original.tenantId().toString());
        assertThat(jdbc.sql("SELECT count(*) FROM tenants").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM github_installations").query(Long.class).single()).isEqualTo(1);
        assertThat(store.resolve(501, 601)).contains(original);
        assertThat(store.resolve(502, 601)).isEmpty();
    }

    @Test
    void compositeForeignKeyRejectsRepositoryAttachedAcrossTenants() {
        TenantContext first = store.provision(701, 801, NOW);
        TenantContext second = store.provision(702, 802, NOW);

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO tenant_repositories
                  (id, tenant_id, installation_id, github_repository_id, created_at, updated_at)
                VALUES (:id, :tenantId, :installationId, 803, :now, :now)
                """).param("id", UUID.randomUUID()).param("tenantId", first.tenantId())
                .param("installationId", second.installationId())
                .param("now", NOW.atOffset(ZoneOffset.UTC)).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsCrossTenantReviewJobAndPublicationAssociations() {
        TenantContext tenantA = store.provision(801, 901, NOW);
        TenantContext tenantB = store.provision(802, 902, NOW);
        OffsetDateTime timestamp = NOW.atOffset(ZoneOffset.UTC);

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO review_jobs
                  (id, status, attempts, max_attempts, next_attempt_at,
                   github_installation_id, github_repository_id,
                   github_pull_request_number, github_head_sha,
                   tenant_id, tenant_repository_id, created_at, updated_at)
                VALUES (:id, 'READY', 0, 3, :now, 801, 901, 1, :sha,
                        :tenantId, :tenantRepositoryId, :now, :now)
                """).param("id", UUID.randomUUID()).param("now", timestamp)
                .param("sha", "a".repeat(40)).param("tenantId", tenantA.tenantId())
                .param("tenantRepositoryId", tenantB.repositoryId()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID analysisJobId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO review_jobs
                  (id, status, attempts, max_attempts, next_attempt_at,
                   tenant_id, tenant_repository_id, created_at, updated_at)
                VALUES (:id, 'READY', 0, 3, :now, :tenantId, :tenantRepositoryId, :now, :now)
                """).param("id", analysisJobId).param("now", timestamp)
                .param("tenantId", tenantA.tenantId())
                .param("tenantRepositoryId", tenantA.repositoryId()).update();

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO review_publications
                  (id, analysis_job_id, tenant_id, tenant_repository_id,
                   github_installation_id, github_repository_id,
                   github_repository_owner, github_repository_name, github_pull_request_number,
                   github_head_sha, publication_key, payload_version, finding_count, payload,
                   status, created_at, updated_at)
                VALUES (:id, :analysisJobId, :tenantId, :tenantRepositoryId,
                        802, 902, 'owner', 'repo', 1, :sha, :key, 1, 1, '{}',
                        'PENDING', :now, :now)
                """).param("id", UUID.randomUUID()).param("analysisJobId", analysisJobId)
                .param("tenantId", tenantB.tenantId()).param("tenantRepositoryId", tenantB.repositoryId())
                .param("sha", "b".repeat(40)).param("key", "c".repeat(64))
                .param("now", timestamp).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidIdentifiersAndDomainRepresentationsFailSafely() {
        assertThatThrownBy(() -> store.provision(0, 1, NOW))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("tenant")
                .hasMessageNotContaining("repository");
        assertThatThrownBy(() -> store.provision(1, -1, NOW))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("tenant")
                .hasMessageNotContaining("repository");
        assertThat(jdbc.sql("SELECT count(*) FROM tenants").query(Long.class).single()).isZero();

        UUID id = UUID.randomUUID();
        assertThat(new Tenant(id, NOW, NOW).toString()).contains(id.toString());
        assertThatThrownBy(() -> new GitHubInstallationOwnership(
                UUID.randomUUID(), UUID.randomUUID(), 0, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        TenantContext context = new TenantContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 2);
        assertThat(context.toString()).contains("ownership=<redacted>")
                .doesNotContain("githubInstallationId");
    }
}
