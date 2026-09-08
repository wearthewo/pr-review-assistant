package io.prreviewassistant.review.publication;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="review.publication",name="enabled",havingValue="true")
final class PublicationScheduler {
    private static final Logger LOGGER=LoggerFactory.getLogger(PublicationScheduler.class);
    private final PublicationWorker worker;private final AtomicBoolean polling=new AtomicBoolean();
    PublicationScheduler(PublicationWorker worker){this.worker=worker;}
    @Scheduled(fixedDelayString="${review.publication.poll-interval}")void poll(){if(!polling.compareAndSet(false,true))return;
        try{worker.pollOnce();}catch(RuntimeException e){LOGGER.warn("Publication poll failed; a later scheduler tick will retry");}finally{polling.set(false);}}
}
