package io.prreviewassistant.dashboard.github;

import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/github-connection")
public class GitHubConnectionController {
    private final GitHubConnectionService service;

    public GitHubConnectionController(GitHubConnectionService service) {
        this.service = service;
    }

    @PostMapping("/start")
    public StartResponse start(@AuthenticationPrincipal Jwt jwt) {
        return new StartResponse(service.start(identity(jwt)));
    }

    @PostMapping("/callback")
    public CompleteResponse callback(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CallbackRequest request) {
        return new CompleteResponse(service.complete(identity(jwt), request.code(), request.state()));
    }

    private static AuthenticatedUserIdentity identity(Jwt jwt) {
        return new AuthenticatedUserIdentity(jwt.getIssuer().toString(), jwt.getSubject());
    }

    public record StartResponse(String authorizationUrl) {
        @Override public String toString() { return "StartResponse[authorizationUrl=<redacted>]"; }
    }
    public record CompleteResponse(GitHubConnectionService.ConnectionResult result) { }
    public record CallbackRequest(@NotBlank @Size(max = 512) String code, @NotBlank @Size(max = 512) String state) {
        @Override public String toString() { return "CallbackRequest[code=<redacted>, state=<redacted>]"; }
    }
}
