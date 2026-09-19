package io.prreviewassistant.dashboard.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import io.prreviewassistant.identity.AuthorizedTenantContext;
import io.prreviewassistant.identity.TenantAuthorizationService;
import io.prreviewassistant.usage.QuotaDecision;
import io.prreviewassistant.usage.UsageAccountingService;
import io.prreviewassistant.usage.UsagePeriod;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardUsageServiceTest {
    @Test
    void authorizationPrecedesOneAuthoritativeQuotaRead() {
        UUID tenantId = UUID.randomUUID();
        AuthenticatedUserIdentity identity =
                new AuthenticatedUserIdentity("https://issuer.example/", "subject");
        TenantAuthorizationService authorization = mock(TenantAuthorizationService.class);
        UsageAccountingService accounting = mock(UsageAccountingService.class);
        AuthorizedTenantContext context = mock(AuthorizedTenantContext.class);
        UsagePeriod period = new UsagePeriod(
                Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"));
        when(authorization.authorize(identity, tenantId)).thenReturn(context);
        when(context.tenantId()).thenReturn(tenantId);
        when(accounting.quota(tenantId)).thenReturn(new QuotaDecision(
                true, 50, 31, 19, period, QuotaDecision.Reason.AVAILABLE));

        DashboardUsageService.UsageResponse response =
                new DashboardUsageService(authorization, accounting).current(identity, tenantId);

        assertThat(response.reviewAnalysis()).isEqualTo(new DashboardUsageService.ReviewAnalysisUsage(
                period.start(), period.end(), 50, 31, 19));
        verify(authorization).authorize(identity, tenantId);
        verify(accounting).quota(tenantId);
        verifyNoMoreInteractions(accounting);
    }
}
