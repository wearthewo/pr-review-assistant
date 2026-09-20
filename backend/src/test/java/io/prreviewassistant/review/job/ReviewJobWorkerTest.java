package io.prreviewassistant.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.prreviewassistant.observability.ApplicationMetrics;

import org.junit.jupiter.api.Test;

class ReviewJobWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final ReviewJobRetryPolicy RETRY_POLICY =
            new ReviewJobRetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5));

    @Test
    void disabledWorkerDoesNotPoll() {
        ReviewJobStore store = mock(ReviewJobStore.class);
        ReviewJobHandler handler = mock(ReviewJobHandler.class);
        ReviewJobWorker worker = worker(store, handler, false, 7);

        assertThat(worker.pollOnce()).isZero();
        verifyNoInteractions(store, handler);
    }

    @Test
    void pollOnceHonorsBatchLimitAndCompletesSuccessfulJob() {
        ReviewJobStore store = mock(ReviewJobStore.class);
        ReviewJobHandler handler = job -> ReviewJobExecutionResult.success();
        ClaimedReviewJob claim = claim(1, 3);
        when(store.claimDue(NOW, LEASE, 7)).thenReturn(List.of(claim));
        when(store.complete(claim.id(), claim.claimToken(), NOW)).thenReturn(true);

        assertThat(worker(store, handler, true, 7).pollOnce()).isOne();

        verify(store).claimDue(NOW, LEASE, 7);
        verify(store).complete(claim.id(), claim.claimToken(), NOW);
    }

    @Test
    void retryableFailureSchedulesDeterministicBackoff() {
        ReviewJobStore store = mock(ReviewJobStore.class);
        ReviewJobErrorCode code = new ReviewJobErrorCode("TEMPORARY_FAILURE");
        ClaimedReviewJob claim = claim(2, 3);
        when(store.claimDue(NOW, LEASE, 7)).thenReturn(List.of(claim));
        when(store.retry(eq(claim.id()), eq(claim.claimToken()), eq(NOW.plusSeconds(20)), eq(code), eq(NOW)))
                .thenReturn(true);

        worker(store, job -> ReviewJobExecutionResult.retryable(code.value()), true, 7).pollOnce();

        verify(store).retry(claim.id(), claim.claimToken(), NOW.plusSeconds(20), code, NOW);
        verify(store, never()).fail(eq(claim.id()), eq(claim.claimToken()), eq(code), eq(NOW));
    }

    @Test
    void retryableFailureAtMaxAttemptsBecomesTerminal() {
        ReviewJobStore store = mock(ReviewJobStore.class);
        ReviewJobErrorCode code = new ReviewJobErrorCode("STILL_UNAVAILABLE");
        ClaimedReviewJob claim = claim(3, 3);
        when(store.claimDue(NOW, LEASE, 7)).thenReturn(List.of(claim));

        worker(store, job -> ReviewJobExecutionResult.retryable(code.value()), true, 7).pollOnce();

        verify(store).fail(claim.id(), claim.claimToken(), code, NOW);
        verify(store, never()).retry(eq(claim.id()), eq(claim.claimToken()), eq(NOW), eq(code), eq(NOW));
    }

    @Test
    void terminalFailureIsPersistedWithoutRetry() {
        ReviewJobStore store = mock(ReviewJobStore.class);
        ReviewJobErrorCode code = new ReviewJobErrorCode("INVALID_WORK");
        ClaimedReviewJob claim = claim(1, 3);
        when(store.claimDue(NOW, LEASE, 7)).thenReturn(List.of(claim));

        worker(store, job -> ReviewJobExecutionResult.terminal(code.value()), true, 7).pollOnce();

        verify(store).fail(claim.id(), claim.claimToken(), code, NOW);
    }

    @Test
    void oneCrashingHandlerDoesNotPreventLaterJobsInBatch() {
        ReviewJobStore store = mock(ReviewJobStore.class);
        ClaimedReviewJob first = claim(1, 3);
        ClaimedReviewJob second = claim(1, 3);
        when(store.claimDue(NOW, LEASE, 7)).thenReturn(List.of(first, second));
        AtomicInteger invocation = new AtomicInteger();
        ReviewJobHandler handler = job -> {
            if (invocation.getAndIncrement() == 0) {
                throw new IllegalStateException("untrusted external detail");
            }
            return ReviewJobExecutionResult.success();
        };

        assertThat(worker(store, handler, true, 7).pollOnce()).isEqualTo(2);

        verify(store).retry(first.id(), first.claimToken(), NOW.plusSeconds(10),
                new ReviewJobErrorCode("UNEXPECTED_HANDLER_FAILURE"), NOW);
        verify(store).complete(second.id(), second.claimToken(), NOW);
    }

    @Test
    void recordsCompletedRetryAndRecoveredLeaseSemantics() {
        ReviewJobStore store = mock(ReviewJobStore.class);
        ClaimedReviewJob completed = claim(1, 3);
        ClaimedReviewJob recoveredRetry = new ClaimedReviewJob(
                UUID.randomUUID(), UUID.randomUUID(), 2, 3, NOW, NOW.plus(LEASE),
                null, null, true);
        when(store.claimDue(NOW, LEASE, 7)).thenReturn(List.of(completed, recoveredRetry));
        when(store.complete(completed.id(), completed.claimToken(), NOW)).thenReturn(true);
        when(store.retry(eq(recoveredRetry.id()), eq(recoveredRetry.claimToken()),
                eq(NOW.plusSeconds(20)), eq(new ReviewJobErrorCode("TEMPORARY_FAILURE")), eq(NOW)))
                .thenReturn(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger call = new AtomicInteger();
        ReviewJobHandler handler = ignored -> call.getAndIncrement() == 0
                ? ReviewJobExecutionResult.success()
                : ReviewJobExecutionResult.retryable("TEMPORARY_FAILURE");
        ReviewWorkerProperties properties =
                new ReviewWorkerProperties(true, Duration.ofSeconds(5), 7, LEASE);
        ReviewJobWorker worker = new ReviewJobWorker(store, handler, properties, RETRY_POLICY,
                Clock.fixed(NOW, ZoneOffset.UTC), new ApplicationMetrics(registry));

        worker.pollOnce();

        assertThat(registry.get(ApplicationMetrics.PREFIX + ".review.jobs.claimed").counter().count())
                .isEqualTo(2);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".review.jobs.stale.recovered").counter().count())
                .isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".review.jobs.outcomes")
                .tag("outcome", "completed").counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".review.jobs.outcomes")
                .tag("outcome", "retry").counter().count()).isEqualTo(1);
    }

    private ReviewJobWorker worker(
            ReviewJobStore store,
            ReviewJobHandler handler,
            boolean enabled,
            int batchSize) {
        ReviewWorkerProperties properties =
                new ReviewWorkerProperties(enabled, Duration.ofSeconds(5), batchSize, LEASE);
        return new ReviewJobWorker(store, handler, properties, RETRY_POLICY, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ClaimedReviewJob claim(int attempt, int maxAttempts) {
        return new ClaimedReviewJob(UUID.randomUUID(), UUID.randomUUID(), attempt, maxAttempts,
                NOW, NOW.plus(LEASE));
    }
}
