package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.prreviewassistant.observability.ApplicationMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "DB_JDBC_URL=jdbc:postgresql://invalid:5432/invalid",
        "DB_USERNAME=test",
        "DB_PASSWORD=test",
        "GITHUB_APP_ID=test-app-id",
        "GITHUB_PRIVATE_KEY_PATH=unused-test-key.pem",
        "GITHUB_WEBHOOK_SECRET=test-webhook-secret-with-entropy",
        "REVIEW_WORKER_ENABLED=false",
        "REVIEW_PUBLICATION_ENABLED=false"
})
@Import(PostgreSqlTestConfiguration.class)
class ObservabilityPersistenceIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private Clock clock;

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @BeforeEach
    void clean() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        jdbc.sql("DELETE FROM publication_jobs").update();
        jdbc.sql("DELETE FROM review_publications").update();
        jdbc.sql("DELETE FROM review_jobs").update();
    }

    @Test
    void queueGaugesReflectDueDelayedActiveAndStalePostgresState() {
        Instant now = clock.instant();
        insertReady(now.minusSeconds(120), 0);
        insertReady(now.plusSeconds(3600), 1);
        insertProcessing(now.minusSeconds(60), now.minusSeconds(30));
        insertProcessing(now.minusSeconds(30), now.plusSeconds(3600));
        insertPublicationJob("READY", now.minusSeconds(60), null);
        insertPublicationJob("READY", now.plusSeconds(1800), null);
        insertPublicationJob("PROCESSING", now.minusSeconds(45), now.minusSeconds(15));
        insertPublicationJob("PROCESSING", now.minusSeconds(30), now.plusSeconds(1800));

        assertGauge("ready", 1);
        assertGauge("delayed", 1);
        assertGauge("stale", 1);
        assertGauge("processing", 1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".queue.oldest.actionable.age")
                .tag("queue", "review").gauge().value()).isGreaterThanOrEqualTo(119);
        assertPublicationGauge("ready", 1);
        assertPublicationGauge("delayed", 1);
        assertPublicationGauge("stale", 1);
        assertPublicationGauge("processing", 1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".queue.oldest.actionable.age")
                .tag("queue", "publication").gauge().value()).isGreaterThanOrEqualTo(59);
    }

    @Test
    void healthProbesArePublicWhilePrometheusRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UP")));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UP")));
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "pr_review_assistant_queue_depth")));
        for (String sensitive : java.util.List.of("env", "configprops", "beans", "mappings", "heapdump",
                "threaddump", "loggers", "conditions", "scheduledtasks", "metrics")) {
            mockMvc.perform(get("/actuator/" + sensitive).with(jwt()))
                    .andExpect(status().isForbidden());
        }
    }

    private void assertGauge(String state, double expected) {
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".queue.depth")
                .tags("queue", "review", "state", state).gauge().value()).isEqualTo(expected);
    }

    private void assertPublicationGauge(String state, double expected) {
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".queue.depth")
                .tags("queue", "publication", "state", state).gauge().value()).isEqualTo(expected);
    }

    private void insertReady(Instant nextAttempt, int attempts) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(nextAttempt.minusSeconds(10), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO review_jobs
                  (id, status, attempts, max_attempts, next_attempt_at, last_error_code, created_at, updated_at)
                VALUES (:id, 'READY', :attempts, 3, :nextAttempt, :error, :createdAt, :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("attempts", attempts)
                .param("nextAttempt", OffsetDateTime.ofInstant(nextAttempt, ZoneOffset.UTC))
                .param("error", attempts == 0 ? null : "RETRYABLE_TEST")
                .param("createdAt", timestamp)
                .update();
    }

    private void insertProcessing(Instant claimedAt, Instant expiresAt) {
        OffsetDateTime claimed = OffsetDateTime.ofInstant(claimedAt, ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO review_jobs
                  (id, status, attempts, max_attempts, next_attempt_at, claim_token,
                   claimed_at, claim_expires_at, created_at, updated_at)
                VALUES (:id, 'PROCESSING', 1, 3, :claimedAt, :claimToken,
                        :claimedAt, :expiresAt, :createdAt, :claimedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("claimedAt", claimed)
                .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .param("claimToken", UUID.randomUUID())
                .param("createdAt", OffsetDateTime.ofInstant(claimedAt.minusSeconds(10), ZoneOffset.UTC))
                .update();
    }

    private void insertPublicationJob(String status, Instant actionableAt, Instant expiresAt) {
        UUID analysisJobId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        UUID publicationJobId = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.ofInstant(actionableAt.minusSeconds(10), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO review_jobs
                  (id, status, attempts, max_attempts, completed_at, created_at, updated_at)
                VALUES (:id, 'COMPLETED', 1, 3, :completedAt, :createdAt, :completedAt)
                """)
                .param("id", analysisJobId)
                .param("completedAt", created.plusSeconds(1))
                .param("createdAt", created)
                .update();
        jdbc.sql("""
                INSERT INTO review_publications
                  (id, analysis_job_id, github_installation_id, github_repository_id,
                   github_repository_owner, github_repository_name, github_pull_request_number,
                   github_head_sha, publication_key, payload_version, finding_count, payload,
                   status, created_at, updated_at)
                VALUES (:id, :analysisJobId, 1, 2, 'owner', 'repository', 3,
                        :headSha, :publicationKey, 1, 1, 'payload', 'PENDING', :createdAt, :createdAt)
                """)
                .param("id", publicationId)
                .param("analysisJobId", analysisJobId)
                .param("headSha", "a".repeat(40))
                .param("publicationKey", publicationId.toString().replace("-", "").repeat(2))
                .param("createdAt", created)
                .update();
        boolean processing = "PROCESSING".equals(status);
        jdbc.sql("""
                INSERT INTO publication_jobs
                  (id, publication_id, status, attempts, max_attempts, next_attempt_at,
                   claim_token, claimed_at, claim_expires_at, created_at, updated_at)
                VALUES (:id, :publicationId, :status, :attempts, 3, :nextAttempt,
                        :claimToken, :claimedAt, :expiresAt, :createdAt, :updatedAt)
                """)
                .param("id", publicationJobId)
                .param("publicationId", publicationId)
                .param("status", status)
                .param("attempts", processing ? 1 : 0)
                .param("nextAttempt", OffsetDateTime.ofInstant(actionableAt, ZoneOffset.UTC))
                .param("claimToken", processing ? UUID.randomUUID() : null)
                .param("claimedAt", processing ? OffsetDateTime.ofInstant(actionableAt, ZoneOffset.UTC) : null)
                .param("expiresAt", processing ? OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC) : null)
                .param("createdAt", created)
                .param("updatedAt", OffsetDateTime.ofInstant(actionableAt, ZoneOffset.UTC))
                .update();
    }
}
