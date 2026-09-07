package io.prreviewassistant.ai;

import java.util.Objects;

public record StructuredAiResponse(String structuredOutput, AiTokenUsage usage,
                                   AiExecutionMetadata executionMetadata) {
    public StructuredAiResponse {
        Objects.requireNonNull(structuredOutput, "structuredOutput is required");
        Objects.requireNonNull(usage, "usage is required");
        Objects.requireNonNull(executionMetadata, "executionMetadata is required");
        if (structuredOutput.isBlank()) throw new IllegalArgumentException("structuredOutput must not be blank");
    }

    @Override public String toString() {
        return "StructuredAiResponse[structuredOutput=<redacted>, usage=" + usage
                + ", executionMetadata=" + executionMetadata + "]";
    }
}
