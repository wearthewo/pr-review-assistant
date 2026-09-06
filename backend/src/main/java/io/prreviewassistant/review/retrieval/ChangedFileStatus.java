package io.prreviewassistant.review.retrieval;

import java.util.Locale;

public enum ChangedFileStatus {
    ADDED,
    MODIFIED,
    REMOVED,
    RENAMED,
    COPIED,
    CHANGED,
    UNKNOWN;

    static ChangedFileStatus fromGitHub(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return UNKNOWN;
        }
    }
}
