package io.prreviewassistant.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.prreviewassistant.observability.ApplicationMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

class ObservabilitySensitiveLoggingTest {

    @Test
    void representativeWebhookLogsExcludePayloadSignatureAndSecretSentinels() {
        String secret = "test-webhook-secret-SENTINEL";
        String payloadSentinel = "BEGIN PRIVATE KEY SOURCE_PROMPT_SENTINEL";
        byte[] body = ("{\"content\":\"" + payloadSentinel + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        CapturedLogs serviceLogs = appender(GitHubWebhookService.class);
        CapturedLogs adviceLogs = appender(GitHubWebhookControllerAdvice.class);
        ApplicationMetrics metrics = new ApplicationMetrics(new SimpleMeterRegistry());
        GitHubWebhookService service = new GitHubWebhookService(
                new GitHubWebhookSignatureVerifier(
                        new GitHubWebhookProperties(secret, DataSize.ofMegabytes(1))),
                JsonMapper.builder().build(),
                new GitHubWebhookAcceptanceService(
                        ignored -> GitHubWebhookStore.StoreResult.ACCEPTED,
                        mock(GitHubWebhookEventProcessor.class)),
                Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC), metrics);

        try {
            service.ingest(body, WebhookTestSupport.sign(secret, body), "safe-delivery", "ping");
            new GitHubWebhookControllerAdvice(metrics).handleWebhookError(GitHubWebhookException.unauthorized());

            String rendered = java.util.stream.Stream.concat(
                            serviceLogs.appender().list.stream(), adviceLogs.appender().list.stream())
                    .map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs())
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(rendered)
                    .contains("github_webhook_ingested", "github_webhook_rejected", "safe-delivery")
                    .doesNotContain(secret, payloadSentinel, "sha256=", "Authorization", "Bearer");
        } finally {
            serviceLogs.close();
            adviceLogs.close();
        }
    }

    private static CapturedLogs appender(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new CapturedLogs(logger, appender);
    }

    private record CapturedLogs(Logger logger, ListAppender<ILoggingEvent> appender) {
        void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
