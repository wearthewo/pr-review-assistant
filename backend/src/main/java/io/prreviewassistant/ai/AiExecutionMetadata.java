package io.prreviewassistant.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record AiExecutionMetadata(String provider, String model, Optional<String> providerRequestId,
                                  Duration duration, int maximumAttempts) {
    public AiExecutionMetadata {
        Objects.requireNonNull(provider, "provider is required");
        Objects.requireNonNull(model, "model is required");
        providerRequestId = providerRequestId == null ? Optional.empty() : providerRequestId;
        Objects.requireNonNull(duration, "duration is required");
        if (provider.isBlank() || model.isBlank() || duration.isNegative() || maximumAttempts < 1) {
            throw new IllegalArgumentException("execution metadata is invalid");
        }
    }
}
