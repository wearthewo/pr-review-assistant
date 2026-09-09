package io.prreviewassistant.review.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryPathMatcherTest {
    private final RepositoryPathMatcher matcher = new RepositoryPathMatcher();

    @Test
    void supportsOnlyTheDocumentedSegmentGlobSubset() {
        assertThat(matcher.matches("*.lock", "package.lock")).isTrue();
        assertThat(matcher.matches("*.lock", "nested/package.lock")).isFalse();
        assertThat(matcher.matches("**/*.lock", "package.lock")).isTrue();
        assertThat(matcher.matches("**/*.lock", "nested/package.lock")).isTrue();
        assertThat(matcher.matches("docs/**", "docs/guide/setup.md")).isTrue();
        assertThat(matcher.matches("**/generated/**", "src/main/generated/A.java")).isTrue();
        assertThat(matcher.matches("src/**/generated/*", "src/main/java/generated/A.java")).isTrue();
        assertThat(matcher.matches("?.txt", "λ.txt")).isTrue();
        assertThat(matcher.matches("?.txt", "nested/a.txt")).isFalse();
    }

    @Test
    void handlesUnicodeAndNeverNormalizesTraversalOrBackslashes() {
        assertThat(matcher.matches("資料/**", "資料/設定.yml")).isTrue();
        assertThat(matcher.matches("**", "../../x")).isTrue();
        assertThat(matcher.matches("**", "folder\\file")).isFalse();
        assertThat(matcher.matches("**", "/absolute/file")).isFalse();
    }

    @Test
    void validationRejectsAmbiguousOrUnsafePatterns() {
        assertThatThrownBy(() -> RepositoryPathMatcher.validatePatterns(List.of("")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryPathMatcher.validatePatterns(List.of("/absolute/**")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryPathMatcher.validatePatterns(List.of("../secret")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryPathMatcher.validatePatterns(List.of("a\\b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryPathMatcher.validatePatterns(List.of("a/**x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryPathMatcher.validatePatterns(List.of("a\u0000b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryPathMatcher.validatePatterns(
                java.util.stream.IntStream.range(0, 17)
                        .mapToObj(index -> (char) ('a' + index) + "x".repeat(240)).toList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void effectiveConfigAlwaysExcludesItsOwnCanonicalFileAndRedactsPatterns() {
        var config = new EffectiveRepositoryReviewConfig(ReviewMode.BALANCED,
                List.of("private/**"), java.util.Set.of());
        assertThat(config.ignores(EffectiveRepositoryReviewConfig.FILE_NAME)).isTrue();
        assertThat(config.ignores("private/key.txt")).isTrue();
        assertThat(config.toString()).contains("ignorePatternCount=1").doesNotContain("private", "key.txt");
    }
}
