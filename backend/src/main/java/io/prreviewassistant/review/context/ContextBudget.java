package io.prreviewassistant.review.context;

import io.prreviewassistant.review.config.ReviewExecutionProfile;

final class ContextBudget {
    private final int candidates;
    private final int maxFiles;
    private final long maxBytes;
    private final int maxRequests;
    private int fetched;
    private int retained;
    private long bytesFetched;
    private long bytesRetained;
    private int requests;
    private boolean exhausted;

    ContextBudget(int candidates, ReviewExecutionProfile profile) {
        this.candidates = candidates;
        this.maxFiles = profile.maxFiles();
        this.maxBytes = profile.maxTotalBytes();
        this.maxRequests = profile.maxApiRequests();
    }

    boolean canFetch() {
        if (retained >= maxFiles || requests >= maxRequests) {
            exhausted = true;
            return false;
        }
        return true;
    }

    void requested() { requests++; }
    void fetched(long bytes) { fetched++; bytesFetched += bytes; }
    boolean canRetain(long bytes) {
        if (bytesRetained + bytes > maxBytes) { exhausted = true; return false; }
        return true;
    }
    void retained(long bytes) { retained++; bytesRetained += bytes; }
    ContextBudgetUsage usage() {
        return new ContextBudgetUsage(candidates, fetched, retained, bytesFetched,
                bytesRetained, requests, exhausted);
    }
}
