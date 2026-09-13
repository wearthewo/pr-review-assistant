package io.prreviewassistant.review.analysis;

import java.util.Optional;

public final class ReviewAnalysisException extends RuntimeException {
    private final ReviewAnalysisMetadata consumptionMetadata;

    public ReviewAnalysisException() {
        this(null);
    }

    public ReviewAnalysisException(ReviewAnalysisMetadata consumptionMetadata) {
        super("Review analysis output is invalid");
        this.consumptionMetadata = consumptionMetadata;
    }

    public Optional<ReviewAnalysisMetadata> consumptionMetadata() {
        return Optional.ofNullable(consumptionMetadata);
    }
}
