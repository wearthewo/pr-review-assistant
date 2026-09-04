package io.prreviewassistant.review.job;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
class ReviewJobIdentifiers {

    UUID newJobId() {
        return UUID.randomUUID();
    }

    UUID newClaimToken() {
        return UUID.randomUUID();
    }
}
