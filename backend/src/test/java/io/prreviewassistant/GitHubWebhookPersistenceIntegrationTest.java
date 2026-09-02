package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.prreviewassistant.github.webhook.GitHubWebhookDelivery;
import io.prreviewassistant.github.webhook.GitHubWebhookStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {
        "DB_JDBC_URL=jdbc:postgresql://invalid:5432/invalid",
        "DB_USERNAME=test",
        "DB_PASSWORD=test",
        "GITHUB_APP_ID=test-app-id",
        "GITHUB_PRIVATE_KEY_PATH=unused-test-key.pem",
        "GITHUB_WEBHOOK_SECRET=test-webhook-secret-with-entropy"
})
@Import(PostgreSqlTestConfiguration.class)
class GitHubWebhookPersistenceIntegrationTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-09-02T12:34:56.123456Z");

    @Autowired
    private GitHubWebhookStore store;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearDeliveries() {
        jdbcClient.sql("DELETE FROM github_webhook_deliveries").update();
    }

    @Test
    void persistsTheCompleteIngestionEnvelopeAsRawText() {
        String payload = "{ \"z\": 1, \"a\": \"preserve this layout\" }\r\n";

        assertThat(store.store(delivery("delivery-persisted", "installation", payload)))
                .isEqualTo(GitHubWebhookStore.StoreResult.ACCEPTED);

        StoredDelivery stored = jdbcClient.sql("""
                        SELECT github_delivery_id, event_name, received_at, payload
                        FROM github_webhook_deliveries
                        WHERE github_delivery_id = :deliveryId
                        """)
                .param("deliveryId", "delivery-persisted")
                .query((resultSet, rowNumber) -> new StoredDelivery(
                        resultSet.getString("github_delivery_id"),
                        resultSet.getString("event_name"),
                        resultSet.getObject("received_at", java.time.OffsetDateTime.class).toInstant(),
                        resultSet.getString("payload")))
                .single();

        assertThat(stored).isEqualTo(new StoredDelivery(
                "delivery-persisted", "installation", RECEIVED_AT, payload));
    }

    @Test
    void duplicateDeliveryIsAnIdempotentSuccessWithOneDurableRow() {
        GitHubWebhookDelivery delivery = delivery("delivery-duplicate", "ping", "{}");

        assertThat(store.store(delivery)).isEqualTo(GitHubWebhookStore.StoreResult.ACCEPTED);
        assertThat(store.store(delivery)).isEqualTo(GitHubWebhookStore.StoreResult.DUPLICATE);
        assertThat(count("delivery-duplicate")).isOne();
    }

    @Test
    void databaseUniqueConstraintIsTheUltimateDuplicateSafeguard() {
        String sql = """
                INSERT INTO github_webhook_deliveries
                    (github_delivery_id, event_name, received_at, payload)
                VALUES (:deliveryId, 'ping', now(), '{}')
                """;
        jdbcClient.sql(sql).param("deliveryId", "delivery-db-constraint").update();

        assertThatThrownBy(() -> jdbcClient.sql(sql)
                        .param("deliveryId", "delivery-db-constraint")
                        .update())
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(count("delivery-db-constraint")).isOne();
    }

    @Test
    void concurrentIdenticalDeliveriesCreateExactlyOneRow() throws Exception {
        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        GitHubWebhookDelivery delivery = delivery("delivery-concurrent", "push", "{\"ref\":\"main\"}");
        List<Future<GitHubWebhookStore.StoreResult>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent test start barrier timed out");
                    }
                    return store.store(delivery);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<GitHubWebhookStore.StoreResult> results = new ArrayList<>();
            for (Future<GitHubWebhookStore.StoreResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            assertThat(results).containsOnlyOnce(GitHubWebhookStore.StoreResult.ACCEPTED);
            assertThat(results).filteredOn(result -> result == GitHubWebhookStore.StoreResult.DUPLICATE)
                    .hasSize(callers - 1);
        }

        assertThat(count("delivery-concurrent")).isOne();
    }

    private GitHubWebhookDelivery delivery(String id, String event, String payload) {
        return new GitHubWebhookDelivery(id, event, RECEIVED_AT, payload);
    }

    private long count(String deliveryId) {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM github_webhook_deliveries
                        WHERE github_delivery_id = :deliveryId
                        """)
                .param("deliveryId", deliveryId)
                .query(Long.class)
                .single();
    }

    private record StoredDelivery(String deliveryId, String eventName, Instant receivedAt, String payload) {
    }
}
