package io.prreviewassistant.review.job;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record ReviewTarget(
        long installationId,
        long repositoryId,
        int pullRequestNumber,
        String headSha) {

    public static final int MIN_HEAD_SHA_LENGTH = 40;
    public static final int MAX_HEAD_SHA_LENGTH = 64;
    private static final Pattern GIT_OBJECT_ID = Pattern.compile("[0-9a-fA-F]{40,64}");

    public ReviewTarget {
        if (installationId <= 0) {
            throw new IllegalArgumentException("installationId must be positive");
        }
        if (repositoryId <= 0) {
            throw new IllegalArgumentException("repositoryId must be positive");
        }
        if (pullRequestNumber <= 0) {
            throw new IllegalArgumentException("pullRequestNumber must be positive");
        }
        Objects.requireNonNull(headSha, "headSha must not be null");
        if (!GIT_OBJECT_ID.matcher(headSha).matches()) {
            throw new IllegalArgumentException("headSha must be a bounded hexadecimal Git object ID");
        }
        headSha = headSha.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "ReviewTarget[installationId=" + installationId
                + ", repositoryId=" + repositoryId
                + ", pullRequestNumber=" + pullRequestNumber
                + ", headSha=" + headSha.substring(0, 8) + "...]";
    }
}
