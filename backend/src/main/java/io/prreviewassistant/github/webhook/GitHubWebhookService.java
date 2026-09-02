package io.prreviewassistant.github.webhook;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public final class GitHubWebhookService {

    static final int MAX_DELIVERY_ID_LENGTH = 128;
    static final int MAX_EVENT_NAME_LENGTH = 64;

    private final GitHubWebhookSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final GitHubWebhookStore store;
    private final Clock clock;

    public GitHubWebhookService(
            GitHubWebhookSignatureVerifier signatureVerifier,
            ObjectMapper objectMapper,
            GitHubWebhookStore store,
            Clock clock) {
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
        this.store = store;
        this.clock = clock;
    }

    public GitHubWebhookStore.StoreResult ingest(
            byte[] rawBody,
            String signature,
            String deliveryId,
            String eventName) {
        if (!signatureVerifier.isValid(rawBody, signature)) {
            throw GitHubWebhookException.unauthorized();
        }

        validateHeader(deliveryId, MAX_DELIVERY_ID_LENGTH);
        validateHeader(eventName, MAX_EVENT_NAME_LENGTH);
        validateJson(rawBody);

        GitHubWebhookDelivery delivery = new GitHubWebhookDelivery(
                deliveryId, eventName, clock.instant(), decodeUtf8(rawBody));
        return store.store(delivery);
    }

    private void validateJson(byte[] rawBody) {
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
