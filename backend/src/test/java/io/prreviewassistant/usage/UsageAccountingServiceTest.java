package io.prreviewassistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.prreviewassistant.ai.AiModelTier;
import io.prreviewassistant.ai.AiTokenUsage;
import io.prreviewassistant.observability.ApplicationMetrics;
import io.prreviewassistant.review.analysis.ReviewAnalysisMetadata;
import io.prreviewassistant.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UsageAccountingServiceTest {
    @Test
    void recordsQuotaExhaustionConsumptionAndReleaseUsingControlledTags() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        UsagePeriod period = new UsagePeriod(now, Instant.parse("2026-11-01T00:00:00Z"));
        TenantContext tenant = new TenantContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 2);
        UUID jobId = UUID.randomUUID();
        UsageAccountingStore store = mock(UsageAccountingStore.class);
        QuotaPolicy policy = new QuotaPolicy(1);
        UsageReservationResult exhausted = new UsageReservationResult(
                UsageReservationResult.Outcome.QUOTA_EXCEEDED, policy.decide(1, period));
        when(store.reserve(tenant, jobId, period, policy, now)).thenReturn(exhausted);
        when(store.release(tenant, jobId, now)).thenReturn(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsageAccountingService service = new UsageAccountingService(
                store, policy, Clock.fixed(now, ZoneOffset.UTC), new ApplicationMetrics(registry));
        ReviewAnalysisMetadata metadata = new ReviewAnalysisMetadata(
                "fake", "fake-model", AiModelTier.BALANCED,
                new AiTokenUsage(OptionalLong.of(10), OptionalLong.empty(), OptionalLong.of(4),
                        OptionalLong.empty(), OptionalLong.of(14)),
                Duration.ofSeconds(1), 1);

        assertThat(service.reserve(tenant, jobId)).isEqualTo(exhausted);
        service.consume(tenant, jobId, metadata);
        assertThat(service.release(tenant, jobId)).isTrue();

        assertThat(registry.get(ApplicationMetrics.PREFIX + ".usage.events")
                .tags("action", "reserve", "outcome", "exhausted").counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".usage.events")
                .tags("action", "consume", "outcome", "consumed").counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".usage.events")
                .tags("action", "release", "outcome", "released").counter().count()).isEqualTo(1);
    }

    @Test
    void reservationAndQuotaUseInjectedClockAndExplicitTenantContext() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        UsagePeriod october = new UsagePeriod(now, Instant.parse("2026-11-01T00:00:00Z"));
        TenantContext tenant = new TenantContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 2);
        UUID jobId = UUID.randomUUID();
        UsageAccountingStore store = mock(UsageAccountingStore.class);
        QuotaPolicy policy = new QuotaPolicy(50);
        QuotaDecision available = policy.decide(4, october);
        UsageReservationResult acquired = new UsageReservationResult(
                UsageReservationResult.Outcome.ACQUIRED, available);
        when(store.reserve(tenant, jobId, october, policy, now)).thenReturn(acquired);
        when(store.quota(tenant.tenantId(), october, policy)).thenReturn(available);
        UsageAccountingService service = new UsageAccountingService(
                store, policy, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.reserve(tenant, jobId)).isEqualTo(acquired);
        assertThat(service.quota(tenant)).isEqualTo(available);
        assertThat(service.quota(tenant.tenantId())).isEqualTo(available);
        verify(store).reserve(tenant, jobId, october, policy, now);
        verify(store, org.mockito.Mockito.times(2)).quota(tenant.tenantId(), october, policy);
    }
}
