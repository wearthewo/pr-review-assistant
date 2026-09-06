package io.prreviewassistant.review.retrieval;

import java.util.Objects;

public record ChangedFile(
        String path,
        String previousPath,
        ChangedFileStatus status,
        int additions,
        int deletions,
        int changes,
        PatchAvailability patchAvailability,
        String patch) {

    public static final int MAX_PATH_LENGTH = 4096;

    public ChangedFile {
        validatePath(path, "path");
        if (previousPath != null) {
            validatePath(previousPath, "previousPath");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(patchAvailability, "patchAvailability must not be null");
        if (additions < 0 || deletions < 0 || changes < 0) {
            throw new IllegalArgumentException("change counts must not be negative");
        }
        if ((patchAvailability == PatchAvailability.AVAILABLE) != (patch != null)) {
            throw new IllegalArgumentException("only available patches may contain patch text");
        }
    }

    private static void validatePath(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException(field + " must be a bounded repository-relative identifier");
        }
    }

    @Override
    public String toString() {
        return "ChangedFile[path=<redacted>, previousPath="
                + (previousPath == null ? "absent" : "<redacted>")
                + ", status=" + status
                + ", additions=" + additions
                + ", deletions=" + deletions
                + ", changes=" + changes
                + ", patchAvailability=" + patchAvailability
                + ", patch=<redacted>]";
    }
}
