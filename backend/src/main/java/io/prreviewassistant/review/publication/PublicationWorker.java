package io.prreviewassistant.review.publication;

import io.prreviewassistant.review.job.ReviewJobRetryPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import io.prreviewassistant.observability.ApplicationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PublicationWorker {
    private static final Logger LOGGER=LoggerFactory.getLogger(PublicationWorker.class);
    private final PublicationStore store; private final ReviewPublicationService service;
    private final ReviewPublicationProperties properties; private final ReviewJobRetryPolicy retryPolicy; private final Clock clock;
    private final ApplicationMetrics metrics;
    public PublicationWorker(PublicationStore store,ReviewPublicationService service,ReviewPublicationProperties properties,
            ReviewJobRetryPolicy retryPolicy,Clock clock){this(store,service,properties,retryPolicy,clock,ApplicationMetrics.noop());}
    public PublicationWorker(PublicationStore store,ReviewPublicationService service,ReviewPublicationProperties properties,
            ReviewJobRetryPolicy retryPolicy,Clock clock,ApplicationMetrics metrics){this.store=store;this.service=service;this.properties=properties;this.retryPolicy=retryPolicy;this.clock=clock;this.metrics=metrics;}
    public int pollOnce(){if(!properties.enabled())return 0;var claims=store.claimDue(clock.instant(),properties.leaseDuration(),properties.batchSize());
        for(var claim:claims)execute(claim);return claims.size();}
    private void execute(ClaimedPublicationJob claim){PublicationExecutionResult result;
        Instant startedAt=clock.instant();
        try{result=service.publish(claim);}catch(RuntimeException e){result=PublicationExecutionResult.retryable("UNEXPECTED_PUBLICATION_FAILURE");}
        Instant now=clock.instant();boolean changed=switch(result.outcome()){
            case SUCCESS->store.completeJob(claim.id(),claim.claimToken(),now);
            case TERMINAL->failPublicationAndJob(claim,result.errorCode(),now);
            case RETRYABLE->claim.attempt()>=claim.maxAttempts()
                    ?failPublicationAndJob(claim,result.errorCode(),now)
                    :store.retryJob(claim.id(),claim.claimToken(),now.plus(retryPolicy.delayAfterAttempt(claim.attempt())),result.errorCode(),now);};
        String outcome=!changed?"stale_claim":switch(result.outcome()){
            case SUCCESS->"success";
            case TERMINAL->"terminal_failure";
            case RETRYABLE->result.errorCode().contains("AMBIGUOUS")?"ambiguous"
                    :claim.attempt()>=claim.maxAttempts()?"terminal_failure":"retryable_failure";};
        metrics.publication(outcome,Duration.between(startedAt,clock.instant()));
        if(!changed){LOGGER.atDebug().addKeyValue("event","publication_stale_claim")
                .addKeyValue("publication_id",claim.publicationId()).addKeyValue("attempt",claim.attempt())
                .log("Publication transition rejected for stale claim");return;}
        var log="success".equals(outcome)?LOGGER.atInfo():"terminal_failure".equals(outcome)?LOGGER.atError():LOGGER.atWarn();
        log.addKeyValue("event","publication_attempt_finished")
                .addKeyValue("publication_id",claim.publicationId()).addKeyValue("attempt",claim.attempt())
                .addKeyValue("outcome",outcome)
                .addKeyValue("safe_error_code",result.errorCode()==null?"none":result.errorCode())
                .log("Publication attempt finished");}

    private boolean failPublicationAndJob(ClaimedPublicationJob claim,String errorCode,Instant now){
        return store.failPublicationAndJob(
                claim.id(),claim.publicationId(),claim.claimToken(),errorCode,now);
    }
}
