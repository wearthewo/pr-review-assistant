package io.prreviewassistant.review.job;

import java.time.Clock;

import org.springframework.stereotype.Service;

@Service
public final class ReviewJobService {

    private final ReviewJobStore store;
    private final ReviewJobProperties properties;
    private final Clock clock;

    public ReviewJobService(ReviewJobStore store, ReviewJobProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    ReviewJob createPlaceholder() {
        return store.create(properties.maxAttempts(), clock.instant());
    }

    public ReviewJobCreationResult createForReviewTarget(ReviewTarget target) {
        return store.createForReviewTarget(target, properties.maxAttempts(), clock.instant());
    }
}
