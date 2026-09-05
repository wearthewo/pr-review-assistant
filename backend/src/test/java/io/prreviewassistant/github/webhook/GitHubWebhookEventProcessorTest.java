package io.prreviewassistant.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class GitHubWebhookEventProcessorTest {

    @Test
    void ignoresEveryNonPullRequestEventWithoutParsingItsPayload() {
        PullRequestWebhookProcessor pullRequests = mock(PullRequestWebhookProcessor.class);
        GitHubWebhookEventProcessor processor = new GitHubWebhookEventProcessor(pullRequests);

        assertThat(processor.process("issues", JsonMapper.builder().build().nullNode()))
                .isEqualTo(GitHubWebhookProcessingResult.IGNORED);
        verifyNoInteractions(pullRequests);
    }
}
