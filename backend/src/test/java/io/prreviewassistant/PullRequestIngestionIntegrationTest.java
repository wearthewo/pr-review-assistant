package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.prreviewassistant.github.webhook.GitHubWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
        "REVIEW_WORKER_ENABLED=false"
})
@Import(PostgreSqlTestConfiguration.class)
class PullRequestIngestionIntegrationTest {

    private static final String SECRET = "test-webhook-secret-with-entropy";
    private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired
    private GitHubWebhookService service;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void clearState() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        jdbcClient.sql("DELETE FROM review_jobs").update();
        jdbcClient.sql("DELETE FROM github_webhook_deliveries").update();
        jdbcClient.sql("DELETE FROM tenant_repositories").update();
        jdbcClient.sql("DELETE FROM github_installations").update();
        jdbcClient.sql("DELETE FROM tenants").update();
    }

    @Test
    void openedAndSynchronizeCommitWebhookAndExactRevisionJobs() {
        ingest("delivery-opened", "pull_request", pullRequest("opened", 101, 202, 42, SHA_A));
        ingest("delivery-sync", "pull_request", pullRequest("synchronize", 101, 202, 42, SHA_B));

        assertThat(deliveryCount()).isEqualTo(2);
        assertThat(reviewTargets()).containsExactlyInAnyOrder(
                new StoredTarget(101, 202, 42, SHA_A),
                new StoredTarget(101, 202, 42, SHA_B));
        assertThat(jdbcClient.sql("""
                SELECT count(*) FROM review_jobs
                WHERE tenant_id IS NOT NULL AND tenant_repository_id IS NOT NULL
                """).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void closedAndUnrelatedEventsAreDurableButCreateNoJobs() {
        ingest("delivery-closed", "pull_request", pullRequest("closed", 101, 202, 42, SHA_A));
        ingest("delivery-ping", "ping", "{\"zen\":\"synthetic\"}");

        assertThat(deliveryCount()).isEqualTo(2);
        assertThat(jobCount()).isZero();
    }

    @Test
    void exactDeliveryRedeliveryCreatesOneWebhookAndOneJob() {
        String payload = pullRequest("opened", 101, 202, 42, SHA_A);

        ingest("delivery-repeat", "pull_request", payload);
        ingest("delivery-repeat", "pull_request", payload);

        assertThat(deliveryCount()).isOne();
        assertThat(jobCount()).isOne();
    }

    @Test
    void distinctDeliveriesForTheSameRevisionCreateTwoWebhooksAndOneJob() {
        String payload = pullRequest("opened", 101, 202, 42, SHA_A);

        ingest("delivery-one", "pull_request", payload);
        ingest("delivery-two", "pull_request", payload);

        assertThat(deliveryCount()).isEqualTo(2);
        assertThat(jobCount()).isOne();
    }

    @Test
    void reviewIdentityIncludesInstallationRepositoryPullRequestAndSha() {
        ingest("d1", "pull_request", pullRequest("opened", 101, 202, 42, SHA_A));
        ingest("d2", "pull_request", pullRequest("opened", 102, 204, 42, SHA_A));
        ingest("d3", "pull_request", pullRequest("opened", 101, 203, 42, SHA_A));
        ingest("d4", "pull_request", pullRequest("opened", 101, 202, 43, SHA_A));
        ingest("d5", "pull_request", pullRequest("opened", 101, 202, 42, SHA_B));

        assertThat(deliveryCount()).isEqualTo(5);
        assertThat(jobCount()).isEqualTo(5);
    }

    @Test
    void signedRepositoryReassignmentIsRetainedButCreatesNoForeignTenantJob() {
        ingest("owner", "pull_request", pullRequest("opened", 101, 202, 42, SHA_A));
        ingest("attacker", "pull_request", pullRequest("opened", 102, 202, 43, SHA_B));

        assertThat(deliveryCount()).isEqualTo(2);
        assertThat(jobCount()).isOne();
        assertThat(jdbcClient.sql("SELECT count(*) FROM github_installations")
                .query(Long.class).single()).isOne();
        assertThat(reviewTargets()).containsExactly(new StoredTarget(101, 202, 42, SHA_A));
    }

    @Test
    void signedIncompleteRelevantEventIsRetainedWithoutAJob() {
        ingest("delivery-incomplete", "pull_request", "{\"action\":\"opened\"}");

        assertThat(deliveryCount()).isOne();
        assertThat(jobCount()).isZero();
    }

    @Test
    void invalidSignatureOrMalformedJsonCreatesNeitherRecord() {
        byte[] valid = pullRequest("opened", 101, 202, 42, SHA_A).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.ingest(valid, "sha256=" + "0".repeat(64), "bad-signature",
                "pull_request")).isInstanceOf(RuntimeException.class);

        byte[] malformed = "{not-json}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.ingest(malformed, sign(malformed), "bad-json", "pull_request"))
                .isInstanceOf(RuntimeException.class);

        assertThat(deliveryCount()).isZero();
        assertThat(jobCount()).isZero();
    }

    @Test
    void concurrentDistinctDeliveriesForOneRevisionCreateExactlyOneJob() throws Exception {
        int callers = 12;
        String payload = pullRequest("opened", 101, 202, 42, SHA_A);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
            for (int index = 0; index < callers; index++) {
                int delivery = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    ingest("concurrent-" + delivery, "pull_request", payload);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }

        assertThat(deliveryCount()).isEqualTo(callers);
        assertThat(jobCount()).isOne();
    }

    private void ingest(String deliveryId, String event, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        try {
            mockMvc.perform(post("/api/webhooks/github")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Hub-Signature-256", sign(body))
                            .header("X-GitHub-Delivery", deliveryId)
                            .header("X-GitHub-Event", event)
                            .content(body))
                    .andExpect(status().isAccepted());
        } catch (Exception exception) {
            throw new IllegalStateException("synthetic webhook request failed", exception);
        }
    }

    private String pullRequest(String action, long installation, long repository, int number, String sha) {
        return """
                {"action":"%s","installation":{"id":%d},"repository":{"id":%d},
                 "number":%d,"pull_request":{"head":{"sha":"%s"}}}
                """.formatted(action, installation, repository, number, sha);
    }

    private String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("test HMAC setup failed", exception);
        }
    }

    private long deliveryCount() {
        return jdbcClient.sql("SELECT count(*) FROM github_webhook_deliveries")
                .query(Long.class).single();
    }

    private long jobCount() {
        return jdbcClient.sql("SELECT count(*) FROM review_jobs")
                .query(Long.class).single();
    }

    private List<StoredTarget> reviewTargets() {
        return jdbcClient.sql("""
                        SELECT github_installation_id, github_repository_id,
                               github_pull_request_number, github_head_sha
                        FROM review_jobs
                        """)
                .query((resultSet, rowNumber) -> new StoredTarget(
                        resultSet.getLong("github_installation_id"),
                        resultSet.getLong("github_repository_id"),
                        resultSet.getInt("github_pull_request_number"),
                        resultSet.getString("github_head_sha")))
                .list();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test synchronization timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test synchronization interrupted", exception);
        }
    }

    private record StoredTarget(long installationId, long repositoryId, int pullRequestNumber, String headSha) {
    }
}
