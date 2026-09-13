package io.prreviewassistant.review.publication;

import io.prreviewassistant.review.analysis.ValidatedReview;
import io.prreviewassistant.review.retrieval.PullRequestSnapshot;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import io.prreviewassistant.tenant.TenantContext;

public class PublicationHandoffService {
    private final PublicationStore store; private final GitHubReviewRenderer renderer;
    private final PublicationPayloadCodec codec; private final ReviewPublicationProperties properties; private final Clock clock;
    public PublicationHandoffService(PublicationStore store,GitHubReviewRenderer renderer,PublicationPayloadCodec codec,
            ReviewPublicationProperties properties,Clock clock){this.store=store;this.renderer=renderer;this.codec=codec;this.properties=properties;this.clock=clock;}

    @Transactional
    public PublicationHandoffResult handoff(UUID analysisJobId,TenantContext tenantContext,
            ValidatedReview review,PullRequestSnapshot snapshot){
        if(review.findings().isEmpty())return PublicationHandoffResult.NO_FINDINGS;
        String key=PublicationKey.create(review.target(),PublicationPayload.CURRENT_VERSION,review.findings());
        PublicationPayload payload=renderer.render(review,key);
        return store.create(analysisJobId,tenantContext,review.target(),snapshot.repositoryOwner(),snapshot.repositoryName(),key,
                review.findings().size(),codec.encode(payload),properties.maxAttempts(),clock.instant());
    }

    public boolean alreadyHandedOff(UUID analysisJobId) {
        return store.existsForAnalysisJob(analysisJobId);
    }
}
