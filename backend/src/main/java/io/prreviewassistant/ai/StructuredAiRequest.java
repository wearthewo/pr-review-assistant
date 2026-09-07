package io.prreviewassistant.ai;

import java.util.Objects;

public record StructuredAiRequest(String instructions, String input, StructuredOutputSchema outputSchema,
                                  AiGenerationProfile generationProfile) {
    public StructuredAiRequest {
        Objects.requireNonNull(instructions, "instructions are required");
        Objects.requireNonNull(input, "input is required");
        Objects.requireNonNull(outputSchema, "outputSchema is required");
        Objects.requireNonNull(generationProfile, "generationProfile is required");
        if (instructions.isBlank() || input.isBlank()) {
            throw new IllegalArgumentException("instructions and input must not be blank");
        }
    }

    public int inputCharacters() { return Math.addExact(instructions.length(), input.length()); }

    @Override public String toString() {
        return "StructuredAiRequest[instructions=<redacted>, input=<redacted>, outputSchema="
                + outputSchema + ", generationProfile=" + generationProfile + "]";
    }
}
