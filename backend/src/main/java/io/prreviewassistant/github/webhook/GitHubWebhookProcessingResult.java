package io.prreviewassistant.github.webhook;

public enum GitHubWebhookProcessingResult {
    IGNORED,
    MALFORMED,
    JOB_CREATED,
    JOB_ALREADY_EXISTS
}
