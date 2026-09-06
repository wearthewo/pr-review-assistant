package io.prreviewassistant.review.retrieval;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record PullRequestSnapshot(
        long installationId,
        long repositoryId,
        int pullRequestNumber,
        String headSha,
        String baseSha,
        boolean draft,
        List<ChangedFile> changedFiles) {

    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-fA-F]{40,64}");

    public PullRequestSnapshot {
        if (installationId <= 0 || repositoryId <= 0 || pullRequestNumber <= 0) {
            throw new IllegalArgumentException("snapshot identity must be positive");
        }
        Objects.requireNonNull(headSha, "headSha must not be null");
        Objects.requireNonNull(baseSha, "baseSha must not be null");
        if (!OBJECT_ID.matcher(headSha).matches() || !OBJECT_ID.matcher(baseSha).matches()) {
            throw new IllegalArgumentException("snapshot revisions must be bounded hexadecimal object IDs");
        }
        headSha = headSha.toLowerCase(Locale.ROOT);
        baseSha = baseSha.toLowerCase(Locale.ROOT);
        changedFiles = List.copyOf(changedFiles);
    }

    @Override
    public String toString() {
        return "PullRequestSnapshot[installationId=" + installationId
                + ", repositoryId=" + repositoryId
                + ", pullRequestNumber=" + pullRequestNumber
                + ", headSha=" + headSha.substring(0, 8) + "..."
                + ", baseSha=" + baseSha.substring(0, 8) + "..."
                + ", draft=" + draft
                + ", changedFileCount=" + changedFiles.size() + "]";
    }
}
