package io.prreviewassistant.review.config;

import io.prreviewassistant.review.context.ReviewContextProperties;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewExecutionProfileTest {
    private final ReviewContextProperties ceilings = new ReviewContextProperties(
            true, 12, DataSize.ofKilobytes(128), DataSize.ofKilobytes(512),
            DataSize.ofKilobytes(128), 200, 100, 20);

    @Test
    void modesSelectOnlyPredefinedBudgetsWithinOperatorCeilings() {
        var fast = ReviewExecutionProfile.forMode(ReviewMode.FAST, ceilings);
        var balanced = ReviewExecutionProfile.forMode(ReviewMode.BALANCED, ceilings);
        var deep = ReviewExecutionProfile.forMode(ReviewMode.DEEP, ceilings);

        assertThat(fast).isEqualTo(new ReviewExecutionProfile(6, 256 * 1024, 50, 10));
        assertThat(balanced).isEqualTo(new ReviewExecutionProfile(12, 512 * 1024, 100, 20));
        assertThat(deep).isEqualTo(balanced);
        assertThat(List.of(fast, balanced, deep)).allSatisfy(profile -> {
            assertThat(profile.maxFiles()).isLessThanOrEqualTo(ceilings.maxFiles());
            assertThat(profile.maxTotalBytes()).isLessThanOrEqualTo(ceilings.maxTotalBytes());
            assertThat(profile.maxCandidates()).isLessThanOrEqualTo(ceilings.maxCandidates());
            assertThat(profile.maxApiRequests()).isLessThanOrEqualTo(ceilings.maxApiRequests());
        });
    }
}
