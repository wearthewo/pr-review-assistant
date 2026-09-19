package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "REVIEW_WORKER_ENABLED=false", "REVIEW_PUBLICATION_ENABLED=false",
        "REVIEW_USAGE_MONTHLY_LIMIT=50",
        "DB_JDBC_URL=jdbc:postgresql://unused", "DB_USERNAME=unused", "DB_PASSWORD=unused",
        "GITHUB_APP_ID=1", "GITHUB_PRIVATE_KEY_PATH=unused",
        "GITHUB_WEBHOOK_SECRET=test-secret-that-is-long-enough"
})
@Import(PostgreSqlTestConfiguration.class)
class DashboardCrossFeatureSecurityIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private static final String OWNER_A_TOKEN = "cross-feature-owner-a";
    private static final String MEMBER_A_TOKEN = "cross-feature-member-a";
    private static final String OWNER_B_TOKEN = "cross-feature-owner-b";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired TenantOwnershipStore ownership;
    @Autowired ApplicationIdentityStore identities;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean Clock clock;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clean();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(clock.instant()).thenReturn(NOW);
        when(jwtDecoder.decode(OWNER_A_TOKEN)).thenReturn(jwt(OWNER_A_TOKEN, "owner-a"));
        when(jwtDecoder.decode(MEMBER_A_TOKEN)).thenReturn(jwt(MEMBER_A_TOKEN, "member-a"));
        when(jwtDecoder.decode(OWNER_B_TOKEN)).thenReturn(jwt(OWNER_B_TOKEN, "owner-b"));
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
    void ownerAndMemberSeeOneConsistentlyScopedRepositoryReviewAndUsageReadModel() throws Exception {
        TenantContext tenant = ownership.provision(101, 10_100, NOW);
        membership(user("owner-a").id(), tenant.tenantId(), "OWNER");
        membership(user("member-a").id(), tenant.tenantId(), "MEMBER");
        reviewWithUsage(tenant, 41, 1);

        for (String token : List.of(OWNER_A_TOKEN, MEMBER_A_TOKEN)) {
            mockMvc.perform(get("/api/dashboard/session").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberships.length()").value(1))
                    .andExpect(jsonPath("$.memberships[0].tenantId").value(tenant.tenantId().toString()));
            mockMvc.perform(get(path(tenant.tenantId(), "repositories")).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.repositories.length()").value(1))
                    .andExpect(jsonPath("$.repositories[0].repositoryId").value(10_100));
            mockMvc.perform(get(path(tenant.tenantId(), "reviews")).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviews.length()").value(1))
                    .andExpect(jsonPath("$.reviews[0].repositoryId").value(10_100))
                    .andExpect(jsonPath("$.reviews[0].pullRequestNumber").value(41));
            mockMvc.perform(get(path(tenant.tenantId(), "usage")).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewAnalysis.used").value(1))
                    .andExpect(jsonPath("$.reviewAnalysis.remaining").value(49));
        }
    }

    @Test
    void knownForeignTenantAndResourceIdentifiersGrantNoCrossFeatureAccess() throws Exception {
        TenantContext tenantA = ownership.provision(201, 20_100, NOW);
        TenantContext tenantB = ownership.provision(202, 20_200, NOW);
        membership(user("owner-a").id(), tenantA.tenantId(), "OWNER");
        membership(user("owner-b").id(), tenantB.tenantId(), "OWNER");
        reviewWithUsage(tenantA, 51, 1);
        reviewWithUsage(tenantB, 52, 2);

        for (String resource : List.of("repositories", "reviews", "usage")) {
            mockMvc.perform(get(path(tenantB.tenantId(), resource))
                            .queryParam("repositoryId", Long.toString(tenantB.githubRepositoryId()))
                            .queryParam("installationId", Long.toString(tenantB.githubInstallationId()))
                            .header("Authorization", bearer(OWNER_A_TOKEN)))
                    .andExpect(status().isNotFound()).andExpect(content().string(""));
        }
        String session = mockMvc.perform(get("/api/dashboard/session")
                        .header("Authorization", bearer(OWNER_A_TOKEN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(session).contains(tenantA.tenantId().toString()).doesNotContain(tenantB.tenantId().toString());
    }

    private UUID reviewWithUsage(TenantContext tenant, int pullRequestNumber, int sequence) {
        UUID jobId = new UUID(9, sequence);
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO review_jobs
                  (id, status, attempts, max_attempts, completed_at,
                   github_installation_id, github_repository_id, github_pull_request_number,
                   github_head_sha, tenant_id, tenant_repository_id, created_at, updated_at)
                VALUES (:id, 'COMPLETED', 1, 3, :now,
                        :installation, :repository, :pullRequest, :sha,
                        :tenant, :tenantRepository, :now, :now)
                """).param("id", jobId).param("now", now)
                .param("installation", tenant.githubInstallationId())
                .param("repository", tenant.githubRepositoryId()).param("pullRequest", pullRequestNumber)
                .param("sha", "%040x".formatted(sequence)).param("tenant", tenant.tenantId())
                .param("tenantRepository", tenant.repositoryId()).update();
        jdbc.sql("""
                INSERT INTO tenant_usage_events
                  (id, tenant_id, tenant_repository_id, review_job_id, usage_type, status,
                   occurred_at, consumed_at, created_at, updated_at)
                VALUES (:id, :tenant, :tenantRepository, :job, 'REVIEW_ANALYSIS', 'CONSUMED',
                        :now, :now, :now, :now)
                """).param("id", UUID.randomUUID()).param("tenant", tenant.tenantId())
                .param("tenantRepository", tenant.repositoryId()).param("job", jobId)
                .param("now", now).update();
        return jobId;
    }

    private ApplicationUser user(String subject) {
        return identities.provisionUser(new AuthenticatedUserIdentity("https://issuer.example/", subject), NOW);
    }

    private void membership(UUID userId, UUID tenantId, String role) {
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO tenant_memberships (id, tenant_id, user_id, role, created_at, updated_at)
                VALUES (:id, :tenant, :user, :role, :now, :now)
                """).param("id", UUID.randomUUID()).param("tenant", tenantId).param("user", userId)
                .param("role", role).param("now", now).update();
    }

    private static String path(UUID tenantId, String resource) {
        return "/api/dashboard/tenants/" + tenantId + "/" + resource;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static Jwt jwt(String token, String subject) {
        return Jwt.withTokenValue(token).header("alg", "RS256")
                .issuer("https://issuer.example/").subject(subject)
                .audience(List.of("https://api.example"))
                .issuedAt(NOW.minusSeconds(10)).notBefore(NOW.minusSeconds(10))
                .expiresAt(NOW.plusSeconds(300)).build();
    }
}
