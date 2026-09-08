package io.prreviewassistant.review.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.prreviewassistant.review.job.ReviewJobRetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicationWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    @Test
    void disabledWorkerDoesNotClaimOrPublish() {
        PublicationStore store = mock(PublicationStore.class);
        ReviewPublicationService service = mock(ReviewPublicationService.class);
        PublicationWorker worker = worker(store, service, properties(false, 10));

        assertThat(worker.pollOnce()).isZero();
        verify(store, never()).claimDue(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        verify(service, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retryableFailureSchedulesDeterministicBackoffAndHonorsBatchLimit() {
        PublicationStore store = mock(PublicationStore.class);
        ReviewPublicationService service = mock(ReviewPublicationService.class);
        ReviewPublicationProperties properties = properties(true, 2);
        ClaimedPublicationJob claim = claim(1, 3);
        when(store.claimDue(NOW, properties.leaseDuration(), 2)).thenReturn(List.of(claim));
        when(service.publish(claim)).thenReturn(PublicationExecutionResult.retryable("GITHUB_RATE_LIMITED"));
        when(store.retryJob(claim.id(), claim.claimToken(), NOW.plusSeconds(10),
                "GITHUB_RATE_LIMITED", NOW)).thenReturn(true);

        assertThat(worker(store, service, properties).pollOnce()).isEqualTo(1);
        verify(store).retryJob(claim.id(), claim.claimToken(), NOW.plusSeconds(10),
                "GITHUB_RATE_LIMITED", NOW);
    }

    @Test
    void exhaustedRetryAndTerminalFailureBecomeFailedJobs() {
        PublicationStore store = mock(PublicationStore.class);
        ReviewPublicationService service = mock(ReviewPublicationService.class);
        ReviewPublicationProperties properties = properties(true, 10);
        ClaimedPublicationJob exhausted = claim(3, 3);
        ClaimedPublicationJob terminal = claim(1, 3);
        when(store.claimDue(NOW, properties.leaseDuration(), 10)).thenReturn(List.of(exhausted, terminal));
        when(service.publish(exhausted)).thenReturn(PublicationExecutionResult.retryable("GITHUB_TRANSIENT_FAILURE"));
        when(service.publish(terminal)).thenReturn(PublicationExecutionResult.terminal("GITHUB_REVIEW_INVALID"));

        assertThat(worker(store, service, properties).pollOnce()).isEqualTo(2);
        verify(store).failPublicationAndJob(exhausted.id(), exhausted.publicationId(),
                exhausted.claimToken(), "GITHUB_TRANSIENT_FAILURE", NOW);
        verify(store).failPublicationAndJob(terminal.id(), terminal.publicationId(),
                terminal.claimToken(), "GITHUB_REVIEW_INVALID", NOW);
    }

    private PublicationWorker worker(
            PublicationStore store, ReviewPublicationService service, ReviewPublicationProperties properties) {
        return new PublicationWorker(
                store,
                service,
                properties,
                new ReviewJobRetryPolicy(properties.retryBaseDelay(), properties.retryMaxDelay()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ReviewPublicationProperties properties(boolean enabled, int batchSize) {
        return new ReviewPublicationProperties(
                enabled,
                6_000,
                2_000,
                20_000,
                10,
                3,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                batchSize,
                Duration.ofMinutes(2));
    }

    private ClaimedPublicationJob claim(int attempt, int maxAttempts) {
        return new ClaimedPublicationJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                attempt,
                maxAttempts,
                NOW,
                NOW.plusSeconds(60));
    }
}
