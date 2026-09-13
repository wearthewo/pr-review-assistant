package io.prreviewassistant.tenant;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Tenant(UUID id, Instant createdAt, Instant updatedAt) {
    public Tenant {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("tenant timestamps are invalid");
        }
    }
}
