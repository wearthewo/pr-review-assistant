package io.prreviewassistant.review.config;

import java.util.List;

/** A bounded, repository-path-only glob matcher. It never touches the local filesystem. */
public final class RepositoryPathMatcher {

    public boolean matches(String pattern, String repositoryPath) {
        if (pattern == null || pattern.isBlank()
                || pattern.length() > EffectiveRepositoryReviewConfig.MAX_IGNORE_PATTERN_LENGTH
                || pattern.indexOf('\\') >= 0 || hasControl(pattern)
                || repositoryPath == null || repositoryPath.isEmpty()
                || repositoryPath.startsWith("/") || repositoryPath.indexOf('\\') >= 0
                || hasControl(repositoryPath)) {
            return false;
        }
        String[] patternSegments = pattern.split("/", -1);
        String[] pathSegments = repositoryPath.split("/", -1);
        boolean[][] matches = new boolean[patternSegments.length + 1][pathSegments.length + 1];
        matches[patternSegments.length][pathSegments.length] = true;
        for (int patternIndex = patternSegments.length - 1; patternIndex >= 0; patternIndex--) {
            for (int pathIndex = pathSegments.length; pathIndex >= 0; pathIndex--) {
                if ("**".equals(patternSegments[patternIndex])) {
                    matches[patternIndex][pathIndex] = matches[patternIndex + 1][pathIndex]
                            || (pathIndex < pathSegments.length && matches[patternIndex][pathIndex + 1]);
                } else {
                    matches[patternIndex][pathIndex] = pathIndex < pathSegments.length
                            && segmentMatches(patternSegments[patternIndex], pathSegments[pathIndex])
                            && matches[patternIndex + 1][pathIndex + 1];
                }
            }
        }
        return matches[0][0];
    }

    private boolean segmentMatches(String pattern, String value) {
        boolean[] previous = new boolean[value.length() + 1];
        previous[0] = true;
        for (int patternIndex = 0; patternIndex < pattern.length(); patternIndex++) {
            char token = pattern.charAt(patternIndex);
            boolean[] current = new boolean[value.length() + 1];
            if (token == '*') {
                current[0] = previous[0];
                for (int valueIndex = 1; valueIndex <= value.length(); valueIndex++) {
                    current[valueIndex] = previous[valueIndex] || current[valueIndex - 1];
                }
            } else {
                for (int valueIndex = 1; valueIndex <= value.length(); valueIndex++) {
                    current[valueIndex] = previous[valueIndex - 1]
                            && (token == '?' || token == value.charAt(valueIndex - 1));
                }
            }
            previous = current;
        }
        return previous[value.length()];
    }

    static void validatePatterns(List<String> patterns) {
        if (patterns == null || patterns.size() > EffectiveRepositoryReviewConfig.MAX_IGNORE_PATTERNS) {
            throw new IllegalArgumentException("ignore patterns are invalid");
        }
        int total = 0;
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()
                    || pattern.length() > EffectiveRepositoryReviewConfig.MAX_IGNORE_PATTERN_LENGTH
                    || pattern.startsWith("/") || pattern.indexOf('\\') >= 0
                    || hasControl(pattern)) {
                throw new IllegalArgumentException("ignore pattern is invalid");
            }
            String[] segments = pattern.split("/", -1);
            for (String segment : segments) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                        || (segment.contains("**") && !"**".equals(segment))) {
                    throw new IllegalArgumentException("ignore pattern is invalid");
                }
            }
            total += pattern.length();
            if (total > EffectiveRepositoryReviewConfig.MAX_TOTAL_IGNORE_PATTERN_LENGTH) {
                throw new IllegalArgumentException("ignore patterns are invalid");
            }
        }
    }

    private static boolean hasControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
