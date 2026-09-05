package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.prreviewassistant.github.webhook.GitHubWebhookService;
import io.prreviewassistant.review.job.ReviewJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
class GitHubWebhookAtomicityIntegrationTest {

    private static final String SECRET = "test-webhook-secret-with-entropy";

    @Autowired
    private GitHubWebhookService service;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private ReviewJobService reviewJobService;

    @BeforeEach
    void clearState() {
        jdbcClient.sql("DELETE FROM review_jobs").update();
        jdbcClient.sql("DELETE FROM github_webhook_deliveries").update();
    }

    @Test
    void jobInsertionFailureRollsBackTheAlreadyAttemptedWebhookInsert() {
        when(reviewJobService.createForReviewTarget(any()))
                .thenThrow(new DataAccessResourceFailureException("synthetic insertion failure"));
        byte[] body = ("{\"action\":\"opened\",\"installation\":{\"id\":1},"
                + "\"repository\":{\"id\":2},\"number\":3,\"pull_request\":{\"head\":{"
                + "\"sha\":\"" + "a".repeat(40) + "\"}}}").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.ingest(body, sign(body), "atomic-failure", "pull_request"))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageNotContaining(new String(body, StandardCharsets.UTF_8));

        assertThat(jdbcClient.sql("SELECT count(*) FROM github_webhook_deliveries")
                .query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM review_jobs")
                .query(Long.class).single()).isZero();
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
}
