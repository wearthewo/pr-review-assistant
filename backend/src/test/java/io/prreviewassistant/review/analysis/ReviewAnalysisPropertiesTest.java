package io.prreviewassistant.review.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewAnalysisPropertiesTest {
    @Test void acceptsConservativeDefaults() {
        assertThatNoException().isThrownBy(() -> new ReviewAnalysisProperties(5, 70));
    }

    @Test void rejectsUnsafeBounds() {
        assertThatThrownBy(() -> new ReviewAnalysisProperties(11, 70)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewAnalysisProperties(5, 101)).isInstanceOf(IllegalArgumentException.class);
    }
}
