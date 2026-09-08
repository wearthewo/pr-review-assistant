package io.prreviewassistant.review.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindingSuppressionPropertiesTest {

    @Test
    void acceptsDocumentedBoundaryValues() {
        assertThatCode(() -> new FindingSuppressionProperties(0, ReviewSeverity.LOW, 1))
                .doesNotThrowAnyException();
        assertThatCode(() -> new FindingSuppressionProperties(100, ReviewSeverity.CRITICAL, 5))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidConfidenceSeverityAndCap() {
        assertThatThrownBy(() -> new FindingSuppressionProperties(-1, ReviewSeverity.MEDIUM, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FindingSuppressionProperties(85, null, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FindingSuppressionProperties(85, ReviewSeverity.MEDIUM, 6))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
