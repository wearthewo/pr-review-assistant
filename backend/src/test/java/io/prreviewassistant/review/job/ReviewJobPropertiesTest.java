package io.prreviewassistant.review.job;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ReviewJobPropertiesTest {

    @Test
    void rejectsNonPositiveDurationsAndInvertedRetryRange() {
        assertThatThrownBy(() -> new ReviewJobProperties(0, Duration.ofSeconds(1), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewJobProperties(3, Duration.ZERO, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewJobProperties(3, Duration.ofMinutes(2), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewWorkerProperties(false, Duration.ofSeconds(1), 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewWorkerProperties(false, Duration.ofSeconds(1), 101,
                Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
