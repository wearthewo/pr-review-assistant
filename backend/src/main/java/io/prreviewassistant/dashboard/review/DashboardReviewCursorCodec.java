package io.prreviewassistant.dashboard.review;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
final class DashboardReviewCursorCodec {
    static final int MAX_CURSOR_LENGTH = 160;

    String encode(UUID tenantId, Instant createdAt, UUID jobId) {
        if (tenantId == null || createdAt == null || jobId == null) {
            throw new IllegalArgumentException("review cursor fields are required");
        }
        String value = tenantId + "|" + createdAt + "|" + jobId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    Cursor decode(String encoded, UUID expectedTenantId) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_CURSOR_LENGTH
                || expectedTenantId == null) {
            throw new DashboardReviewRequestException();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            String[] fields = decoded.split("\\|", -1);
            if (fields.length != 3) {
                throw new DashboardReviewRequestException();
            }
            UUID tenantId = UUID.fromString(fields[0]);
            Instant createdAt = Instant.parse(fields[1]);
            UUID jobId = UUID.fromString(fields[2]);
            if (!tenantId.equals(expectedTenantId)
                    || !encode(tenantId, createdAt, jobId).equals(encoded)) {
                throw new DashboardReviewRequestException();
            }
            return new Cursor(createdAt, jobId);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new DashboardReviewRequestException();
        }
    }

    record Cursor(Instant createdAt, UUID jobId) {
    }
}
