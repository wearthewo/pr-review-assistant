package io.prreviewassistant.review.publication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReviewPublicationPropertiesTest {

    @Test
    void rejectsUnsafeBoundsAndTiming() {
        assertThatThrownBy(() -> properties(false, 101, 10, Duration.ofSeconds(10), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(false, 10, 0, Duration.ofSeconds(10), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(false, 10, 3, Duration.ZERO, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(false, 10, 3, Duration.ofMinutes(6), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ReviewPublicationProperties properties(
            boolean enabled, int batchSize, int attempts, Duration baseDelay, Duration maxDelay) {
        return new ReviewPublicationProperties(
                enabled,
                6_000,
                2_000,
                20_000,
                10,
                attempts,
                baseDelay,
                maxDelay,
                Duration.ofSeconds(5),
                batchSize,
                Duration.ofMinutes(2));
    }
}
