package io.prreviewassistant.review.analysis;

import io.prreviewassistant.tenant.TenantContext;
import io.prreviewassistant.usage.UsageMeasurement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReviewAnalysisCheckpointStore {
    Optional<ReviewCandidateAnalysis> find(TenantContext tenantContext, UUID reviewJobId);

    ReviewAnalysisCheckpointResult createAndConsume(
            TenantContext tenantContext,
            UUID reviewJobId,
            ReviewCandidateAnalysis analysis,
            UsageMeasurement measurement,
            Instant now);
}
