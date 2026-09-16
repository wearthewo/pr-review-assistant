package io.prreviewassistant.dashboard.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import io.prreviewassistant.identity.AuthorizedTenantContext;
import io.prreviewassistant.identity.TenantAuthorizationService;
import org.junit.jupiter.api.Test;

class DashboardReviewServiceTest {
    @Test
    void oneBoundedStoreQueryProducesKeysetContinuationWithoutNPlusOneReads() {
        UUID tenantId = UUID.randomUUID();
        AuthenticatedUserIdentity identity = new AuthenticatedUserIdentity("https://issuer.example/", "subject");
        TenantAuthorizationService authorization = mock(TenantAuthorizationService.class);
        AuthorizedTenantContext context = mock(AuthorizedTenantContext.class);
        DashboardReviewStore store = mock(DashboardReviewStore.class);
        when(context.tenantId()).thenReturn(tenantId);
        when(authorization.authorize(identity, tenantId)).thenReturn(context);
        List<DashboardReviewStore.ReviewRecord> records = IntStream.rangeClosed(1, 21)
                .mapToObj(index -> record(index, Instant.parse("2026-09-16T12:00:00Z").minusSeconds(index)))
                .toList();
        when(store.findByTenant(tenantId, null, 21)).thenReturn(records);

        DashboardReviewService.ReviewPage page = new DashboardReviewService(
                authorization, store, new DashboardReviewCursorCodec()).list(identity, tenantId, null, 20);

        assertThat(page.reviews()).hasSize(20);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank().hasSizeLessThanOrEqualTo(160);
        verify(authorization).authorize(identity, tenantId);
        verify(store).findByTenant(tenantId, null, 21);
        verifyNoMoreInteractions(store);
    }

    private DashboardReviewStore.ReviewRecord record(int sequence, Instant createdAt) {
        return new DashboardReviewStore.ReviewRecord(
                new UUID(0, sequence), 1000 + sequence, sequence, "%040x".formatted(sequence),
                "COMPLETED", null, null, createdAt, createdAt);
    }
}
