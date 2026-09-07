package io.prreviewassistant.ai.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.prreviewassistant.ai.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

public final class OpenAiProvider implements AiProvider {
    private final OpenAiGateway gateway;
    private final OpenAiProperties openAiProperties;
    private final ReviewAiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    OpenAiProvider(OpenAiGateway gateway, OpenAiProperties openAiProperties, ReviewAiProperties aiProperties,
                   ObjectMapper objectMapper, Clock clock) {
        this.gateway = gateway;
        this.openAiProperties = openAiProperties;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public StructuredAiResponse generateStructured(StructuredAiRequest request) {
        validateBounds(request);
        JsonNode schema = parseJson(request.outputSchema().jsonSchema(), AiProviderErrorType.INVALID_REQUEST);
        Instant startedAt = clock.instant();
        try {
            var profile = request.generationProfile();
            OpenAiGatewayResponse response = gateway.generate(new OpenAiInvocation(
                    openAiProperties.model(), profile.reasoningEffort(), profile.maxOutputTokens(),
                    request.instructions(), request.input(), request.outputSchema().name(), schema,
                    request.outputSchema().strict()));
            JsonNode output = parseJson(response.structuredOutput(), AiProviderErrorType.OUTPUT_INVALID);
            if (!output.isContainerNode()) throw new AiProviderException(AiProviderErrorType.OUTPUT_INVALID);
            return new StructuredAiResponse(response.structuredOutput(), usage(response),
                    new AiExecutionMetadata("openai", openAiProperties.model(),
                            safeRequestId(response.requestId()), durationSince(startedAt),
                            openAiProperties.maximumAttempts()));
        } catch (OpenAiGatewayException exception) {
            throw new AiProviderException(exception.errorType());
        }
    }

    private void validateBounds(StructuredAiRequest request) {
        if (request.inputCharacters() > aiProperties.maxInputChars()
                || request.outputSchema().sizeBytes() > aiProperties.maxSchemaBytes()) {
            throw new AiProviderException(AiProviderErrorType.INPUT_TOO_LARGE);
        }
        if (request.generationProfile().maxOutputTokens() > openAiProperties.maxOutputTokens()) {
            throw new AiProviderException(AiProviderErrorType.INVALID_REQUEST);
        }
        if (!request.outputSchema().strict()
                || request.generationProfile().reasoningEffort() != openAiProperties.reasoningEffort()) {
            throw new AiProviderException(AiProviderErrorType.INVALID_REQUEST);
        }
    }

    private JsonNode parseJson(String value, AiProviderErrorType errorType) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new AiProviderException(errorType); }
    }

    private AiTokenUsage usage(OpenAiGatewayResponse response) {
        if (response.inputTokens() == null || response.outputTokens() == null || response.totalTokens() == null) {
            return AiTokenUsage.unavailable();
        }
        return new AiTokenUsage(optional(response.inputTokens()), optional(response.cachedInputTokens()),
                optional(response.outputTokens()), optional(response.reasoningTokens()), optional(response.totalTokens()));
    }

    private OptionalLong optional(Long value) { return value == null ? OptionalLong.empty() : OptionalLong.of(value); }
    private Optional<String> safeRequestId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}") ? Optional.of(value) : Optional.empty();
    }
    private Duration durationSince(Instant startedAt) {
        Duration duration = Duration.between(startedAt, clock.instant());
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
