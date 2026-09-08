package io.prreviewassistant.review.publication;

import java.util.Objects;

public record PublicationComment(String path, int line, Integer startLine, String body) {
    public PublicationComment {
        Objects.requireNonNull(path, "path is required");
        Objects.requireNonNull(body, "body is required");
        if (path.isBlank() || path.length() > 4096 || line < 1
                || (startLine != null && (startLine < 1 || startLine >= line))
                || body.isBlank()) {
            throw new IllegalArgumentException("publication comment is invalid");
        }
    }

    @Override
    public String toString() {
        return "PublicationComment[path=<redacted>, line=" + line
                + ", multiline=" + (startLine != null) + ", body=<redacted>]";
    }
}
