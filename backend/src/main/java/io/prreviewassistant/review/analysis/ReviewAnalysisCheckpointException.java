package io.prreviewassistant.review.analysis;

import java.util.Objects;

public final class ReviewAnalysisCheckpointException extends RuntimeException {
    private final ReviewAnalysisCheckpointError error;

    public ReviewAnalysisCheckpointException(ReviewAnalysisCheckpointError error) {
        super("Review analysis checkpoint failed: "
                + Objects.requireNonNull(error, "error is required").name());
        this.error = error;
    }

    public ReviewAnalysisCheckpointError error() {
        return error;
    }
}
