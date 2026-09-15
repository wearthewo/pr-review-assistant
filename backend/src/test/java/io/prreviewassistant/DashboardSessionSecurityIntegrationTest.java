package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

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
class DashboardSessionSecurityIntegrationTest {
    private static final String ACCESS_TOKEN = "synthetic-access-token-never-returned";
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        clean();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM tenant_memberships").update();
        jdbc.sql("DELETE FROM application_users").update();
    }

    @Test
    void missingAndInvalidAuthenticationAreRejectedWithoutDetails() throws Exception {
        mockMvc.perform(get("/api/dashboard/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("synthetic signature failure"));
        mockMvc.perform(get("/api/dashboard/session").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void verifiedIdentityCreatesOneInternalUserAndReturnsSafeUnboundSession() throws Exception {
        when(jwtDecoder.decode(ACCESS_TOKEN)).thenReturn(validJwt());

        String first = mockMvc.perform(get("/api/dashboard/session")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationUserId").isString())
                .andExpect(jsonPath("$.memberships").isEmpty())
                .andExpect(jsonPath("$.onboardingRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/dashboard/session")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(first).doesNotContain(ACCESS_TOKEN, "auth0|sensitive-subject", "issuer.example");
        assertThat(jdbc.sql("SELECT count(*) FROM application_users").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_memberships").query(Long.class).single()).isZero();
    }

    private Jwt validJwt() {
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        return Jwt.withTokenValue(ACCESS_TOKEN)
                .header("alg", "RS256")
                .issuer("https://issuer.example/")
                .subject("auth0|sensitive-subject")
                .audience(List.of("https://api.example"))
                .issuedAt(now.minusSeconds(10))
                .notBefore(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
