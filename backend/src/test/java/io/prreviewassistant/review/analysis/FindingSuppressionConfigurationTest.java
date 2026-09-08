package io.prreviewassistant.review.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FindingSuppressionConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FindingSuppressionConfiguration.class)
            .withPropertyValues(
                    "review.suppression.minimum-confidence=85",
                    "review.suppression.minimum-severity=MEDIUM",
                    "review.suppression.max-publishable-findings=3");

    @Test
    void wiresProviderIndependentEngineWithConservativePolicy() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FindingSuppressionEngine.class);
            assertThat(context.getBean(FindingSuppressionProperties.class))
                    .isEqualTo(new FindingSuppressionProperties(85, ReviewSeverity.MEDIUM, 3));
        });
    }

    @Test
    void rejectsUnsafeMaximum() {
        runner.withPropertyValues("review.suppression.max-publishable-findings=6")
                .run(context -> assertThat(context).hasFailed());
    }
}
