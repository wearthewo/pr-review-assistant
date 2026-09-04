package io.prreviewassistant.review.job;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class ReviewJobWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewJobWorker.class);
    private static final ReviewJobErrorCode UNEXPECTED_FAILURE =
            new ReviewJobErrorCode("UNEXPECTED_HANDLER_FAILURE");

    private final ReviewJobStore store;
    private final ReviewJobHandler handler;
    private final ReviewWorkerProperties properties;
    private final ReviewJobRetryPolicy retryPolicy;
    private final Clock clock;

    public ReviewJobWorker(
            ReviewJobStore store,
            ReviewJobHandler handler,
            ReviewWorkerProperties properties,
            ReviewJobRetryPolicy retryPolicy,
            Clock clock) {
        this.store = store;
        this.handler = handler;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    public int pollOnce() {
        if (!properties.enabled()) {
            return 0;
        }
        List<ClaimedReviewJob> claims = store.claimDue(
                clock.instant(), properties.leaseDuration(), properties.batchSize());
        for (ClaimedReviewJob claim : claims) {
            try {
                execute(claim);
            } catch (RuntimeException exception) {
                LOGGER.warn("Review job transition failed: jobId={}, attempt={}", claim.id(), claim.attempt());
            }
        }
        return claims.size();
    }

    private void execute(ClaimedReviewJob claim) {
        ReviewJobExecutionResult result;
        try {
            result = handler.handle(claim);
            if (result == null) {
                result = ReviewJobExecutionResult.retryable(UNEXPECTED_FAILURE.value());
            }
        } catch (RuntimeException exception) {
            result = ReviewJobExecutionResult.retryable(UNEXPECTED_FAILURE.value());
        }

        Instant now = clock.instant();
        boolean transitioned = switch (result.outcome()) {
            case SUCCESS -> store.complete(claim.id(), claim.claimToken(), now);
            case TERMINAL_FAILURE -> store.fail(claim.id(), claim.claimToken(), result.errorCode(), now);
            case RETRYABLE_FAILURE -> transitionRetryableFailure(claim, result.errorCode(), now);
        };
        if (!transitioned) {
            LOGGER.debug("Review job transition rejected for stale claim: jobId={}, attempt={}",
                    claim.id(), claim.attempt());
        }
    }

    private boolean transitionRetryableFailure(
            ClaimedReviewJob claim,
            ReviewJobErrorCode errorCode,
            Instant now) {
        if (claim.attempt() >= claim.maxAttempts()) {
            return store.fail(claim.id(), claim.claimToken(), errorCode, now);
        }
        Instant nextAttemptAt = now.plus(retryPolicy.delayAfterAttempt(claim.attempt()));
        return store.retry(claim.id(), claim.claimToken(), nextAttemptAt, errorCode, now);
    }
}
