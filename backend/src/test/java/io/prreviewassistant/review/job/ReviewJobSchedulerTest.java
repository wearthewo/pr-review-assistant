package io.prreviewassistant.review.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ReviewJobSchedulerTest {

    @Test
    void pollFailureDoesNotEscapeAndKillFutureScheduling() {
        ReviewJobWorker worker = mock(ReviewJobWorker.class);
        doThrow(new IllegalStateException("external detail")).when(worker).pollOnce();

        assertThatCode(() -> new ReviewJobScheduler(worker).poll()).doesNotThrowAnyException();
    }

    @Test
    void overlappingTickIsSkippedWithinOneProcess() throws Exception {
        ReviewJobWorker worker = mock(ReviewJobWorker.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test synchronization timed out");
            }
            return 0;
        }).when(worker).pollOnce();
        ReviewJobScheduler scheduler = new ReviewJobScheduler(worker);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> first = executor.submit(scheduler::poll);
            if (!entered.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test synchronization timed out");
            }
            scheduler.poll();
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }

        verify(worker, times(1)).pollOnce();
    }
}
