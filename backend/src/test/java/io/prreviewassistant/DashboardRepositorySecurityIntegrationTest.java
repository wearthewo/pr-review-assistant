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
class DashboardRepositorySecurityIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");
    private static final String TOKEN_A = "repository-token-a";
    private static final String TOKEN_B = "repository-token-b";

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
    void ownerAndMemberCanListOnlyTheirTenantRepositoriesWithAllowlistedFields() throws Exception {
        TenantContext tenant = ownership.provision(101, 9002, NOW);
        ownership.provision(101, 9001, NOW.plusSeconds(1));
        ApplicationUser owner = user("user-a");
        ApplicationUser member = user("user-b");
        membership(owner.id(), tenant.tenantId(), "OWNER");
        membership(member.id(), tenant.tenantId(), "MEMBER");

        for (String token : List.of(TOKEN_A, TOKEN_B)) {
            String body = mockMvc.perform(get(path(tenant.tenantId()))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.repositories.length()").value(2))
                    .andExpect(jsonPath("$.repositories[0].repositoryId").value(9001))
                    .andExpect(jsonPath("$.repositories[1].repositoryId").value(9002))
                    .andExpect(jsonPath("$.repositories[0].connectedAt").isString())
                    .andExpect(jsonPath("$.truncated").value(false))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("tenantId", "installationId", "accessToken", "role", "name");
        }
    }

    @Test
    void crossTenantUnknownAndUnownedTenantRequestsFailClosedWithoutRepositoryDisclosure() throws Exception {
        TenantContext tenantA = ownership.provision(201, 9101, NOW);
        TenantContext tenantB = ownership.provision(202, 9201, NOW);
        ApplicationUser user = user("user-a");
        membership(user.id(), tenantA.tenantId(), "MEMBER");

        for (UUID denied : List.of(tenantB.tenantId(), UUID.randomUUID())) {
            mockMvc.perform(get(path(denied)).header("Authorization", "Bearer " + TOKEN_A))
                    .andExpect(status().isNotFound()).andExpect(content().string(""));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_memberships WHERE tenant_id = :tenant")
                .param("tenant", tenantB.tenantId()).query(Long.class).single()).isZero();
    }

    @Test
    void unauthenticatedAndMalformedTenantRequestsAreRejectedSafely() throws Exception {
        mockMvc.perform(get(path(UUID.randomUUID())))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mockMvc.perform(get("/api/dashboard/tenants/not-a-uuid/repositories")
                        .header("Authorization", "Bearer " + TOKEN_A))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
    }

    @Test
    void authorizedTenantCanReturnAnHonestEmptyCollection() throws Exception {
        TenantContext tenant = ownership.provision(301, 9301, NOW);
        jdbc.sql("DELETE FROM tenant_repositories WHERE tenant_id = :tenant")
                .param("tenant", tenant.tenantId()).update();
        ApplicationUser user = user("user-a");
        membership(user.id(), tenant.tenantId(), "OWNER");

        mockMvc.perform(get(path(tenant.tenantId())).header("Authorization", "Bearer " + TOKEN_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories").isEmpty())
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void resultIsDeterministicallyOrderedAndExplicitlyBounded() throws Exception {
        TenantContext tenant = ownership.provision(401, 10_000, NOW);
        OffsetDateTime timestamp = NOW.atOffset(ZoneOffset.UTC);
        for (int index = 0; index < 100; index++) {
            jdbc.sql("""
                    INSERT INTO tenant_repositories
                      (id, tenant_id, installation_id, github_repository_id, created_at, updated_at)
                    VALUES (:id, :tenant, :installation, :repository, :now, :now)
                    """).param("id", UUID.randomUUID()).param("tenant", tenant.tenantId())
                    .param("installation", tenant.installationId()).param("repository", 20_099L - index)
                    .param("now", timestamp).update();
        }
        ApplicationUser user = user("user-a");
        membership(user.id(), tenant.tenantId(), "OWNER");

        mockMvc.perform(get(path(tenant.tenantId())).header("Authorization", "Bearer " + TOKEN_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories.length()").value(100))
                .andExpect(jsonPath("$.repositories[0].repositoryId").value(10_000))
                .andExpect(jsonPath("$.repositories[99].repositoryId").value(20_098))
                .andExpect(jsonPath("$.truncated").value(true));
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
        return "/api/dashboard/tenants/" + tenantId + "/repositories";
    }

    private static Jwt jwt(String token, String subject) {
        return Jwt.withTokenValue(token).header("alg", "RS256")
                .issuer("https://issuer.example/").subject(subject)
                .audience(List.of("https://api.example"))
                .issuedAt(NOW.minusSeconds(10)).notBefore(NOW.minusSeconds(10))
                .expiresAt(NOW.plusSeconds(300)).build();
    }
}
