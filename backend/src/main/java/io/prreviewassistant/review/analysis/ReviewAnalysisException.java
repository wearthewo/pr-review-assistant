package io.prreviewassistant.review.analysis;

public final class ReviewAnalysisException extends RuntimeException {
    public ReviewAnalysisException() {
        super("Review analysis output is invalid");
    }
}
