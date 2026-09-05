package io.prreviewassistant.github.webhook;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;

class GitHubWebhookControllerTest {

    private static final String SECRET = "test-webhook-secret-with-entropy";
    private static final String PAYLOAD = "{\"zen\":\"Keep it logically awesome.\"}";
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GitHubWebhookProperties properties = new GitHubWebhookProperties(SECRET, DataSize.ofBytes(64));
        GitHubWebhookSignatureVerifier verifier = new GitHubWebhookSignatureVerifier(properties);
        GitHubWebhookService service = new GitHubWebhookService(
                verifier,
                JsonMapper.builder().build(),
                acceptance(delivery -> GitHubWebhookStore.StoreResult.ACCEPTED),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        mockMvc = MockMvcBuilders.standaloneSetup(new GitHubWebhookController(properties, service))
                .setControllerAdvice(new GitHubWebhookControllerAdvice())
                .build();
    }

    @Test
    void acceptsValidSignedJsonWithAnEmptyResponse() throws Exception {
        byte[] body = PAYLOAD.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(validRequest(body))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void returnsTheSameAcceptedResponseForAnIdempotentDuplicate() throws Exception {
        GitHubWebhookProperties properties = new GitHubWebhookProperties(SECRET, DataSize.ofMegabytes(1));
        GitHubWebhookService duplicateService = new GitHubWebhookService(
                new GitHubWebhookSignatureVerifier(properties),
                JsonMapper.builder().build(),
                acceptance(delivery -> GitHubWebhookStore.StoreResult.DUPLICATE),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        MockMvc duplicateMvc = MockMvcBuilders
                .standaloneSetup(new GitHubWebhookController(properties, duplicateService))
                .setControllerAdvice(new GitHubWebhookControllerAdvice())
                .build();
        byte[] body = PAYLOAD.getBytes(StandardCharsets.UTF_8);

        duplicateMvc.perform(validRequest(body))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void rejectsMissingDeliveryOrEventHeaders() throws Exception {
        byte[] body = PAYLOAD.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(GitHubWebhookController.SIGNATURE_HEADER, WebhookTestSupport.sign(SECRET, body))
                        .header(GitHubWebhookController.EVENT_HEADER, "ping")
                        .content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(GitHubWebhookController.SIGNATURE_HEADER, WebhookTestSupport.sign(SECRET, body))
                        .header(GitHubWebhookController.DELIVERY_HEADER, "delivery-1")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingOrInvalidSignatureWithoutEchoingSecretsOrPayload() throws Exception {
        byte[] body = PAYLOAD.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(GitHubWebhookController.DELIVERY_HEADER, "delivery-1")
                        .header(GitHubWebhookController.EVENT_HEADER, "ping")
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(GitHubWebhookController.SIGNATURE_HEADER, "sha256=" + "0".repeat(64))
                        .header(GitHubWebhookController.DELIVERY_HEADER, "delivery-1")
                        .header(GitHubWebhookController.EVENT_HEADER, "ping")
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void rejectsUnsupportedContentType() throws Exception {
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(PAYLOAD))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().string(""));
    }

    @Test
    void rejectsMalformedJsonWithoutEchoingIt() throws Exception {
        byte[] body = "{malicious-payload}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(validRequest(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));
    }

    @Test
    void rejectsOversizedBodyWithoutEchoingIt() throws Exception {
        byte[] body = ("{\"value\":\"" + "x".repeat(64) + "\"}").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(validRequest(body))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().string(""));
    }

    @Test
    void persistenceFailureIsNotAcknowledgedAsAccepted() throws Exception {
        GitHubWebhookProperties properties = new GitHubWebhookProperties(SECRET, DataSize.ofMegabytes(1));
        GitHubWebhookService failingService = new GitHubWebhookService(
                new GitHubWebhookSignatureVerifier(properties),
                JsonMapper.builder().build(),
                acceptance(delivery -> {
                    throw new IllegalStateException("database unavailable");
                }),
                Clock.systemUTC());
        MockMvc failingMvc = MockMvcBuilders
                .standaloneSetup(new GitHubWebhookController(properties, failingService))
                .setControllerAdvice(new GitHubWebhookControllerAdvice())
                .build();
        byte[] body = PAYLOAD.getBytes(StandardCharsets.UTF_8);

        failingMvc.perform(validRequest(body))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(""));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest(byte[] body) {
        return post("/api/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .header(GitHubWebhookController.SIGNATURE_HEADER, WebhookTestSupport.sign(SECRET, body))
                .header(GitHubWebhookController.DELIVERY_HEADER, "delivery-1")
                .header(GitHubWebhookController.EVENT_HEADER, "ping")
                .content(body);
    }

    private GitHubWebhookAcceptanceService acceptance(GitHubWebhookStore store) {
        return new GitHubWebhookAcceptanceService(store, mock(GitHubWebhookEventProcessor.class));
    }
}
