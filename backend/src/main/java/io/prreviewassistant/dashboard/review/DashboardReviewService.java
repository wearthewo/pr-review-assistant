package io.prreviewassistant.dashboard.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import io.prreviewassistant.identity.AuthorizedTenantContext;
import io.prreviewassistant.identity.TenantAuthorizationService;
import org.springframework.stereotype.Service;

@Service
public final class DashboardReviewService {
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_QUERY_SIZE = MAX_PAGE_SIZE + 1;

    private final TenantAuthorizationService authorization;
    private final DashboardReviewStore store;
    private final DashboardReviewCursorCodec cursors;

    DashboardReviewService(
            TenantAuthorizationService authorization,
            DashboardReviewStore store,
            DashboardReviewCursorCodec cursors) {
        this.authorization = authorization;
        this.store = store;
        this.cursors = cursors;
    }

    public ReviewPage list(
            AuthenticatedUserIdentity identity, UUID requestedTenantId, String cursor, int pageSize) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new DashboardReviewRequestException();
        }
        AuthorizedTenantContext context = authorization.authorize(identity, requestedTenantId);
        DashboardReviewCursorCodec.Cursor boundary = cursor == null
                ? null : cursors.decode(cursor, context.tenantId());
        List<DashboardReviewStore.ReviewRecord> found =
                store.findByTenant(context.tenantId(), boundary, pageSize + 1);
        boolean hasMore = found.size() > pageSize;
        List<DashboardReviewStore.ReviewRecord> visible = found.stream().limit(pageSize).toList();
        List<ReviewSummary> reviews = visible.stream().map(this::summary).toList();
        String nextCursor = hasMore
                ? cursors.encode(context.tenantId(), visible.getLast().createdAt(), visible.getLast().jobId())
                : null;
        return new ReviewPage(reviews, hasMore, nextCursor);
    }

    private ReviewSummary summary(DashboardReviewStore.ReviewRecord record) {
        return new ReviewSummary(
                record.repositoryId(), record.pullRequestNumber(), record.headSha(), state(record),
                record.findingCount(), record.createdAt(), record.updatedAt());
    }

    private DashboardReviewState state(DashboardReviewStore.ReviewRecord record) {
        if (record.publicationStatus() != null) {
            return switch (record.publicationStatus()) {
                case "PENDING" -> DashboardReviewState.PUBLICATION_PENDING;
                case "AMBIGUOUS" -> DashboardReviewState.PUBLICATION_UNCERTAIN;
                case "PUBLISHED" -> DashboardReviewState.PUBLISHED;
                case "FAILED" -> DashboardReviewState.PUBLICATION_FAILED;
                default -> throw new IllegalStateException("Unsupported persisted publication state");
            };
        }
        return switch (record.jobStatus()) {
            case "READY" -> DashboardReviewState.QUEUED;
            case "PROCESSING" -> DashboardReviewState.ANALYZING;
            case "COMPLETED" -> DashboardReviewState.COMPLETED_WITHOUT_PUBLICATION;
            case "FAILED" -> DashboardReviewState.ANALYSIS_FAILED;
            default -> throw new IllegalStateException("Unsupported persisted review state");
        };
    }

    public record ReviewPage(List<ReviewSummary> reviews, boolean hasMore, String nextCursor) {
        public ReviewPage {
            reviews = List.copyOf(reviews);
            if (reviews.size() > MAX_PAGE_SIZE || hasMore != (nextCursor != null)) {
                throw new IllegalArgumentException("review history page is invalid");
            }
        }
    }

    public record ReviewSummary(
            long repositoryId,
            int pullRequestNumber,
            String headSha,
            DashboardReviewState state,
            Integer publishableFindingCount,
            Instant createdAt,
            Instant updatedAt) {
        public ReviewSummary {
            boolean publicationState = state == DashboardReviewState.PUBLICATION_PENDING
                    || state == DashboardReviewState.PUBLICATION_UNCERTAIN
                    || state == DashboardReviewState.PUBLISHED
                    || state == DashboardReviewState.PUBLICATION_FAILED;
            if (repositoryId <= 0 || pullRequestNumber <= 0
                    || headSha == null || !headSha.matches("[0-9a-f]{40,64}")
                    || state == null || createdAt == null || updatedAt == null
                    || publicationState != (publishableFindingCount != null)
                    || (publishableFindingCount != null
                        && (publishableFindingCount < 1 || publishableFindingCount > 5))) {
                throw new IllegalArgumentException("review history summary is invalid");
            }
        }
    }
}
