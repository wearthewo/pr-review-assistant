package io.prreviewassistant.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ReviewJobRetryPolicyTest {

    private final ReviewJobRetryPolicy policy =
            new ReviewJobRetryPolicy(Duration.ofSeconds(10), Duration.ofSeconds(45));

    @Test
    void firstFailureUsesBaseDelay() {
        assertThat(policy.delayAfterAttempt(1)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void delayGrowsExponentially() {
        assertThat(policy.delayAfterAttempt(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.delayAfterAttempt(3)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void delayIsCappedWithoutOverflow() {
        assertThat(policy.delayAfterAttempt(4)).isEqualTo(Duration.ofSeconds(45));
        assertThat(policy.delayAfterAttempt(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void rejectsInvalidPolicyInputs() {
        assertThatThrownBy(() -> policy.delayAfterAttempt(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewJobRetryPolicy(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewJobRetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
