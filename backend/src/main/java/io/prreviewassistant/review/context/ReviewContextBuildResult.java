package io.prreviewassistant.review.context;

import java.util.List;

public record ReviewContextBuildResult(Outcome outcome, ReviewContext context, List<ContextOmission> omissions) {
    public ReviewContextBuildResult { omissions = List.copyOf(omissions); }
    public static ReviewContextBuildResult ready(ReviewContext c) { return new ReviewContextBuildResult(Outcome.READY, c, List.of()); }
    public static ReviewContextBuildResult partial(ReviewContext c, List<ContextOmission> o) { return new ReviewContextBuildResult(Outcome.PARTIAL, c, o); }
    public static ReviewContextBuildResult unavailable(ContextOmissionReason r) { return new ReviewContextBuildResult(Outcome.UNAVAILABLE, null, List.of(new ContextOmission(r, RepositoryRevisionSide.HEAD))); }
    public enum Outcome { READY, PARTIAL, UNAVAILABLE }
}
