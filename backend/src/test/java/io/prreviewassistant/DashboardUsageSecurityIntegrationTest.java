package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.prreviewassistant.identity.ApplicationIdentityStore;
import io.prreviewassistant.identity.ApplicationUser;
import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import io.prreviewassistant.tenant.TenantContext;
import io.prreviewassistant.tenant.TenantOwnershipStore;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "REVIEW_WORKER_ENABLED=false", "REVIEW_PUBLICATION_ENABLED=false",
        "REVIEW_USAGE_MONTHLY_LIMIT=3",
        "DB_JDBC_URL=jdbc:postgresql://unused", "DB_USERNAME=unused", "DB_PASSWORD=unused",
        "GITHUB_APP_ID=1", "GITHUB_PRIVATE_KEY_PATH=unused",
        "GITHUB_WEBHOOK_SECRET=test-secret-that-is-long-enough"
})
@Import(PostgreSqlTestConfiguration.class)
class DashboardUsageSecurityIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private static final String TOKEN_A = "usage-token-a";
    private static final String TOKEN_B = "usage-token-b";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired TenantOwnershipStore ownership;
    @Autowired ApplicationIdentityStore identities;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean Clock clock;
    private MockMvc mockMvc;
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        clean();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(clock.instant()).thenReturn(NOW);
        when(jwtDecoder.decode(TOKEN_A)).thenReturn(jwt(TOKEN_A, "user-a"));
        when(jwtDecoder.decode(TOKEN_B)).thenReturn(jwt(TOKEN_B, "user-b"));
    }

    @AfterEach
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
    void ownerAndMemberCanReadOnlyTheirAuthoritativeQuotaWithAllowlistedFields() throws Exception {
        TenantContext tenant = ownership.provision(101, 9001, NOW);
        ApplicationUser owner = user("user-a");
        ApplicationUser member = user("user-b");
        membership(owner.id(), tenant.tenantId(), "OWNER");
        membership(member.id(), tenant.tenantId(), "MEMBER");
        usage(tenant, "RESERVED", NOW.minusSeconds(60));
        usage(tenant, "CONSUMED", NOW.minusSeconds(30));

        for (String token : List.of(TOKEN_A, TOKEN_B)) {
            String body = mockMvc.perform(get(path(tenant.tenantId()))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewAnalysis.periodStart").value("2026-09-01T00:00:00Z"))
                    .andExpect(jsonPath("$.reviewAnalysis.periodEnd").value("2026-10-01T00:00:00Z"))
                    .andExpect(jsonPath("$.reviewAnalysis.limit").value(3))
                    .andExpect(jsonPath("$.reviewAnalysis.used").value(2))
                    .andExpect(jsonPath("$.reviewAnalysis.remaining").value(1))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("tenantId", "reviewJobId", "usageType", "status",
                    "provider", "model", "token", "installationId", "role");
        }
    }

    @Test
    void crossTenantUnknownAndHistoricalUnownedTenantRequestsFailClosed() throws Exception {
        TenantContext tenantA = ownership.provision(201, 9101, NOW);
        TenantContext tenantB = ownership.provision(202, 9201, NOW);
        ApplicationUser user = user("user-a");
        membership(user.id(), tenantA.tenantId(), "MEMBER");
        usage(tenantB, "CONSUMED", NOW);

        for (UUID denied : List.of(tenantB.tenantId(), UUID.randomUUID())) {
            mockMvc.perform(get(path(denied)).header("Authorization", "Bearer " + TOKEN_A))
                    .andExpect(status().isNotFound()).andExpect(content().string(""));
        }
    }

    @Test
    void unauthenticatedAndMalformedTenantRequestsAreRejectedSafely() throws Exception {
        mockMvc.perform(get(path(UUID.randomUUID())))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mockMvc.perform(get("/api/dashboard/tenants/not-a-uuid/usage")
                        .header("Authorization", "Bearer " + TOKEN_A))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
    }

    @Test
    void zeroUsageReturnsTheConfiguredLimitWithoutMutation() throws Exception {
        TenantContext tenant = ownership.provision(301, 9301, NOW);
        ApplicationUser user = user("user-a");
        membership(user.id(), tenant.tenantId(), "OWNER");
        long before = eventCount();

        mockMvc.perform(get(path(tenant.tenantId())).header("Authorization", "Bearer " + TOKEN_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewAnalysis.limit").value(3))
                .andExpect(jsonPath("$.reviewAnalysis.used").value(0))
                .andExpect(jsonPath("$.reviewAnalysis.remaining").value(3));

        assertThat(eventCount()).isEqualTo(before);
    }

    @Test
    void currentUtcHalfOpenPeriodCountsReservedAndConsumedButNotReleased() throws Exception {
        TenantContext tenant = ownership.provision(401, 9401, NOW);
        ApplicationUser user = user("user-a");
        membership(user.id(), tenant.tenantId(), "OWNER");
        usage(tenant, "CONSUMED", Instant.parse("2026-08-31T23:59:59.999Z"));
        usage(tenant, "RESERVED", Instant.parse("2026-09-01T00:00:00Z"));
        usage(tenant, "CONSUMED", Instant.parse("2026-09-30T23:59:59.999Z"));
        usage(tenant, "RELEASED", NOW);
        usage(tenant, "RESERVED", Instant.parse("2026-10-01T00:00:00Z"));
        long before = eventCount();

        mockMvc.perform(get(path(tenant.tenantId())).header("Authorization", "Bearer " + TOKEN_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewAnalysis.used").value(2))
                .andExpect(jsonPath("$.reviewAnalysis.remaining").value(1));

        assertThat(eventCount()).isEqualTo(before);
    }

    @Test
    void historicalOverLimitStateRemainsTruthfulAndRemainingNeverBecomesNegative() throws Exception {
        TenantContext tenant = ownership.provision(501, 9501, NOW);
        ApplicationUser user = user("user-a");
        membership(user.id(), tenant.tenantId(), "MEMBER");
        for (int index = 0; index < 4; index++) {
            usage(tenant, index % 2 == 0 ? "RESERVED" : "CONSUMED", NOW.minusSeconds(index));
        }

        mockMvc.perform(get(path(tenant.tenantId())).header("Authorization", "Bearer " + TOKEN_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewAnalysis.limit").value(3))
                .andExpect(jsonPath("$.reviewAnalysis.used").value(4))
                .andExpect(jsonPath("$.reviewAnalysis.remaining").value(0));
    }

    private void usage(TenantContext tenant, String status, Instant occurredAt) {
        int value = sequence.incrementAndGet();
        UUID jobId = UUID.randomUUID();
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO review_jobs
                  (id, status, attempts, max_attempts, next_attempt_at, created_at, updated_at,
                   github_installation_id, github_repository_id, github_pull_request_number,
                   github_head_sha, tenant_id, tenant_repository_id)
                VALUES (:id, 'READY', 0, 3, :now, :now, :now, :installation, :repository,
                        :pullRequest, :sha, :tenant, :tenantRepository)
                """).param("id", jobId).param("now", now)
                .param("installation", tenant.githubInstallationId())
                .param("repository", tenant.githubRepositoryId()).param("pullRequest", value)
                .param("sha", "%040x".formatted(value)).param("tenant", tenant.tenantId())
                .param("tenantRepository", tenant.repositoryId()).update();
        OffsetDateTime occurred = occurredAt.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO tenant_usage_events
                  (id, tenant_id, tenant_repository_id, review_job_id, usage_type, status,
                   occurred_at, consumed_at, released_at, created_at, updated_at)
                VALUES (:id, :tenant, :tenantRepository, :job, 'REVIEW_ANALYSIS', :status,
                        :occurred, :consumed, :released, :created, :updated)
                """).param("id", UUID.randomUUID()).param("tenant", tenant.tenantId())
                .param("tenantRepository", tenant.repositoryId()).param("job", jobId)
                .param("status", status).param("occurred", occurred)
                .param("consumed", "CONSUMED".equals(status) ? occurred : null)
                .param("released", "RELEASED".equals(status) ? occurred : null)
                .param("created", occurred).param("updated", occurred).update();
    }

    private ApplicationUser user(String subject) {
        return identities.provisionUser(new AuthenticatedUserIdentity("https://issuer.example/", subject), NOW);
    }

    private void membership(UUID userId, UUID tenantId, String role) {
        OffsetDateTime timestamp = NOW.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO tenant_memberships (id, tenant_id, user_id, role, created_at, updated_at)
                VALUES (:id, :tenant, :user, :role, :now, :now)
                """).param("id", UUID.randomUUID()).param("tenant", tenantId).param("user", userId)
                .param("role", role).param("now", timestamp).update();
    }

    private long eventCount() {
        return jdbc.sql("SELECT count(*) FROM tenant_usage_events").query(Long.class).single();
    }

    private static String path(UUID tenantId) {
        return "/api/dashboard/tenants/" + tenantId + "/usage";
    }

    private static Jwt jwt(String token, String subject) {
        return Jwt.withTokenValue(token).header("alg", "RS256")
                .issuer("https://issuer.example/").subject(subject)
                .audience(List.of("https://api.example"))
                .issuedAt(NOW.minusSeconds(10)).notBefore(NOW.minusSeconds(10))
                .expiresAt(NOW.plusSeconds(300)).build();
    }
}
