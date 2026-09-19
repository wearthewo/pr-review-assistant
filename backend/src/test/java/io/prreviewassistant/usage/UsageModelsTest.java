package io.prreviewassistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.prreviewassistant.ai.AiModelTier;
import io.prreviewassistant.ai.AiTokenUsage;
import io.prreviewassistant.review.analysis.ReviewAnalysisMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class UsageModelsTest {
    @Test
    void utcCalendarMonthUsesHalfOpenExactBoundaries() {
        UsagePeriod september = UsagePeriod.utcMonthContaining(Instant.parse("2026-09-30T23:59:59.999Z"));
        UsagePeriod october = UsagePeriod.utcMonthContaining(Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(september.start()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(september.end()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(october.start()).isEqualTo(september.end());
        assertThat(october.end()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    void utcCalendarMonthHandlesYearAndFebruaryBoundaries() {
        UsagePeriod december = UsagePeriod.utcMonthContaining(Instant.parse("2026-12-31T23:59:59.999Z"));
        UsagePeriod february = UsagePeriod.utcMonthContaining(Instant.parse("2028-02-29T12:00:00Z"));

        assertThat(december.start()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(december.end()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(february.start()).isEqualTo(Instant.parse("2028-02-01T00:00:00Z"));
        assertThat(february.end()).isEqualTo(Instant.parse("2028-03-01T00:00:00Z"));
    }

    @Test
    void quotaPolicyReportsUsedRemainingAndHardBounds() {
        UsagePeriod period = UsagePeriod.utcMonthContaining(Instant.EPOCH);
        QuotaPolicy policy = new QuotaPolicy(3);

        assertThat(policy.decide(2, period))
                .isEqualTo(new QuotaDecision(true, 3, 2, 1, period, QuotaDecision.Reason.AVAILABLE));
        assertThat(policy.decide(3, period))
                .isEqualTo(new QuotaDecision(false, 3, 3, 0, period, QuotaDecision.Reason.EXHAUSTED));
        assertThatThrownBy(() -> new UsageProperties(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageProperties(UsageProperties.MAXIMUM_MONTHLY_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void measurementsKeepUnknownValuesUnknownAndDiscardUnsafeIdentifiers() {
        UsageMeasurement measurement = new UsageMeasurement(Optional.of("unsafe provider\nsecret"),
                Optional.of("m".repeat(201)), OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());

        assertThat(measurement.provider()).isEmpty();
        assertThat(measurement.model()).isEmpty();
        assertThat(measurement.totalTokens()).isEmpty();
        assertThat(measurement.toString()).contains("tokens=<redacted>")
                .doesNotContain("secret", "mmmm");
        assertThatThrownBy(() -> new UsageMeasurement(Optional.empty(), Optional.empty(),
                OptionalLong.of(UsageMeasurement.MAXIMUM_TOKEN_COUNT + 1), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerMeasurementsPreserveUnknownValuesAndDropUnsafeIdentifiers() {
        var metadata = new ReviewAnalysisMetadata("provider\nunsafe", "m".repeat(201),
                AiModelTier.BALANCED, AiTokenUsage.unavailable(), Duration.ZERO, 1);

        UsageMeasurement measurement = UsageMeasurement.from(metadata);

        assertThat(measurement.provider()).isEmpty();
        assertThat(measurement.model()).isEmpty();
        assertThat(measurement.inputTokens()).isEmpty();
        assertThat(measurement.toString()).contains("tokens=<redacted>").doesNotContain("unsafe");
    }

    @Test
    void tokenMeasurementsAreBoundedAndKeepPartialOptionalValues() {
        UsageMeasurement partial = new UsageMeasurement(Optional.of("openai"), Optional.of("model-1"),
                OptionalLong.of(10), OptionalLong.empty(), OptionalLong.of(4),
                OptionalLong.empty(), OptionalLong.of(14));

        assertThat(partial.inputTokens()).hasValue(10);
        assertThat(partial.cachedInputTokens()).isEmpty();
        assertThatThrownBy(() -> new UsageMeasurement(Optional.empty(), Optional.empty(),
                OptionalLong.of(UsageMeasurement.MAXIMUM_TOKEN_COUNT + 1), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
