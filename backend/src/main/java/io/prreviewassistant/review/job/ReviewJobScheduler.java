package io.prreviewassistant.review.job;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "review.worker", name = "enabled", havingValue = "true")
final class ReviewJobScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewJobScheduler.class);

    private final ReviewJobWorker worker;
    private final AtomicBoolean polling = new AtomicBoolean();

    ReviewJobScheduler(ReviewJobWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${review.worker.poll-interval}")
    void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.pollOnce();
        } catch (RuntimeException exception) {
            LOGGER.warn("Review job poll failed; a later scheduler tick will retry");
        } finally {
            polling.set(false);
        }
    }
}
