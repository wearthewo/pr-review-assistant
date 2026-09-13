package io.prreviewassistant.github.webhook;

public enum GitHubWebhookProcessingResult {
    IGNORED,
    MALFORMED,
    OWNERSHIP_REJECTED,
    JOB_CREATED,
    JOB_ALREADY_EXISTS
}
