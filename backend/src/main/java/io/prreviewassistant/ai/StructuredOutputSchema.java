package io.prreviewassistant.ai;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record StructuredOutputSchema(String name, String jsonSchema, boolean strict) {
    public StructuredOutputSchema {
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(jsonSchema, "jsonSchema is required");
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("schema name is invalid");
        }
        if (jsonSchema.isBlank()) {
            throw new IllegalArgumentException("jsonSchema must not be blank");
        }
    }

    public int sizeBytes() { return jsonSchema.getBytes(StandardCharsets.UTF_8).length; }

    @Override public String toString() {
        return "StructuredOutputSchema[name=" + name + ", jsonSchema=<redacted>, strict=" + strict + "]";
    }
}
