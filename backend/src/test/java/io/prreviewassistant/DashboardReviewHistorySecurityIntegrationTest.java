package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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
        "DB_JDBC_URL=jdbc:postgresql://unused", "DB_USERNAME=unused", "DB_PASSWORD=unused",
        "GITHUB_APP_ID=1", "GITHUB_PRIVATE_KEY_PATH=unused",
        "GITHUB_WEBHOOK_SECRET=test-secret-that-is-long-enough"
})
@Import(PostgreSqlTestConfiguration.class)
class DashboardReviewHistorySecurityIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");
    private static final String TOKEN_A = "history-token-a";
    private static final String TOKEN_B = "history-token-b";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired TenantOwnershipStore ownership;
    @Autowired ApplicationIdentityStore identities;
    @MockitoBean JwtDecoder jwtDecoder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clean();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(jwtDecoder.decode(TOKEN_A)).thenReturn(jwt(TOKEN_A, "history-user-a"));
        when(jwtDecoder.decode(TOKEN_B)).thenReturn(jwt(TOKEN_B, "history-user-b"));
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
    void ownerAndMemberSeeTruthfulAllowlistedReviewAndPublicationStates() throws Exception {
        TenantContext tenant = ownership.provision(101, 9001, NOW);
        ApplicationUser owner = user("history-user-a");
        ApplicationUser member = user("history-user-b");
        membership(owner.id(), tenant.tenantId(), "OWNER");
        membership(member.id(), tenant.tenantId(), "MEMBER");

        UUID queued = review(tenant, "READY", NOW.plusSeconds(1), 1);
        UUID analyzing = review(tenant, "PROCESSING", NOW.plusSeconds(2), 2);
        UUID failed = review(tenant, "FAILED", NOW.plusSeconds(3), 3);
        UUID completed = review(tenant, "COMPLETED", NOW.plusSeconds(4), 4);
        UUID pending = review(tenant, "COMPLETED", NOW.plusSeconds(5), 5);
        UUID uncertain = review(tenant, "COMPLETED", NOW.plusSeconds(6), 6);
        UUID published = review(tenant, "COMPLETED", NOW.plusSeconds(7), 7);
        UUID publicationFailed = review(tenant, "COMPLETED", NOW.plusSeconds(8), 8);
        publication(tenant, pending, "PENDING", 2, 5);
        publication(tenant, uncertain, "AMBIGUOUS", 2, 6);
        publication(tenant, published, "PUBLISHED", 2, 7);
        publication(tenant, publicationFailed, "FAILED", 2, 8);

        for (String token : List.of(TOKEN_A, TOKEN_B)) {
            String body = mockMvc.perform(get(path(tenant.tenantId())).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviews.length()").value(8))
                    .andExpect(jsonPath("$.reviews[0].state").value("PUBLICATION_FAILED"))
                    .andExpect(jsonPath("$.reviews[1].state").value("PUBLISHED"))
                    .andExpect(jsonPath("$.reviews[2].state").value("PUBLICATION_UNCERTAIN"))
                    .andExpect(jsonPath("$.reviews[3].state").value("PUBLICATION_PENDING"))
                    .andExpect(jsonPath("$.reviews[4].state").value("COMPLETED_WITHOUT_PUBLICATION"))
                    .andExpect(jsonPath("$.reviews[5].state").value("ANALYSIS_FAILED"))
                    .andExpect(jsonPath("$.reviews[6].state").value("ANALYZING"))
                    .andExpect(jsonPath("$.reviews[7].state").value("QUEUED"))
                    .andExpect(jsonPath("$.reviews[0].publishableFindingCount").value(2))
                    .andExpect(jsonPath("$.reviews[4].publishableFindingCount").doesNotExist())
                    .andExpect(jsonPath("$.hasMore").value(false))
                    .andExpect(jsonPath("$.nextCursor").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).contains("repositoryId", "pullRequestNumber", "headSha", "createdAt", "updatedAt")
                    .doesNotContain("jobId", "tenantId", "installationId", "claim", "attempt", "payload",
                            "publicationKey", "githubReviewId", "lastError", "secret-publication-payload");
        }
        assertThat(List.of(queued, analyzing, failed, completed)).doesNotContainNull();
    }

    @Test
    void foreignUnknownAndHistoricalUnownedTenantsFailClosedRegardlessOfKnownIdentifiers() throws Exception {
        TenantContext tenantA = ownership.provision(201, 9101, NOW);
        TenantContext tenantB = ownership.provision(202, 9201, NOW);
        UUID foreignJob = review(tenantB, "COMPLETED", NOW, 42);
        ApplicationUser user = user("history-user-a");
        membership(user.id(), tenantA.tenantId(), "MEMBER");

        for (UUID denied : List.of(tenantB.tenantId(), UUID.randomUUID())) {
            mockMvc.perform(get(path(denied))
                            .queryParam("repositoryId", "9201")
                            .queryParam("pullRequestNumber", "42")
                            .queryParam("reviewId", foreignJob.toString())
                            .header("Authorization", "Bearer " + TOKEN_A))
                    .andExpect(status().isNotFound()).andExpect(content().string(""));
        }
    }

    @Test
    void unauthenticatedMalformedTenantAndInvalidPageSizesAreRejectedSafely() throws Exception {
        mockMvc.perform(get(path(UUID.randomUUID())))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mockMvc.perform(get("/api/dashboard/tenants/not-a-uuid/reviews")
                        .header("Authorization", "Bearer " + TOKEN_A))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));

        TenantContext tenant = ownership.provision(301, 9301, NOW);
        membership(user("history-user-a").id(), tenant.tenantId(), "OWNER");
        for (String limit : List.of("0", "51", "not-a-number")) {
            mockMvc.perform(get(path(tenant.tenantId())).queryParam("limit", limit)
                            .header("Authorization", "Bearer " + TOKEN_A))
                    .andExpect(status().isBadRequest()).andExpect(content().string(""));
        }
    }

    @Test
    void authorizedTenantReceivesHonestEmptyHistory() throws Exception {
        TenantContext tenant = ownership.provision(401, 9401, NOW);
        membership(user("history-user-a").id(), tenant.tenantId(), "OWNER");
        mockMvc.perform(get(path(tenant.tenantId())).header("Authorization", "Bearer " + TOKEN_A))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviews").isEmpty())
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void keysetPaginationIsNewestFirstBoundedAndDeterministic() throws Exception {
        TenantContext tenant = ownership.provision(501, 9501, NOW);
        membership(user("history-user-a").id(), tenant.tenantId(), "OWNER");
        for (int index = 1; index <= 51; index++) {
            review(tenant, "COMPLETED", NOW.plusSeconds(index), index);
        }

        String firstBody = mockMvc.perform(get(path(tenant.tenantId()))
                        .header("Authorization", "Bearer " + TOKEN_A))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviews.length()").value(20))
                .andExpect(jsonPath("$.reviews[0].pullRequestNumber").value(51))
                .andExpect(jsonPath("$.reviews[19].pullRequestNumber").value(32))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(firstBody, "$.nextCursor");
        assertThat(cursor).hasSizeLessThanOrEqualTo(160).doesNotContain("history-user", "9501");

        mockMvc.perform(get(path(tenant.tenantId())).queryParam("cursor", cursor)
                        .header("Authorization", "Bearer " + TOKEN_A))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviews.length()").value(20))
                .andExpect(jsonPath("$.reviews[0].pullRequestNumber").value(31))
                .andExpect(jsonPath("$.reviews[19].pullRequestNumber").value(12));

        mockMvc.perform(get(path(tenant.tenantId())).queryParam("limit", "50")
                        .header("Authorization", "Bearer " + TOKEN_A))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviews.length()").value(50))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void malformedAndCrossTenantCursorsAreRejectedWithoutDisclosure() throws Exception {
        TenantContext tenantA = ownership.provision(601, 9601, NOW);
        TenantContext tenantB = ownership.provision(602, 9602, NOW);
        membership(user("history-user-a").id(), tenantA.tenantId(), "OWNER");
        membership(user("history-user-b").id(), tenantB.tenantId(), "OWNER");
        for (int index = 1; index <= 2; index++) review(tenantA, "COMPLETED", NOW.plusSeconds(index), index);

        String body = mockMvc.perform(get(path(tenantA.tenantId())).queryParam("limit", "1")
                        .header("Authorization", "Bearer " + TOKEN_A))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(body, "$.nextCursor");

        for (String invalid : List.of("not-base64", "x".repeat(161))) {
            mockMvc.perform(get(path(tenantA.tenantId())).queryParam("cursor", invalid)
                            .header("Authorization", "Bearer " + TOKEN_A))
                    .andExpect(status().isBadRequest()).andExpect(content().string(""));
        }
        mockMvc.perform(get(path(tenantB.tenantId())).queryParam("cursor", cursor)
                        .header("Authorization", "Bearer " + TOKEN_B))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
    }

    private UUID review(TenantContext tenant, String status, Instant createdAt, int sequence) {
        UUID id = new UUID(0, sequence);
        OffsetDateTime timestamp = createdAt.atOffset(ZoneOffset.UTC);
        OffsetDateTime nextAttempt = status.equals("READY") || status.equals("PROCESSING") ? timestamp : null;
        UUID claimToken = status.equals("PROCESSING") ? UUID.randomUUID() : null;
        OffsetDateTime claimedAt = status.equals("PROCESSING") ? timestamp : null;
        OffsetDateTime claimExpires = status.equals("PROCESSING") ? timestamp.plusMinutes(5) : null;
        OffsetDateTime completedAt = status.equals("COMPLETED") ? timestamp : null;
        OffsetDateTime failedAt = status.equals("FAILED") ? timestamp : null;
        jdbc.sql("""
                INSERT INTO review_jobs
                  (id, status, attempts, max_attempts, next_attempt_at, claim_token, claimed_at,
                   claim_expires_at, completed_at, failed_at, last_error_code,
                   github_installation_id, github_repository_id, github_pull_request_number,
                   github_head_sha, tenant_id, tenant_repository_id, created_at, updated_at)
                VALUES (:id, :status, :attempts, 3, :nextAttempt, :claimToken, :claimedAt,
                        :claimExpires, :completedAt, :failedAt, :errorCode,
                        :githubInstallation, :githubRepository, :pullNumber,
                        :headSha, :tenant, :tenantRepository, :createdAt, :createdAt)
                """).param("id", id).param("status", status)
                .param("attempts", status.equals("READY") ? 0 : 1)
                .param("nextAttempt", nextAttempt).param("claimToken", claimToken)
                .param("claimedAt", claimedAt).param("claimExpires", claimExpires)
                .param("completedAt", completedAt).param("failedAt", failedAt)
                .param("errorCode", status.equals("FAILED") ? "SECRET_INTERNAL_FAILURE" : null)
                .param("githubInstallation", tenant.githubInstallationId())
                .param("githubRepository", tenant.githubRepositoryId()).param("pullNumber", sequence)
                .param("headSha", "%040x".formatted(sequence)).param("tenant", tenant.tenantId())
                .param("tenantRepository", tenant.repositoryId()).param("createdAt", timestamp).update();
        return id;
    }

    private void publication(TenantContext tenant, UUID jobId, String status, int findings, int sequence) {
        OffsetDateTime timestamp = NOW.plusSeconds(100 + sequence).atOffset(ZoneOffset.UTC);
        Long reviewId = status.equals("PUBLISHED") ? 10_000L + sequence : null;
        OffsetDateTime publishedAt = status.equals("PUBLISHED") ? timestamp : null;
        jdbc.sql("""
                INSERT INTO review_publications
                  (id, analysis_job_id, tenant_id, tenant_repository_id,
                   github_installation_id, github_repository_id, github_repository_owner,
                   github_repository_name, github_pull_request_number, github_head_sha,
                   publication_key, payload_version, finding_count, payload, status,
                   github_review_id, published_at, last_error_code, created_at, updated_at)
                VALUES (:id, :job, :tenant, :tenantRepository,
                        :installation, :repository, 'unexposed-owner', 'unexposed-name',
                        :pullNumber, :headSha, :publicationKey, 1, :findings,
                        'secret-publication-payload', :status, :reviewId, :publishedAt,
                        :errorCode, :now, :now)
                """).param("id", UUID.randomUUID()).param("job", jobId).param("tenant", tenant.tenantId())
                .param("tenantRepository", tenant.repositoryId())
                .param("installation", tenant.githubInstallationId())
                .param("repository", tenant.githubRepositoryId()).param("pullNumber", sequence)
                .param("headSha", "%040x".formatted(sequence))
                .param("publicationKey", "%064x".formatted(sequence)).param("findings", findings)
                .param("status", status).param("reviewId", reviewId).param("publishedAt", publishedAt)
                .param("errorCode", status.equals("FAILED") ? "SECRET_PUBLICATION_FAILURE" : null)
                .param("now", timestamp).update();
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

    private static String path(UUID tenantId) {
        return "/api/dashboard/tenants/" + tenantId + "/reviews";
    }

    private static Jwt jwt(String token, String subject) {
        return Jwt.withTokenValue(token).header("alg", "RS256")
                .issuer("https://issuer.example/").subject(subject)
                .audience(List.of("https://api.example"))
                .issuedAt(NOW.minusSeconds(10)).notBefore(NOW.minusSeconds(10))
                .expiresAt(NOW.plusSeconds(300)).build();
    }
}
