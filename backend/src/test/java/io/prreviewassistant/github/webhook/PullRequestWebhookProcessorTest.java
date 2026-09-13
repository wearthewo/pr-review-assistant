package io.prreviewassistant.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.prreviewassistant.review.job.ReviewJobCreationResult;
import io.prreviewassistant.review.job.ReviewJobService;
import io.prreviewassistant.review.job.ReviewTarget;
import io.prreviewassistant.tenant.TenantContext;
import io.prreviewassistant.tenant.TenantOwnershipError;
import io.prreviewassistant.tenant.TenantOwnershipException;
import io.prreviewassistant.tenant.TenantOwnershipService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PullRequestWebhookProcessorTest {

    private static final String SHA = "abcdef0123456789abcdef0123456789abcdef01";
    private static final TenantContext TENANT_CONTEXT = new TenantContext(
            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 101, 202);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ReviewJobService jobService = mock(ReviewJobService.class);
    private final TenantOwnershipService ownershipService = mock(TenantOwnershipService.class);
    private final PullRequestWebhookProcessor processor = new PullRequestWebhookProcessor(jobService, ownershipService);

    @ParameterizedTest
    @ValueSource(strings = {"opened", "reopened", "synchronize"})
    void reviewableActionsCreateTheExactTarget(String action) throws Exception {
        ReviewTarget expected = new ReviewTarget(101, 202, 42, SHA);
        when(ownershipService.provision(101, 202)).thenReturn(TENANT_CONTEXT);
        when(jobService.createForReviewTarget(TENANT_CONTEXT, expected)).thenReturn(ReviewJobCreationResult.CREATED);

        assertThat(processor.process(validPayload(action)))
                .isEqualTo(GitHubWebhookProcessingResult.JOB_CREATED);
        verify(jobService).createForReviewTarget(TENANT_CONTEXT, expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"closed", "labeled", "unknown_future_action"})
    void syntacticallyValidUnsupportedActionsAreIgnored(String action) throws Exception {
        assertThat(processor.process(validPayload(action)))
                .isEqualTo(GitHubWebhookProcessingResult.IGNORED);
        verifyNoInteractions(jobService);
    }

    @Test
    void reportsNormalReviewTargetDuplicationWithoutError() throws Exception {
        when(ownershipService.provision(101, 202)).thenReturn(TENANT_CONTEXT);
        when(jobService.createForReviewTarget(any(), any())).thenReturn(ReviewJobCreationResult.ALREADY_EXISTS);

        assertThat(processor.process(validPayload("opened")))
                .isEqualTo(GitHubWebhookProcessingResult.JOB_ALREADY_EXISTS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"action\":\"opened\",\"repository\":{\"id\":202},\"number\":42,\"pull_request\":{\"head\":{\"sha\":\"abcdef0123456789abcdef0123456789abcdef01\"}}}",
            "{\"action\":\"opened\",\"installation\":{\"id\":0},\"repository\":{\"id\":202},\"number\":42,\"pull_request\":{\"head\":{\"sha\":\"abcdef0123456789abcdef0123456789abcdef01\"}}}",
            "{\"action\":\"opened\",\"installation\":{\"id\":101},\"number\":42,\"pull_request\":{\"head\":{\"sha\":\"abcdef0123456789abcdef0123456789abcdef01\"}}}",
            "{\"action\":\"opened\",\"installation\":{\"id\":101},\"repository\":{\"id\":-1},\"number\":42,\"pull_request\":{\"head\":{\"sha\":\"abcdef0123456789abcdef0123456789abcdef01\"}}}",
            "{\"action\":\"opened\",\"installation\":{\"id\":101},\"repository\":{\"id\":202},\"pull_request\":{\"head\":{\"sha\":\"abcdef0123456789abcdef0123456789abcdef01\"}}}",
            "{\"action\":\"opened\",\"installation\":{\"id\":101},\"repository\":{\"id\":202},\"number\":0,\"pull_request\":{\"head\":{\"sha\":\"abcdef0123456789abcdef0123456789abcdef01\"}}}",
            "{\"action\":\"opened\",\"installation\":{\"id\":101},\"repository\":{\"id\":202},\"number\":42,\"pull_request\":{\"head\":{}}}",
            "{\"action\":\"opened\",\"installation\":{\"id\":101},\"repository\":{\"id\":202},\"number\":42,\"pull_request\":{\"head\":{\"sha\":\" \"}}}",
            "{\"action\":\"opened\",\"installation\":{\"id\":101},\"repository\":{\"id\":202},\"number\":42,\"pull_request\":{\"head\":{\"sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}}}",
            "{\"action\":\"opened\",\"installation\":{\"id\":\"101\"},\"repository\":{\"id\":202},\"number\":42,\"pull_request\":{\"head\":{\"sha\":\"abcdef0123456789abcdef0123456789abcdef01\"}}}"
    })
    void malformedRelevantPayloadsAreRetainedWithoutCreatingAJob(String json) throws Exception {
        assertThat(processor.process(mapper.readTree(json)))
                .isEqualTo(GitHubWebhookProcessingResult.MALFORMED);
        verify(jobService, never()).createForReviewTarget(any(), any());
        verifyNoInteractions(ownershipService);
    }

    @Test
    void invalidOrOversizedActionsAreMalformed() throws Exception {
        assertThat(processor.process(mapper.readTree("{\"action\":42}")))
                .isEqualTo(GitHubWebhookProcessingResult.MALFORMED);
        assertThat(processor.process(mapper.readTree("{\"action\":\"" + "a".repeat(65) + "\"}")))
                .isEqualTo(GitHubWebhookProcessingResult.MALFORMED);
    }

    @Test
    void payloadTenantAndMutableRepositoryNamesCannotOverrideServerResolvedOwnership() throws Exception {
        ReviewTarget expected = new ReviewTarget(101, 202, 42, SHA);
        when(ownershipService.provision(101, 202)).thenReturn(TENANT_CONTEXT);
        when(jobService.createForReviewTarget(TENANT_CONTEXT, expected)).thenReturn(ReviewJobCreationResult.CREATED);
        JsonNode payload = mapper.readTree(validPayload("opened").toString()
                .replace("renamable/name", "attacker-controlled/foreign-name")
                .replace("\"ignored_large_schema\"",
                        "\"tenant_id\":\"attacker-controlled\",\"ignored_large_schema\""));

        assertThat(processor.process(payload)).isEqualTo(GitHubWebhookProcessingResult.JOB_CREATED);
        verify(jobService).createForReviewTarget(TENANT_CONTEXT, expected);
    }

    @Test
    void conflictingRepositoryOwnershipIsRejectedBeforeJobCreation() throws Exception {
        when(ownershipService.provision(101, 202)).thenThrow(
                new TenantOwnershipException(TenantOwnershipError.TENANT_REPOSITORY_OWNERSHIP_MISMATCH));

        assertThat(processor.process(validPayload("opened")))
                .isEqualTo(GitHubWebhookProcessingResult.OWNERSHIP_REJECTED);
        verify(jobService, never()).createForReviewTarget(any(), any());
    }

    private JsonNode validPayload(String action) throws Exception {
        return mapper.readTree("""
                {
                  "action": "%s",
                  "installation": {"id": 101},
                  "repository": {"id": 202, "full_name": "renamable/name"},
                  "number": 42,
                  "pull_request": {"head": {"sha": "%s"}},
                  "ignored_large_schema": {"anything": true}
                }
                """.formatted(action, SHA));
    }
}
