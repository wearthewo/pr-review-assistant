package io.prreviewassistant.review.publication;

import io.prreviewassistant.github.client.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewPublicationServiceTest {
    private static final Instant NOW=Instant.parse("2026-09-08T10:00:00Z");
    @Test void normalPublicationUsesOnePostAndPersistsOnlyReturnedId(){Fixture f=fixture(PublicationStatus.PENDING);
        when(f.store.markAmbiguous(f.publication.id(),NOW)).thenReturn(true);
        when(f.client.create(anyLong(),anyString(),anyString(),anyInt(),anyString(),any(),anyInt()))
                .thenReturn(new GitHubPublishedReview(42,NOW));when(f.store.markPublished(f.publication.id(),42,NOW,NOW)).thenReturn(true);
        assertThat(f.service.publish(claim(f.publication.id())).outcome()).isEqualTo(PublicationExecutionResult.Outcome.SUCCESS);
        verify(f.client,times(1)).create(anyLong(),anyString(),anyString(),anyInt(),anyString(),any(),anyInt());
    }
    @Test void ambiguousRetryReconcilesMarkerAndNeverPostsAgain(){Fixture f=fixture(PublicationStatus.AMBIGUOUS);
        String marker=GitHubReviewRenderer.MARKER_PREFIX+f.publication.publicationKey()+" -->";
        when(f.client.list(anyLong(),anyString(),anyString(),anyInt(),eq(1),anyInt())).thenReturn(
                new GitHubReviewPage(List.of(new GitHubReviewSummary(77,"review\n"+marker,NOW)),false));
        when(f.store.markPublished(f.publication.id(),77,NOW,NOW)).thenReturn(true);
        assertThat(f.service.publish(claim(f.publication.id())).outcome()).isEqualTo(PublicationExecutionResult.Outcome.SUCCESS);
        verify(f.client,never()).create(anyLong(),anyString(),anyString(),anyInt(),anyString(),any(),anyInt());
    }
    @Test void ambiguousTransportIsRetryableAndPayloadContentIsAbsentFromResult(){Fixture f=fixture(PublicationStatus.PENDING);
        when(f.store.markAmbiguous(f.publication.id(),NOW)).thenReturn(true);
        when(f.client.create(anyLong(),anyString(),anyString(),anyInt(),anyString(),any(),anyInt()))
                .thenThrow(GitHubReviewException.of(GitHubReviewErrorType.AMBIGUOUS_DELIVERY));
        PublicationExecutionResult result=f.service.publish(claim(f.publication.id()));
        assertThat(result.outcome()).isEqualTo(PublicationExecutionResult.Outcome.RETRYABLE);
        assertThat(result.toString()).doesNotContain("sensitive finding");
    }
    @Test void historicalPublicationWithoutTenantStopsBeforeGitHub(){Fixture f=fixture(PublicationStatus.PENDING);
        ReviewPublication unowned=new ReviewPublication(f.publication.id(),f.publication.analysisJobId(),1,2,
                "octo","repo",3,"a".repeat(40),f.publication.publicationKey(),1,1,
                f.publication.encodedPayload(),PublicationStatus.PENDING,null,null);
        when(f.store.find(unowned.id())).thenReturn(Optional.of(unowned));

        PublicationExecutionResult result=f.service.publish(claim(unowned.id()));

        assertThat(result.outcome()).isEqualTo(PublicationExecutionResult.Outcome.TERMINAL);
        assertThat(result.errorCode()).isEqualTo("TENANT_NOT_RESOLVED");
        verifyNoInteractions(f.client);
    }
    @Test void definiteGitHubRejectionMarksPublicationTerminal() {
        Fixture f = fixture(PublicationStatus.PENDING);
        when(f.store.markAmbiguous(f.publication.id(), NOW)).thenReturn(true);
        when(f.client.create(anyLong(), anyString(), anyString(), anyInt(), anyString(), any(), anyInt()))
                .thenThrow(GitHubReviewException.of(GitHubReviewErrorType.INVALID_REVIEW));
        when(f.store.markPublicationFailed(f.publication.id(), "GITHUB_REVIEW_INVALID", NOW)).thenReturn(true);

        PublicationExecutionResult result = f.service.publish(claim(f.publication.id()));

        assertThat(result.outcome()).isEqualTo(PublicationExecutionResult.Outcome.TERMINAL);
        assertThat(result.errorCode()).isEqualTo("GITHUB_REVIEW_INVALID");
        verify(f.store).markPublicationFailed(f.publication.id(), "GITHUB_REVIEW_INVALID", NOW);
    }
    private Fixture fixture(PublicationStatus status){PublicationStore store=mock(PublicationStore.class);GitHubReviewPublisher client=mock(GitHubReviewPublisher.class);
        PublicationPayloadCodec codec=new PublicationPayloadCodec(20000);String key="b".repeat(64);
        String encoded=codec.encode(new PublicationPayload(1,"sensitive finding\n"+GitHubReviewRenderer.MARKER_PREFIX+key+" -->",List.of()));
        ReviewPublication p=new ReviewPublication(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),1,2,"octo","repo",3,"a".repeat(40),key,1,1,encoded,status,null,null);
        when(store.find(p.id())).thenReturn(Optional.of(p));Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
        ReviewPublicationService service=new ReviewPublicationService(store,codec,client,properties(),clock);return new Fixture(store,client,p,service);}
    private ClaimedPublicationJob claim(UUID publicationId){return new ClaimedPublicationJob(UUID.randomUUID(),publicationId,UUID.randomUUID(),1,3,NOW,NOW.plusSeconds(60));}
    private ReviewPublicationProperties properties(){return new ReviewPublicationProperties(true,6000,2000,20000,10,3,
            Duration.ofSeconds(10),Duration.ofMinutes(5),Duration.ofSeconds(5),10,Duration.ofMinutes(2));}
    private record Fixture(PublicationStore store,GitHubReviewPublisher client,ReviewPublication publication,ReviewPublicationService service){}
}
