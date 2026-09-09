package io.prreviewassistant.review.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("review.repository-config")
@Validated
public record RepositoryConfigProperties(@NotNull DataSize maxSize) {
    public static final int HARD_MAX_BYTES = 64 * 1024;

    public RepositoryConfigProperties {
        long bytes = maxSize == null ? -1 : maxSize.toBytes();
        if (bytes < 1 || bytes > HARD_MAX_BYTES) {
            throw new IllegalArgumentException("repository config max size must be between 1 byte and 64 KiB");
        }
    }

    public int maxBytes() {
        return Math.toIntExact(maxSize.toBytes());
    }
}
