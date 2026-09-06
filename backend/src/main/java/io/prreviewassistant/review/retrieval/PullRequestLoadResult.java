package io.prreviewassistant.review.retrieval;

import java.util.Objects;

public record PullRequestLoadResult(Outcome outcome, PullRequestSnapshot snapshot) {

    public PullRequestLoadResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if ((outcome == Outcome.READY) != (snapshot != null)) {
            throw new IllegalArgumentException("only a ready result may contain a snapshot");
        }
    }

    public static PullRequestLoadResult ready(PullRequestSnapshot snapshot) {
        return new PullRequestLoadResult(Outcome.READY, Objects.requireNonNull(snapshot));
    }

    public static PullRequestLoadResult stale() {
        return new PullRequestLoadResult(Outcome.STALE, null);
    }

    public static PullRequestLoadResult tooLarge() {
        return new PullRequestLoadResult(Outcome.TOO_LARGE, null);
    }

    public enum Outcome {
        READY,
        STALE,
        TOO_LARGE
    }
}
