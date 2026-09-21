package io.prreviewassistant.review.analysis;

import io.prreviewassistant.observability.ApplicationMetrics;
import io.prreviewassistant.tenant.TenantContext;
import io.prreviewassistant.usage.UsageMeasurement;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class ReviewAnalysisCheckpointService {
    private final ReviewAnalysisCheckpointStore store;
    private final Clock clock;
    private final ApplicationMetrics metrics;

    public ReviewAnalysisCheckpointService(
            ReviewAnalysisCheckpointStore store, Clock clock, ApplicationMetrics metrics) {
        this.store = store;
        this.clock = clock;
        this.metrics = metrics;
    }

    public Optional<ReviewCandidateAnalysis> find(TenantContext tenantContext, UUID reviewJobId) {
        try {
            Optional<ReviewCandidateAnalysis> checkpoint = store.find(tenantContext, reviewJobId);
            if (checkpoint.isPresent()) metrics.analysisCheckpoint("reused");
            return checkpoint;
        } catch (ReviewAnalysisCheckpointException exception) {
            recordFailure(exception);
            throw exception;
        }
    }

    public ReviewAnalysisCheckpointResult createAndConsume(
            TenantContext tenantContext,
            UUID reviewJobId,
            ReviewCandidateAnalysis analysis,
            ReviewAnalysisMetadata metadata) {
        try {
            ReviewAnalysisCheckpointResult result = store.createAndConsume(
                    tenantContext, reviewJobId, analysis, UsageMeasurement.from(metadata), clock.instant());
            metrics.analysisCheckpoint(result == ReviewAnalysisCheckpointResult.CREATED ? "created" : "reused");
            return result;
        } catch (ReviewAnalysisCheckpointException exception) {
            recordFailure(exception);
            throw exception;
        }
    }

    private void recordFailure(ReviewAnalysisCheckpointException exception) {
        metrics.analysisCheckpoint(exception.error() == ReviewAnalysisCheckpointError.INCONSISTENT_STATE
                ? "inconsistent" : "failure");
    }
}
