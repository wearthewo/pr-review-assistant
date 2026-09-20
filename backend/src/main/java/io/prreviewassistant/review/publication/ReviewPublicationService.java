package io.prreviewassistant.review.publication;

import io.prreviewassistant.github.client.GitHubPublishedReview;
import io.prreviewassistant.github.client.GitHubReviewErrorType;
import io.prreviewassistant.github.client.GitHubReviewException;
import io.prreviewassistant.github.client.GitHubReviewPage;
import io.prreviewassistant.github.client.GitHubReviewPublisher;
import java.time.Clock;
import java.time.Instant;
import io.prreviewassistant.observability.ApplicationMetrics;

public final class ReviewPublicationService {
    private static final int MAX_RESPONSE_BYTES=512*1024;
    private final PublicationStore store; private final PublicationPayloadCodec codec;
    private final GitHubReviewPublisher client; private final ReviewPublicationProperties properties; private final Clock clock;
    private final ApplicationMetrics metrics;
    public ReviewPublicationService(PublicationStore store,PublicationPayloadCodec codec,GitHubReviewPublisher client,
            ReviewPublicationProperties properties,Clock clock){
        this(store, codec, client, properties, clock, ApplicationMetrics.noop());}
    public ReviewPublicationService(PublicationStore store,PublicationPayloadCodec codec,GitHubReviewPublisher client,
            ReviewPublicationProperties properties,Clock clock,ApplicationMetrics metrics){this.store=store;this.codec=codec;this.client=client;this.properties=properties;this.clock=clock;this.metrics=metrics;}

    public PublicationExecutionResult publish(ClaimedPublicationJob job){
        ReviewPublication publication=store.find(job.publicationId()).orElse(null);
        if(publication==null)return PublicationExecutionResult.terminal("PUBLICATION_NOT_FOUND");
        if(publication.tenantId()==null || publication.tenantRepositoryId()==null)
            return PublicationExecutionResult.terminal("TENANT_NOT_RESOLVED");
        if(publication.status()==PublicationStatus.PUBLISHED)return PublicationExecutionResult.success();
        if(publication.status()==PublicationStatus.FAILED)return PublicationExecutionResult.terminal("PUBLICATION_ALREADY_FAILED");
        PublicationPayload payload;
        try{payload=codec.decode(publication.encodedPayload());}catch(IllegalArgumentException e){
            store.markPublicationFailed(publication.id(),"PUBLICATION_PAYLOAD_INVALID",clock.instant());
            return PublicationExecutionResult.terminal("PUBLICATION_PAYLOAD_INVALID");}
        String marker=GitHubReviewRenderer.MARKER_PREFIX+publication.publicationKey()+" -->";
        if(publication.status()==PublicationStatus.AMBIGUOUS){
            PublicationExecutionResult reconciled=reconcile(publication,marker);
            if(reconciled!=null)return reconciled;
        }
        Instant beforePost=clock.instant();
        if(!store.markAmbiguous(publication.id(),beforePost))return PublicationExecutionResult.retryable("PUBLICATION_STATE_CHANGED");
        try{
            GitHubPublishedReview created=client.create(publication.installationId(),publication.repositoryOwner(),
                    publication.repositoryName(),publication.pullRequestNumber(),publication.headSha(),payload,MAX_RESPONSE_BYTES);
            return store.markPublished(publication.id(),created.id(),created.publishedAt(),clock.instant())
                    ?PublicationExecutionResult.success():PublicationExecutionResult.retryable("PUBLICATION_STATE_CHANGED");
        } catch (GitHubReviewException exception) {
            PublicationExecutionResult result = map(exception.type(), true);
            if (result.outcome() == PublicationExecutionResult.Outcome.TERMINAL
                    && !store.markPublicationFailed(
                            publication.id(), result.errorCode(), clock.instant())) {
                return PublicationExecutionResult.retryable("PUBLICATION_STATE_CHANGED");
            }
            return result;
        }
    }

    private PublicationExecutionResult reconcile(ReviewPublication publication,String marker){
        try{
            for(int page=1;page<=properties.reconciliationMaxPages();page++){
                GitHubReviewPage response=client.list(publication.installationId(),publication.repositoryOwner(),
                        publication.repositoryName(),publication.pullRequestNumber(),page,MAX_RESPONSE_BYTES);
                var match=response.reviews().stream().filter(r->r.body()!=null&&r.body().contains(marker)).findFirst();
                if(match.isPresent()){
                    metrics.publicationReconciled();
                    Instant published=match.get().publishedAt()==null?clock.instant():match.get().publishedAt();
                    return store.markPublished(publication.id(),match.get().id(),published,clock.instant())
                            ?PublicationExecutionResult.success():PublicationExecutionResult.retryable("PUBLICATION_STATE_CHANGED");
                }
                if(!response.hasNextPage())return null;
            }
            return PublicationExecutionResult.terminal("PUBLICATION_RECONCILIATION_LIMIT");
        }catch(GitHubReviewException e){return map(e.type(),false);}
    }

    private PublicationExecutionResult map(GitHubReviewErrorType type,boolean posting){return switch(type){
        case AMBIGUOUS_DELIVERY -> PublicationExecutionResult.retryable("GITHUB_PUBLICATION_AMBIGUOUS");
        case RATE_LIMITED -> PublicationExecutionResult.retryable("GITHUB_RATE_LIMITED");
        case TRANSIENT -> PublicationExecutionResult.retryable(posting?"GITHUB_PUBLICATION_AMBIGUOUS":"GITHUB_TRANSIENT_FAILURE");
        case AUTHENTICATION -> PublicationExecutionResult.terminal("GITHUB_AUTHENTICATION_REJECTED");
        case PERMISSION -> PublicationExecutionResult.terminal("GITHUB_PUBLICATION_PERMISSION_DENIED");
        case NOT_FOUND -> PublicationExecutionResult.terminal("GITHUB_RESOURCE_NOT_ACCESSIBLE");
        case INVALID_REVIEW -> PublicationExecutionResult.terminal("GITHUB_REVIEW_INVALID");
        case MALFORMED_RESPONSE -> posting
                ? PublicationExecutionResult.retryable("GITHUB_PUBLICATION_AMBIGUOUS")
                : PublicationExecutionResult.terminal("GITHUB_RESPONSE_INVALID");
        case RESPONSE_TOO_LARGE -> posting
                ? PublicationExecutionResult.retryable("GITHUB_PUBLICATION_AMBIGUOUS")
                : PublicationExecutionResult.terminal("GITHUB_RESPONSE_TOO_LARGE");
    };}
}
