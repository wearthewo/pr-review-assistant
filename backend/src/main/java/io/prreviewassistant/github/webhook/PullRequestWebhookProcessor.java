package io.prreviewassistant.github.webhook;

import java.util.Set;

import io.prreviewassistant.review.job.ReviewJobCreationResult;
import io.prreviewassistant.review.job.ReviewJobService;
import io.prreviewassistant.review.job.ReviewTarget;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public final class PullRequestWebhookProcessor {

    static final int MAX_ACTION_LENGTH = 64;
    private static final Set<String> REVIEWABLE_ACTIONS = Set.of("opened", "reopened", "synchronize");

    private final ReviewJobService reviewJobService;

    public PullRequestWebhookProcessor(ReviewJobService reviewJobService) {
        this.reviewJobService = reviewJobService;
    }

    public GitHubWebhookProcessingResult process(JsonNode payload) {
        String action = boundedText(payload == null ? null : payload.get("action"), MAX_ACTION_LENGTH);
        if (action == null) {
            return GitHubWebhookProcessingResult.MALFORMED;
        }
        if (!REVIEWABLE_ACTIONS.contains(action)) {
            return GitHubWebhookProcessingResult.IGNORED;
        }

        ReviewTarget target;
        try {
            target = new ReviewTarget(
                    positiveLong(payload, "installation", "id"),
                    positiveLong(payload, "repository", "id"),
                    positiveInt(payload, "number"),
                    requiredBoundedText(required(payload, "pull_request", "head", "sha"),
                            ReviewTarget.MAX_HEAD_SHA_LENGTH));
        } catch (IllegalArgumentException exception) {
            return GitHubWebhookProcessingResult.MALFORMED;
        }

        ReviewJobCreationResult result = reviewJobService.createForReviewTarget(target);
        return result == ReviewJobCreationResult.CREATED
                ? GitHubWebhookProcessingResult.JOB_CREATED
                : GitHubWebhookProcessingResult.JOB_ALREADY_EXISTS;
    }

    private long positiveLong(JsonNode payload, String... path) {
        JsonNode value = required(payload, path);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("required positive integer is invalid");
        }
        return value.longValue();
    }

    private int positiveInt(JsonNode payload, String... path) {
        JsonNode value = required(payload, path);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw new IllegalArgumentException("required positive integer is invalid");
        }
        return value.intValue();
    }

    private JsonNode required(JsonNode payload, String... path) {
        JsonNode current = payload;
        for (String segment : path) {
            if (current == null || !current.isObject()) {
                throw new IllegalArgumentException("required webhook field is missing");
            }
            current = current.get(segment);
        }
        if (current == null || current.isNull() || current.isMissingNode()) {
            throw new IllegalArgumentException("required webhook field is missing");
        }
        return current;
    }

    private String boundedText(JsonNode value, int maxLength) {
        if (value == null || !value.isString()) {
            return null;
        }
        String text = value.stringValue();
        if (text.isBlank() || text.length() > maxLength) {
            return null;
        }
        return text;
    }

    private String requiredBoundedText(JsonNode value, int maxLength) {
        String text = boundedText(value, maxLength);
        if (text == null) {
            throw new IllegalArgumentException("required bounded text is invalid");
        }
        return text;
    }
}
