package io.prreviewassistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.prreviewassistant.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UsageAccountingServiceTest {
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
        verify(store).reserve(tenant, jobId, october, policy, now);
        verify(store).quota(tenant.tenantId(), october, policy);
    }
}
