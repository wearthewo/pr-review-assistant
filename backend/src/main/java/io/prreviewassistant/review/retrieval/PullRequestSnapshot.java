package io.prreviewassistant.review.retrieval;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record PullRequestSnapshot(
        long installationId,
        long repositoryId,
        String repositoryOwner,
        String repositoryName,
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
        Objects.requireNonNull(repositoryOwner, "repositoryOwner must not be null");
        Objects.requireNonNull(repositoryName, "repositoryName must not be null");
        if (repositoryOwner.isBlank() || repositoryOwner.length() > 100
                || repositoryName.isBlank() || repositoryName.length() > 100
                || repositoryOwner.indexOf('/') >= 0 || repositoryOwner.indexOf('\\') >= 0
                || repositoryName.indexOf('/') >= 0 || repositoryName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("repository route is invalid");
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

    public PullRequestSnapshot(long installationId, long repositoryId, int pullRequestNumber,
            String headSha, String baseSha, boolean draft, List<ChangedFile> changedFiles) {
        this(installationId, repositoryId, "test-owner", "test-repository", pullRequestNumber,
                headSha, baseSha, draft, changedFiles);
    }

    @Override
    public String toString() {
        return "PullRequestSnapshot[installationId=" + installationId
                + ", repositoryId=" + repositoryId
                + ", repositoryRoute=<redacted>"
                + ", pullRequestNumber=" + pullRequestNumber
                + ", headSha=" + headSha.substring(0, 8) + "..."
                + ", baseSha=" + baseSha.substring(0, 8) + "..."
                + ", draft=" + draft
                + ", changedFileCount=" + changedFiles.size() + "]";
    }
}
