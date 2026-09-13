package io.prreviewassistant.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantOwnershipServiceTest {

    @Test
    void provisioningUsesTheInjectedClockAndReturnsResolvedOwnership() {
        Instant now = Instant.parse("2026-09-13T12:00:00Z");
        TenantOwnershipStore store = mock(TenantOwnershipStore.class);
        TenantContext expected = new TenantContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 101, 202);
        when(store.provision(101, 202, now)).thenReturn(expected);
        TenantOwnershipService service = new TenantOwnershipService(
                store, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.provision(101, 202)).isEqualTo(expected);
        verify(store).provision(101, 202, now);
    }
}
