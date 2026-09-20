package io.prreviewassistant.review.publication;

import io.prreviewassistant.github.client.GitHubReviewPublisher;
import io.prreviewassistant.review.job.ReviewJobRetryPolicy;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;import org.springframework.context.annotation.Configuration;
import io.prreviewassistant.observability.ApplicationMetrics;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(ReviewPublicationProperties.class)
class ReviewPublicationConfiguration {
    @Bean PublicationPayloadCodec publicationPayloadCodec(ReviewPublicationProperties p){return new PublicationPayloadCodec(p.maxPayloadChars());}
    @Bean GitHubReviewRenderer githubReviewRenderer(ReviewPublicationProperties p){return new GitHubReviewRenderer(p);}
    @Bean PublicationHandoffService publicationHandoffService(PublicationStore s,PublicationPayloadCodec c,GitHubReviewRenderer r,
            ReviewPublicationProperties p,Clock clock){return new PublicationHandoffService(s,r,c,p,clock);}
    @Bean ReviewPublicationService reviewPublicationService(PublicationStore s,PublicationPayloadCodec c,GitHubReviewPublisher client,
            ReviewPublicationProperties p,Clock clock,ApplicationMetrics metrics){return new ReviewPublicationService(s,c,client,p,clock,metrics);}
    @Bean PublicationWorker publicationWorker(PublicationStore s,ReviewPublicationService service,ReviewPublicationProperties p,Clock clock,
            ApplicationMetrics metrics){
        return new PublicationWorker(s,service,p,new ReviewJobRetryPolicy(p.retryBaseDelay(),p.retryMaxDelay()),clock,metrics);}
}
