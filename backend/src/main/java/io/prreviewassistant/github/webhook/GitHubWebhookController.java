package io.prreviewassistant.github.webhook;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks/github")
public final class GitHubWebhookController {

    static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    static final String DELIVERY_HEADER = "X-GitHub-Delivery";
    static final String EVENT_HEADER = "X-GitHub-Event";

    private final GitHubWebhookProperties properties;
    private final GitHubWebhookService service;

    public GitHubWebhookController(GitHubWebhookProperties properties, GitHubWebhookService service) {
        this.properties = properties;
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(HttpServletRequest request) {
        byte[] rawBody = readBoundedBody(request);
        service.ingest(
                rawBody,
                singleHeader(request, SIGNATURE_HEADER),
                singleHeader(request, DELIVERY_HEADER),
                singleHeader(request, EVENT_HEADER));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private byte[] readBoundedBody(HttpServletRequest request) {
        int limit = properties.maxBodyBytes();
        if (request.getContentLengthLong() > limit) {
            throw GitHubWebhookException.payloadTooLarge();
        }
        try {
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) {
                throw GitHubWebhookException.payloadTooLarge();
            }
            return body;
        } catch (IOException exception) {
            throw GitHubWebhookException.invalidRequest();
        }
    }

    private String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) {
            return null;
        }
        return values.getFirst();
    }
}
