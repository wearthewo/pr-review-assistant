package io.prreviewassistant.review.config;

import io.prreviewassistant.review.analysis.ReviewFindingCategory;
import io.prreviewassistant.review.retrieval.ChangedFile;
import io.prreviewassistant.review.retrieval.PullRequestSnapshot;

import java.util.EnumSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class EffectiveRepositoryReviewConfig {
    public static final int VERSION = 1;
    public static final String FILE_NAME = ".reviewbot.yml";
    public static final int MAX_IGNORE_PATTERNS = 50;
    public static final int MAX_IGNORE_PATTERN_LENGTH = 256;
    public static final int MAX_TOTAL_IGNORE_PATTERN_LENGTH = 4096;

    private static final RepositoryPathMatcher MATCHER = new RepositoryPathMatcher();
    private final ReviewMode mode;
    private final List<String> ignorePatterns;
    private final Set<ReviewFindingCategory> enabledCategories;

    public EffectiveRepositoryReviewConfig(ReviewMode mode, List<String> ignorePatterns,
            Set<ReviewFindingCategory> enabledCategories) {
        this.mode = Objects.requireNonNull(mode, "mode is required");
        RepositoryPathMatcher.validatePatterns(ignorePatterns);
        this.ignorePatterns = List.copyOf(ignorePatterns);
        Objects.requireNonNull(enabledCategories, "enabledCategories is required");
        this.enabledCategories = enabledCategories.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(enabledCategories));
    }

    public static EffectiveRepositoryReviewConfig defaults() {
        return new EffectiveRepositoryReviewConfig(
                ReviewMode.BALANCED, List.of(), EnumSet.allOf(ReviewFindingCategory.class));
    }

    public ReviewMode mode() {
        return mode;
    }

    public List<String> ignorePatterns() {
        return ignorePatterns;
    }

    public Set<ReviewFindingCategory> enabledCategories() {
        return enabledCategories;
    }

    public boolean ignores(String repositoryPath) {
        if (FILE_NAME.equals(repositoryPath)) {
            return true;
        }
        return ignorePatterns.stream().anyMatch(pattern -> MATCHER.matches(pattern, repositoryPath));
    }

    public PullRequestSnapshot filter(PullRequestSnapshot snapshot) {
        List<ChangedFile> included = snapshot.changedFiles().stream()
                .filter(file -> !ignores(file.path())
                        && (file.previousPath() == null || !ignores(file.previousPath())))
                .toList();
        return new PullRequestSnapshot(snapshot.installationId(), snapshot.repositoryId(),
                snapshot.repositoryOwner(), snapshot.repositoryName(), snapshot.pullRequestNumber(),
                snapshot.headSha(), snapshot.baseSha(), snapshot.draft(), included);
    }

    @Override
    public String toString() {
        return "EffectiveRepositoryReviewConfig[version=" + VERSION + ", mode=" + mode
                + ", ignorePatternCount=" + ignorePatterns.size()
                + ", enabledCategoryCount=" + enabledCategories.size() + "]";
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EffectiveRepositoryReviewConfig config
                && mode == config.mode
                && ignorePatterns.equals(config.ignorePatterns)
                && enabledCategories.equals(config.enabledCategories);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, ignorePatterns, enabledCategories);
    }
}
