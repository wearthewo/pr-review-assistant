package io.prreviewassistant.ai.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.prreviewassistant.ai.*;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.*;
import java.util.OptionalLong;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class OpenAiProviderTest {
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"result\":{\"type\":\"string\"}},\"required\":[\"result\"],\"additionalProperties\":false}";

    @Test void mapsRequestAndProviderUsageWithoutLeakingSdkTypes() {
        var captured = new java.util.concurrent.atomic.AtomicReference<OpenAiInvocation>();
        OpenAiProvider provider = provider(invocation -> {
            captured.set(invocation);
            return new OpenAiGatewayResponse("{\"result\":\"ok\"}", "resp_safe", 100L, 20L, 40L, 10L, 140L);
        }, 1_000, 4_096);
        StructuredAiResponse response = provider.generateStructured(request("untrusted input", 2_048));

        assertThat(captured.get().model()).isEqualTo("gpt-5.6-terra");
        assertThat(captured.get().reasoningEffort()).isEqualTo(AiReasoningEffort.LOW);
        assertThat(captured.get().instructions()).isEqualTo("application instructions");
        assertThat(captured.get().input()).isEqualTo("untrusted input");
        assertThat(captured.get().schemaName()).isEqualTo("synthetic");
        assertThat(captured.get().strict()).isTrue();
        assertThat(response.usage().cachedInputTokens()).hasValue(20);
        assertThat(response.usage().reasoningTokens()).hasValue(10);
        assertThat(response.executionMetadata().maximumAttempts()).isEqualTo(2);
    }

    @Test void missingUsageIsRepresentedWithoutInventingCounts() {
        var provider = provider(invocation -> new OpenAiGatewayResponse(
                "{\"result\":\"ok\"}", null, null, null, null, null, null), 1_000, 2_048);
        assertThat(provider.generateStructured(request("data", 100)).usage())
                .isEqualTo(AiTokenUsage.unavailable());
    }

    @Test void exactInputLimitPassesAndOverLimitNeverCallsProvider() {
        AtomicInteger calls = new AtomicInteger();
        var within = request("data", 100);
        var provider = provider(invocation -> { calls.incrementAndGet(); return success(); }, within.inputCharacters(), 2_048);
        provider.generateStructured(within);
        assertThat(calls).hasValue(1);
        var smallProvider = provider(invocation -> { calls.incrementAndGet(); return success(); }, 5, 2_048);
        assertThatThrownBy(() -> smallProvider.generateStructured(request("data", 100)))
                .isInstanceOf(AiProviderException.class)
                .extracting(e -> ((AiProviderException)e).errorType()).isEqualTo(AiProviderErrorType.INPUT_TOO_LARGE);
        assertThat(calls).hasValue(1);
    }

    @Test void schemaAndOutputLimitsRejectBeforeProviderCall() {
        AtomicInteger calls = new AtomicInteger();
        var schemaLimited = provider(invocation -> { calls.incrementAndGet(); return success(); }, 1_000, 8);
        assertThatThrownBy(() -> schemaLimited.generateStructured(request("data", 100)))
                .isInstanceOf(AiProviderException.class)
                .extracting(e -> ((AiProviderException)e).errorType()).isEqualTo(AiProviderErrorType.INPUT_TOO_LARGE);
        var outputLimited = provider(invocation -> { calls.incrementAndGet(); return success(); }, 1_000, 2_048);
        assertThatThrownBy(() -> outputLimited.generateStructured(request("data", 3_000)))
                .isInstanceOf(AiProviderException.class)
                .extracting(e -> ((AiProviderException)e).errorType()).isEqualTo(AiProviderErrorType.INVALID_REQUEST);
        assertThat(calls).hasValue(0);
    }

    @Test void malformedSchemaAndMalformedOrScalarOutputAreTerminal() {
        var provider = provider(invocation -> new OpenAiGatewayResponse("not-json", null, null, null, null, null, null), 1_000, 2_048);
        assertError(provider, request("data", 100), AiProviderErrorType.OUTPUT_INVALID);
        var scalar = provider(invocation -> new OpenAiGatewayResponse("\"prose\"", null, null, null, null, null, null), 1_000, 2_048);
        assertError(scalar, request("data", 100), AiProviderErrorType.OUTPUT_INVALID);
    }

    @Test void gatewayClassificationsRemainSafeAndPreserveRetryability() {
        for (AiProviderErrorType type : AiProviderErrorType.values()) {
            var provider = provider(invocation -> { throw new OpenAiGatewayException(type, new RuntimeException("sk-secret source")); }, 1_000, 2_048);
            assertThatThrownBy(() -> provider.generateStructured(request("source", 100)))
                    .isInstanceOf(AiProviderException.class)
                    .hasMessageNotContaining("sk-secret")
                    .hasMessageNotContaining("source")
                    .extracting(e -> ((AiProviderException)e).retryable()).isEqualTo(type.retryable());
        }
    }

    @Test void sharedProviderIsSafeForConcurrentCalls() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var provider = provider(invocation -> { calls.incrementAndGet(); return success(); }, 1_000, 2_048);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(i -> executor.submit(() -> provider.generateStructured(request("data", 100))))
                    .toList();
            for (Future<StructuredAiResponse> future : futures) assertThat(future.get()).isNotNull();
        }
        assertThat(calls).hasValue(20);
    }

    private static void assertError(OpenAiProvider provider, StructuredAiRequest request, AiProviderErrorType type) {
        assertThatThrownBy(() -> provider.generateStructured(request)).isInstanceOf(AiProviderException.class)
                .extracting(e -> ((AiProviderException)e).errorType()).isEqualTo(type);
    }

    private static OpenAiGatewayResponse success() {
        return new OpenAiGatewayResponse("{\"result\":\"ok\"}", "id", 1L, 0L, 1L, 0L, 2L);
    }

    private static StructuredAiRequest request(String input, int maxOutputTokens) {
        return new StructuredAiRequest("application instructions", input,
                new StructuredOutputSchema("synthetic", SCHEMA, true),
                new AiGenerationProfile(AiModelTier.BALANCED, AiReasoningEffort.LOW, maxOutputTokens));
    }

    private static OpenAiProvider provider(OpenAiGateway gateway, int inputLimit, int schemaLimit) {
        return new OpenAiProvider(gateway,
                new OpenAiProperties("sk-never-logged", "gpt-5.6-terra", AiReasoningEffort.LOW,
                        2_048, Duration.ofSeconds(30), 1),
                new ReviewAiProperties(true, ReviewAiProperties.Provider.OPENAI, inputLimit, DataSize.ofBytes(schemaLimit)),
                new ObjectMapper(), Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC));
    }
}
