package io.prreviewassistant.ai.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.errors.*;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;
import io.prreviewassistant.ai.AiProviderErrorType;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

final class SdkOpenAiGateway implements OpenAiGateway {
    private final OpenAIClient client;

    SdkOpenAiGateway(OpenAIClient client) { this.client = client; }

    @Override
    public OpenAiGatewayResponse generate(OpenAiInvocation invocation) {
        try {
            Response response = client.responses().create(toParams(invocation));
            return toResponse(response);
        } catch (OpenAIException exception) {
            throw new OpenAiGatewayException(classify(exception), exception);
        }
    }

    static ResponseCreateParams toParams(OpenAiInvocation invocation) {
        if (!invocation.schema().isObject()) throw new OpenAiGatewayException(AiProviderErrorType.INVALID_REQUEST, null);
        Map<String, JsonValue> schemaProperties = new LinkedHashMap<>();
        invocation.schema().properties().forEach(entry ->
                schemaProperties.put(entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
        var schema = ResponseFormatTextJsonSchemaConfig.Schema.builder()
                .additionalProperties(schemaProperties).build();
        var format = ResponseFormatTextJsonSchemaConfig.builder()
                .name(invocation.schemaName()).schema(schema).strict(invocation.strict()).build();
        return ResponseCreateParams.builder()
                .model(invocation.model())
                .instructions(invocation.instructions())
                .input(invocation.input())
                .reasoning(Reasoning.builder().effort(reasoningEffort(invocation)).build())
                .maxOutputTokens(invocation.maxOutputTokens())
                .text(ResponseTextConfig.builder().format(format).build())
                .store(false)
                .build();
    }

    private static ReasoningEffort reasoningEffort(OpenAiInvocation invocation) {
        return switch (invocation.reasoningEffort()) {
            case NONE -> ReasoningEffort.NONE;
            case LOW -> ReasoningEffort.LOW;
            case MEDIUM -> ReasoningEffort.MEDIUM;
            case HIGH -> ReasoningEffort.HIGH;
        };
    }

    private static OpenAiGatewayResponse toResponse(Response response) {
        if (response.status().isPresent() && !ResponseStatus.COMPLETED.equals(response.status().get())) {
            throw new OpenAiGatewayException(AiProviderErrorType.OUTPUT_INVALID, null);
        }
        String output = null;
        for (ResponseOutputItem item : response.output()) {
            if (!item.isMessage()) continue;
            for (ResponseOutputMessage.Content content : item.asMessage().content()) {
                if (content.isRefusal()) throw new OpenAiGatewayException(AiProviderErrorType.OUTPUT_INVALID, null);
                if (content.isOutputText()) {
                    if (output != null) throw new OpenAiGatewayException(AiProviderErrorType.OUTPUT_INVALID, null);
                    output = content.asOutputText().text();
                }
            }
        }
        if (output == null || output.isBlank()) throw new OpenAiGatewayException(AiProviderErrorType.OUTPUT_INVALID, null);
        if (response.usage().isEmpty()) {
            return new OpenAiGatewayResponse(output, response.id(), null, null, null, null, null);
        }
        ResponseUsage usage = response.usage().get();
        return new OpenAiGatewayResponse(output, response.id(), usage.inputTokens(),
                usage.inputTokensDetails().cachedTokens(), usage.outputTokens(),
                usage.outputTokensDetails().reasoningTokens(), usage.totalTokens());
    }

    static AiProviderErrorType classify(OpenAIException exception) {
        if (exception instanceof UnauthorizedException) return AiProviderErrorType.AUTHENTICATION;
        if (exception instanceof PermissionDeniedException) return AiProviderErrorType.PERMISSION_DENIED;
        if (exception instanceof RateLimitException) return AiProviderErrorType.RATE_LIMITED;
        if (exception instanceof BadRequestException || exception instanceof UnprocessableEntityException) {
            return AiProviderErrorType.INVALID_REQUEST;
        }
        if (exception instanceof NotFoundException) return AiProviderErrorType.MODEL_UNAVAILABLE;
        if (exception instanceof InternalServerException || exception instanceof OpenAIRetryableException) {
            return AiProviderErrorType.TRANSIENT;
        }
        if (exception instanceof OpenAIIoException) {
            return hasTimeoutCause(exception) ? AiProviderErrorType.TIMEOUT : AiProviderErrorType.TRANSIENT;
        }
        if (exception instanceof OpenAIServiceException serviceException) {
            int status = serviceException.statusCode();
            if (status == 408) return AiProviderErrorType.TIMEOUT;
            if (status == 429) return AiProviderErrorType.RATE_LIMITED;
            if (status == 409 || status >= 500) return AiProviderErrorType.TRANSIENT;
            if (status == 401) return AiProviderErrorType.AUTHENTICATION;
            if (status == 403) return AiProviderErrorType.PERMISSION_DENIED;
            if (status == 400 || status == 422) return AiProviderErrorType.INVALID_REQUEST;
        }
        return exception instanceof OpenAIInvalidDataException
                ? AiProviderErrorType.OUTPUT_INVALID : AiProviderErrorType.PROVIDER_FAILURE;
    }

    private static boolean hasTimeoutCause(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof InterruptedIOException) return true;
        }
        return false;
    }
}
