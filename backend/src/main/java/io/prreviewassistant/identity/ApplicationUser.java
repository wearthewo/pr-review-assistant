package io.prreviewassistant.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApplicationUser(UUID id, Instant createdAt, Instant updatedAt) {
    public ApplicationUser {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("application user timestamps are invalid");
        }
    }
}
