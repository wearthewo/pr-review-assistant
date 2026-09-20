package io.prreviewassistant.github.webhook;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import io.prreviewassistant.observability.ApplicationMetrics;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public final class GitHubWebhookService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubWebhookService.class);

    static final int MAX_DELIVERY_ID_LENGTH = 128;
    static final int MAX_EVENT_NAME_LENGTH = 64;

    private final GitHubWebhookSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final GitHubWebhookAcceptanceService acceptanceService;
    private final Clock clock;
    private final ApplicationMetrics metrics;

    @Autowired
    public GitHubWebhookService(
            GitHubWebhookSignatureVerifier signatureVerifier,
            ObjectMapper objectMapper,
            GitHubWebhookAcceptanceService acceptanceService,
            Clock clock,
            ApplicationMetrics metrics) {
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
        this.acceptanceService = acceptanceService;
        this.clock = clock;
        this.metrics = metrics;
    }

    public GitHubWebhookService(
            GitHubWebhookSignatureVerifier signatureVerifier,
            ObjectMapper objectMapper,
            GitHubWebhookAcceptanceService acceptanceService,
            Clock clock) {
        this(signatureVerifier, objectMapper, acceptanceService, clock, ApplicationMetrics.noop());
    }

    public GitHubWebhookStore.StoreResult ingest(
            byte[] rawBody,
            String signature,
            String deliveryId,
            String eventName) {
        Instant startedAt = clock.instant();
        if (!signatureVerifier.isValid(rawBody, signature)) {
            throw GitHubWebhookException.unauthorized();
        }

        validateHeader(deliveryId, MAX_DELIVERY_ID_LENGTH);
        validateHeader(eventName, MAX_EVENT_NAME_LENGTH);
        JsonNode payload = validateJson(rawBody);

        GitHubWebhookDelivery delivery = new GitHubWebhookDelivery(
                deliveryId, eventName, clock.instant(), decodeUtf8(rawBody));
        GitHubWebhookStore.StoreResult result = acceptanceService.accept(delivery, payload);
        String outcome = result == GitHubWebhookStore.StoreResult.ACCEPTED ? "accepted" : "duplicate";
        metrics.webhook(outcome, "none", Duration.between(startedAt, clock.instant()));
        LOGGER.atInfo().addKeyValue("event", "github_webhook_ingested")
                .addKeyValue("github_delivery_id", deliveryId)
                .addKeyValue("github_event", eventName)
                .addKeyValue("outcome", outcome)
                .log("GitHub webhook ingestion finished");
        return result;
    }

    private JsonNode validateJson(byte[] rawBody) {
        if (rawBody.length == 0) {
            throw GitHubWebhookException.invalidRequest();
        }
        try {
            JsonNode document = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(rawBody);
            if (document == null || document.isMissingNode()) {
                throw GitHubWebhookException.invalidRequest();
            }
            return document;
        } catch (JacksonException exception) {
            throw GitHubWebhookException.invalidRequest();
        }
    }

    private String decodeUtf8(byte[] rawBody) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawBody))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw GitHubWebhookException.invalidRequest();
        }
    }

    private void validateHeader(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw GitHubWebhookException.invalidRequest();
        }
    }
}
