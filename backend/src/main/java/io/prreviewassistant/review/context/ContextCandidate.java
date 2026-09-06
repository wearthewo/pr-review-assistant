package io.prreviewassistant.review.context;

import java.util.Objects;

public record ContextCandidate(String path, RepositoryRevisionSide revisionSide,
        ContextSelectionReason reason, int relevanceScore, long estimatedCost, String anchorSymbol) {
    public ContextCandidate(String path, RepositoryRevisionSide revisionSide,
            ContextSelectionReason reason, int relevanceScore, long estimatedCost) {
        this(path, revisionSide, reason, relevanceScore, estimatedCost, null);
    }
    public ContextCandidate {
        Objects.requireNonNull(path); Objects.requireNonNull(revisionSide); Objects.requireNonNull(reason);
        if (path.isBlank() || path.length() > 4096 || relevanceScore < 0 || estimatedCost < 0) throw new IllegalArgumentException("invalid context candidate");
        if (anchorSymbol != null && (!anchorSymbol.matches("[A-Za-z_$][A-Za-z0-9_$]*") || anchorSymbol.length() > 200)) throw new IllegalArgumentException("invalid context anchor");
    }
    @Override public String toString() { return "ContextCandidate[path=<redacted>, revisionSide=" + revisionSide + ", reason=" + reason + ", relevanceScore=" + relevanceScore + ", estimatedCost=" + estimatedCost + ", anchor=" + (anchorSymbol == null ? "absent" : "present") + "]"; }
}
