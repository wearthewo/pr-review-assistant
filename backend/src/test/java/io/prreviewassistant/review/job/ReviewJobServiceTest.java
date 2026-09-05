package io.prreviewassistant.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReviewJobServiceTest {

    @Test
    void createsWithConfiguredAttemptsAtInjectedClockTime() {
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        ReviewJobStore store = mock(ReviewJobStore.class);
        ReviewJob expected = new ReviewJob(UUID.randomUUID(), ReviewJobStatus.READY, 0, 4, now,
                null, null, null, null, null, null, now, now);
        when(store.create(4, now)).thenReturn(expected);
        ReviewJobProperties properties =
                new ReviewJobProperties(4, Duration.ofSeconds(10), Duration.ofMinutes(5));
        ReviewJobService service = new ReviewJobService(store, properties, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.createPlaceholder()).isSameAs(expected);
        verify(store).create(4, now);
    }

    @Test
    void createsForReviewTargetWithConfiguredAttemptsAtInjectedClockTime() {
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        ReviewTarget target = new ReviewTarget(1, 2, 3, "a".repeat(40));
        ReviewJobStore store = mock(ReviewJobStore.class);
        when(store.createForReviewTarget(target, 4, now)).thenReturn(ReviewJobCreationResult.CREATED);
        ReviewJobProperties properties =
                new ReviewJobProperties(4, Duration.ofSeconds(10), Duration.ofMinutes(5));
        ReviewJobService service = new ReviewJobService(store, properties, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.createForReviewTarget(target)).isEqualTo(ReviewJobCreationResult.CREATED);
        verify(store).createForReviewTarget(target, 4, now);
    }
}
