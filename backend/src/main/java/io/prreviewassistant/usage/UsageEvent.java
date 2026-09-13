package io.prreviewassistant.usage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record UsageEvent(UUID id, UUID tenantId, UUID tenantRepositoryId, UUID reviewJobId,
        UsageType usageType, UsageStatus status, Instant occurredAt, Optional<Instant> consumedAt,
        Optional<Instant> releasedAt, Optional<UsageMeasurement> measurement) {
    public UsageEvent {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(tenantRepositoryId, "tenantRepositoryId is required");
        Objects.requireNonNull(reviewJobId, "reviewJobId is required");
        Objects.requireNonNull(usageType, "usageType is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        consumedAt = consumedAt == null ? Optional.empty() : consumedAt;
        releasedAt = releasedAt == null ? Optional.empty() : releasedAt;
        measurement = measurement == null ? Optional.empty() : measurement;
        boolean validState = switch (status) {
            case RESERVED -> consumedAt.isEmpty() && releasedAt.isEmpty() && measurement.isEmpty();
            case CONSUMED -> consumedAt.isPresent() && releasedAt.isEmpty() && measurement.isPresent();
            case RELEASED -> consumedAt.isEmpty() && releasedAt.isPresent() && measurement.isEmpty();
        };
        if (!validState) {
            throw new IllegalArgumentException("usage event state is invalid");
        }
    }

    @Override
    public String toString() {
        return "UsageEvent[id=" + id + ", tenantId=" + tenantId + ", usageType=" + usageType
                + ", status=" + status + ", accountingMetadata=<redacted>]";
    }
}
