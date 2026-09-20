package io.prreviewassistant.review.job;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

import io.prreviewassistant.observability.ApplicationMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

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
    private final ApplicationMetrics metrics;

    @Autowired
    public ReviewJobWorker(
            ReviewJobStore store,
            ReviewJobHandler handler,
            ReviewWorkerProperties properties,
            ReviewJobRetryPolicy retryPolicy,
            Clock clock,
            ApplicationMetrics metrics) {
        this.store = store;
        this.handler = handler;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.metrics = metrics;
    }

    public ReviewJobWorker(
            ReviewJobStore store,
            ReviewJobHandler handler,
            ReviewWorkerProperties properties,
            ReviewJobRetryPolicy retryPolicy,
            Clock clock) {
        this(store, handler, properties, retryPolicy, clock, ApplicationMetrics.noop());
    }

    public int pollOnce() {
        if (!properties.enabled()) {
            return 0;
        }
        List<ClaimedReviewJob> claims = store.claimDue(
                clock.instant(), properties.leaseDuration(), properties.batchSize());
        metrics.reviewClaims(claims.size());
        metrics.staleReviewClaimsRecovered((int) claims.stream().filter(ClaimedReviewJob::recoveredLease).count());
        for (ClaimedReviewJob claim : claims) {
            try {
                execute(claim);
            } catch (RuntimeException exception) {
                LOGGER.atWarn().addKeyValue("event", "review_job_transition_failed")
                        .addKeyValue("review_job_id", claim.id())
                        .addKeyValue("attempt", claim.attempt())
                        .log("Review job transition failed");
            }
        }
        return claims.size();
    }

    private void execute(ClaimedReviewJob claim) {
        Instant startedAt = clock.instant();
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
            metrics.reviewJob("stale_claim", Duration.between(startedAt, clock.instant()));
            LOGGER.atDebug().addKeyValue("event", "review_job_stale_claim")
                    .addKeyValue("review_job_id", claim.id())
                    .addKeyValue("attempt", claim.attempt())
                    .log("Review job transition rejected for stale claim");
            return;
        }
        String outcome = switch (result.outcome()) {
            case SUCCESS -> "completed";
            case TERMINAL_FAILURE -> "failed";
            case RETRYABLE_FAILURE -> claim.attempt() >= claim.maxAttempts() ? "failed" : "retry";
        };
        metrics.reviewJob(outcome, Duration.between(startedAt, clock.instant()));
        var log = "failed".equals(outcome) ? LOGGER.atError()
                : "retry".equals(outcome) ? LOGGER.atWarn() : LOGGER.atInfo();
        log.addKeyValue("event", "review_job_finished")
                .addKeyValue("review_job_id", claim.id())
                .addKeyValue("attempt", claim.attempt())
                .addKeyValue("outcome", outcome)
                .addKeyValue("safe_error_code", result.errorCode() == null ? "none" : result.errorCode().value())
                .log("Review job attempt finished");
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
