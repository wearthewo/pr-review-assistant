package io.prreviewassistant.review.publication;

import io.prreviewassistant.review.job.ReviewJobRetryPolicy;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PublicationWorker {
    private static final Logger LOGGER=LoggerFactory.getLogger(PublicationWorker.class);
    private final PublicationStore store; private final ReviewPublicationService service;
    private final ReviewPublicationProperties properties; private final ReviewJobRetryPolicy retryPolicy; private final Clock clock;
    public PublicationWorker(PublicationStore store,ReviewPublicationService service,ReviewPublicationProperties properties,
            ReviewJobRetryPolicy retryPolicy,Clock clock){this.store=store;this.service=service;this.properties=properties;this.retryPolicy=retryPolicy;this.clock=clock;}
    public int pollOnce(){if(!properties.enabled())return 0;var claims=store.claimDue(clock.instant(),properties.leaseDuration(),properties.batchSize());
        for(var claim:claims)execute(claim);return claims.size();}
    private void execute(ClaimedPublicationJob claim){PublicationExecutionResult result;
        try{result=service.publish(claim);}catch(RuntimeException e){result=PublicationExecutionResult.retryable("UNEXPECTED_PUBLICATION_FAILURE");}
        Instant now=clock.instant();boolean changed=switch(result.outcome()){
            case SUCCESS->store.completeJob(claim.id(),claim.claimToken(),now);
            case TERMINAL->failPublicationAndJob(claim,result.errorCode(),now);
            case RETRYABLE->claim.attempt()>=claim.maxAttempts()
                    ?failPublicationAndJob(claim,result.errorCode(),now)
                    :store.retryJob(claim.id(),claim.claimToken(),now.plus(retryPolicy.delayAfterAttempt(claim.attempt())),result.errorCode(),now);};
        if(!changed)LOGGER.debug("Publication transition rejected for stale claim: jobId={}, attempt={}",claim.id(),claim.attempt());}

    private boolean failPublicationAndJob(ClaimedPublicationJob claim,String errorCode,Instant now){
        return store.failPublicationAndJob(
                claim.id(),claim.publicationId(),claim.claimToken(),errorCode,now);
    }
}
