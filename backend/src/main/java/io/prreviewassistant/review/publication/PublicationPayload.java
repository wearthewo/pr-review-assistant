package io.prreviewassistant.review.publication;

import java.util.List;
import java.util.Objects;

public record PublicationPayload(int version, String body, List<PublicationComment> comments) {
    public static final int CURRENT_VERSION = 1;

    public PublicationPayload {
        if (version != CURRENT_VERSION || body == null || body.isBlank()) {
            throw new IllegalArgumentException("publication payload is invalid");
        }
        comments = List.copyOf(Objects.requireNonNull(comments, "comments are required"));
    }

    @Override
    public String toString() {
        return "PublicationPayload[version=" + version + ", body=<redacted>, commentCount="
                + comments.size() + "]";
    }
}
