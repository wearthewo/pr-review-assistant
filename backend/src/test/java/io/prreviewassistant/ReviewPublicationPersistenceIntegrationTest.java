package io.prreviewassistant;

import io.prreviewassistant.review.job.ReviewTarget;
import io.prreviewassistant.review.publication.*;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"REVIEW_WORKER_ENABLED=false","REVIEW_PUBLICATION_ENABLED=false",
        "DB_JDBC_URL=jdbc:postgresql://unused","DB_USERNAME=unused","DB_PASSWORD=unused",
        "GITHUB_APP_ID=1","GITHUB_PRIVATE_KEY_PATH=unused","GITHUB_WEBHOOK_SECRET=test-secret-that-is-long-enough"})
@Import(PostgreSqlTestConfiguration.class)
class ReviewPublicationPersistenceIntegrationTest {
    private static final Instant NOW=Instant.parse("2026-09-08T10:00:00Z");
    private static final ReviewTarget TARGET=new ReviewTarget(1,2,3,"a".repeat(40));
    @Autowired JdbcClient jdbc; @Autowired PublicationStore store;
    @BeforeEach void clean(){jdbc.sql("DELETE FROM publication_jobs").update();jdbc.sql("DELETE FROM review_publications").update();jdbc.sql("DELETE FROM review_jobs").update();}

    @Test void handoffPersistsImmutablePayloadAndQueueAtomically(){UUID analysis=seedAnalysisJob();
        assertThat(store.create(analysis,TARGET,"octo","repo","b".repeat(64),1,"{\"version\":1}",3,NOW))
                .isEqualTo(PublicationHandoffResult.CREATED);
        assertThat(jdbc.sql("SELECT count(*) FROM review_publications").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM publication_jobs WHERE status='READY'").query(Long.class).single()).isEqualTo(1);
    }
    @Test void failedQueueInsertRollsBackPublication(){UUID analysis=seedAnalysisJob();
        assertThatThrownBy(()->store.create(analysis,TARGET,"octo","repo","b".repeat(64),1,"{}",0,NOW));
        assertThat(jdbc.sql("SELECT count(*) FROM review_publications").query(Long.class).single()).isZero();
    }
    @Test void publicationKeyIsDatabaseIdempotencyGuardUnderConcurrency() throws Exception {int count=8;List<UUID> ids=new ArrayList<>();
        for(int i=0;i<count;i++)ids.add(seedAnalysisJob());CountDownLatch ready=new CountDownLatch(count),go=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(count)){var futures=ids.stream().map(id->executor.submit(()->{ready.countDown();go.await();
            return store.create(id,TARGET,"octo","repo","c".repeat(64),1,"{}",3,NOW);})).toList();ready.await();go.countDown();
            for(var future:futures)assertThat(future.get()).isIn(PublicationHandoffResult.CREATED,PublicationHandoffResult.ALREADY_EXISTS);}
        assertThat(jdbc.sql("SELECT count(*) FROM review_publications").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM publication_jobs").query(Long.class).single()).isEqualTo(1);
    }
    @Test void claimsUseLeasesAndStaleOwnershipCannotComplete(){UUID analysis=seedAnalysisJob();store.create(analysis,TARGET,"octo","repo","d".repeat(64),1,"{}",3,NOW);
        ClaimedPublicationJob first=store.claimDue(NOW,Duration.ofMinutes(1),1).getFirst();
        assertThat(store.claimDue(NOW.plusSeconds(30),Duration.ofMinutes(1),1)).isEmpty();
        ClaimedPublicationJob second=store.claimDue(NOW.plusSeconds(61),Duration.ofMinutes(1),1).getFirst();
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(store.completeJob(first.id(),first.claimToken(),NOW.plusSeconds(62))).isFalse();
        assertThat(store.completeJob(second.id(),second.claimToken(),NOW.plusSeconds(62))).isTrue();
    }

    @Test
    void staleOwnerCannotFailPublicationOwnedByNewClaim() {
        store.create(seedAnalysisJob(), TARGET, "octo", "repo", "f".repeat(64), 1, "{}", 3, NOW);
        ClaimedPublicationJob first = store.claimDue(NOW, Duration.ofMinutes(1), 1).getFirst();
        ClaimedPublicationJob second = store.claimDue(NOW.plusSeconds(61), Duration.ofMinutes(1), 1).getFirst();

        assertThat(store.failPublicationAndJob(first.id(), first.publicationId(), first.claimToken(),
                "STALE_FAILURE", NOW.plusSeconds(62))).isFalse();
        assertThat(store.find(first.publicationId()).orElseThrow().status()).isEqualTo(PublicationStatus.PENDING);

        assertThat(store.failPublicationAndJob(second.id(), second.publicationId(), second.claimToken(),
                "ACTIVE_FAILURE", NOW.plusSeconds(62))).isTrue();
        assertThat(store.find(second.publicationId()).orElseThrow().status()).isEqualTo(PublicationStatus.FAILED);
    }

    @Test
    void expiredFinalLeaseFailsPublicationAndJobTogether() {
        store.create(seedAnalysisJob(), TARGET, "octo", "repo", "e".repeat(64), 1, "{}", 1, NOW);
        ClaimedPublicationJob claim = store.claimDue(NOW, Duration.ofMinutes(1), 1).getFirst();

        assertThat(store.claimDue(NOW.plusSeconds(61), Duration.ofMinutes(1), 1)).isEmpty();
        assertThat(store.find(claim.publicationId()).orElseThrow().status()).isEqualTo(PublicationStatus.FAILED);
        assertThat(jdbc.sql("SELECT status FROM publication_jobs WHERE id=:id")
                .param("id", claim.id()).query(String.class).single()).isEqualTo("FAILED");
    }
    @Test
    void concurrentWorkersSplitPublicationPoolWithoutDuplicateClaims() throws Exception {
        for (int index = 0; index < 12; index++) {
            store.create(seedAnalysisJob(), TARGET, "octo", "repo",
                    "%064x".formatted(index + 1), 1, "{}", 3, NOW);
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                go.await();
                return store.claimDue(NOW, Duration.ofMinutes(1), 6);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                go.await();
                return store.claimDue(NOW, Duration.ofMinutes(1), 6);
            });
            ready.await();
            go.countDown();

            List<ClaimedPublicationJob> claims = new ArrayList<>();
            claims.addAll(first.get());
            claims.addAll(second.get());
            assertThat(claims).hasSize(12);
            assertThat(claims.stream().map(ClaimedPublicationJob::id).distinct()).hasSize(12);
            assertThat(claims.stream().map(ClaimedPublicationJob::claimToken).distinct()).hasSize(12);
        }
    }

    @Test
    void ambiguousPublicationCanBecomePublishedAndRetainsOnlySafeRemoteMetadata() {
        store.create(seedAnalysisJob(), TARGET, "octo", "repo", "e".repeat(64), 1, "{}", 3, NOW);
        UUID publicationId = jdbc.sql("SELECT id FROM review_publications").query(UUID.class).single();

        assertThat(store.markAmbiguous(publicationId, NOW.plusSeconds(1))).isTrue();
        assertThat(store.markPublished(publicationId, 42, NOW.plusSeconds(2), NOW.plusSeconds(3))).isTrue();

        ReviewPublication publication = store.find(publicationId).orElseThrow();
        assertThat(publication.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(publication.githubReviewId()).isEqualTo(42);
        assertThat(publication.publishedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(store.markAmbiguous(publicationId, NOW.plusSeconds(4))).isFalse();
    }

    private UUID seedAnalysisJob(){UUID id=UUID.randomUUID();OffsetDateTime now=NOW.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO review_jobs(id,status,attempts,max_attempts,next_attempt_at,created_at,updated_at)
                VALUES (:id,'READY',0,3,:now,:now,:now)
                """).param("id",id).param("now",now).update();return id;}
}
