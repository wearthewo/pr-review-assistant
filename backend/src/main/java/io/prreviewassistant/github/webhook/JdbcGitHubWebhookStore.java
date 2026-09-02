package io.prreviewassistant.github.webhook;

import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcGitHubWebhookStore implements GitHubWebhookStore {

    private final JdbcClient jdbcClient;

    public JdbcGitHubWebhookStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public StoreResult store(GitHubWebhookDelivery delivery) {
        int rows = jdbcClient.sql("""
                        INSERT INTO github_webhook_deliveries
                            (github_delivery_id, event_name, received_at, payload)
                        VALUES (:deliveryId, :eventName, :receivedAt, :payload)
                        ON CONFLICT (github_delivery_id) DO NOTHING
                        """)
                .param("deliveryId", delivery.deliveryId())
                .param("eventName", delivery.eventName())
                .param("receivedAt", delivery.receivedAt().atOffset(ZoneOffset.UTC))
                .param("payload", delivery.rawPayload())
                .update();
        return rows == 1 ? StoreResult.ACCEPTED : StoreResult.DUPLICATE;
    }
}
