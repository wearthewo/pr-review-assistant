package io.prreviewassistant.dashboard.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface DashboardReviewStore {
    List<ReviewRecord> findByTenant(
            UUID tenantId, DashboardReviewCursorCodec.Cursor cursor, int limit);

    record ReviewRecord(
            UUID jobId,
            long repositoryId,
            int pullRequestNumber,
            String headSha,
            String jobStatus,
            String publicationStatus,
            Integer findingCount,
            Instant createdAt,
            Instant updatedAt) {
        public ReviewRecord {
            if (jobId == null || repositoryId <= 0 || pullRequestNumber <= 0
                    || headSha == null || !headSha.matches("[0-9a-f]{40,64}")
                    || jobStatus == null || createdAt == null || updatedAt == null) {
                throw new IllegalArgumentException("review history record is invalid");
            }
            if ((publicationStatus == null) != (findingCount == null)
                    || (findingCount != null && (findingCount < 1 || findingCount > 5))) {
                throw new IllegalArgumentException("review publication history is invalid");
            }
        }
    }
}
