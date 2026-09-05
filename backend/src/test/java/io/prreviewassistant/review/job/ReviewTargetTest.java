package io.prreviewassistant.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReviewTargetTest {

    private static final String SHA = "ABCDEF0123456789ABCDEF0123456789ABCDEF01";

    @Test
    void validatesAndNormalizesAnExactReviewRevision() {
        ReviewTarget target = new ReviewTarget(11, 22, 33, SHA);

        assertThat(target.installationId()).isEqualTo(11);
        assertThat(target.repositoryId()).isEqualTo(22);
        assertThat(target.pullRequestNumber()).isEqualTo(33);
        assertThat(target.headSha()).isEqualTo(SHA.toLowerCase());
        assertThat(target.toString()).contains("abcdef01...").doesNotContain(SHA);
    }

    @Test
    void rejectsInvalidIdentityFieldsWithoutIncludingTheirValuesInErrors() {
        assertThatThrownBy(() -> new ReviewTarget(0, 22, 33, SHA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(SHA);
        assertThatThrownBy(() -> new ReviewTarget(11, 0, 33, SHA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewTarget(11, 22, 0, SHA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsShaOneAndSha256LengthsButRejectsOtherOrNonHexValues() {
        assertThat(new ReviewTarget(1, 2, 3, "a".repeat(40)).headSha()).hasSize(40);
        assertThat(new ReviewTarget(1, 2, 3, "b".repeat(64)).headSha()).hasSize(64);
        assertThatThrownBy(() -> new ReviewTarget(1, 2, 3, "a".repeat(39)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewTarget(1, 2, 3, "g".repeat(40)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewTarget(1, 2, 3, "a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
