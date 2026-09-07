package io.prreviewassistant.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("review.ai")
@Validated
public record ReviewAiProperties(boolean enabled, @NotNull Provider provider,
                                 @Min(1) @Max(500_000) int maxInputChars,
                                 @NotNull DataSize maxSchemaSize) {
    private static final long HARD_MAX_SCHEMA_BYTES = 256 * 1024;

    public ReviewAiProperties {
        long schemaBytes = maxSchemaSize == null ? -1 : maxSchemaSize.toBytes();
        if (maxInputChars < 1 || maxInputChars > 500_000
                || schemaBytes < 1 || schemaBytes > HARD_MAX_SCHEMA_BYTES) {
            throw new IllegalArgumentException("AI transport limits are invalid");
        }
    }

    public int maxSchemaBytes() { return Math.toIntExact(maxSchemaSize.toBytes()); }
    public enum Provider { OPENAI }
}
