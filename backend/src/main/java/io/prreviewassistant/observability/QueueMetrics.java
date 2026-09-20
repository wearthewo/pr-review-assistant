package io.prreviewassistant.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Scrape-time aggregate queue metrics backed by existing partial polling indexes. */
public final class QueueMetrics {

    private static final String DEPTH = ApplicationMetrics.PREFIX + ".queue.depth";
    private static final String OLDEST_AGE = ApplicationMetrics.PREFIX + ".queue.oldest.actionable.age";

    private final JdbcClient jdbc;
    private final Clock clock;

    QueueMetrics(MeterRegistry registry, JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        registerQueue(registry, "review", "review_jobs");
        registerQueue(registry, "publication", "publication_jobs");
    }

    private void registerQueue(MeterRegistry registry, String queue, String table) {
        registerDepth(registry, queue, "ready", table,
                "status = 'READY' AND next_attempt_at <= :now");
        registerDepth(registry, queue, "delayed", table,
                "status = 'READY' AND next_attempt_at > :now");
        registerDepth(registry, queue, "processing", table,
                "status = 'PROCESSING' AND claim_expires_at > :now");
        registerDepth(registry, queue, "stale", table,
                "status = 'PROCESSING' AND claim_expires_at <= :now");
        Gauge.builder(OLDEST_AGE, this, metrics -> metrics.oldestActionableAge(table))
                .description("Age in seconds of the oldest due or stale queue item")
                .tag("queue", queue)
                .register(registry);
    }

    private void registerDepth(MeterRegistry registry, String queue, String state,
            String table, String predicate) {
        Gauge.builder(DEPTH, this, metrics -> metrics.count(table, predicate))
                .description("Current PostgreSQL queue depth")
                .tag("queue", queue)
                .tag("state", state)
                .register(registry);
    }

    private double count(String table, String predicate) {
        try {
            return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + predicate)
                    .param("now", utc(clock.instant()))
                    .query(Long.class)
                    .single();
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private double oldestActionableAge(String table) {
        try {
            OffsetDateTime oldest = jdbc.sql("SELECT min(actionable_at) FROM ("
                            + "SELECT next_attempt_at AS actionable_at FROM " + table
                            + " WHERE status = 'READY' AND next_attempt_at <= :now "
                            + "UNION ALL SELECT claim_expires_at FROM " + table
                            + " WHERE status = 'PROCESSING' AND claim_expires_at <= :now) actionable")
                    .param("now", utc(clock.instant()))
                    .query(OffsetDateTime.class)
                    .optional()
                    .orElse(null);
            return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), clock.instant()).toSeconds());
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
