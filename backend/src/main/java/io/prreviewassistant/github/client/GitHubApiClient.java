package io.prreviewassistant.github.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.github.auth.InstallationAccessToken;
import io.prreviewassistant.github.auth.InstallationTokenProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

public final class GitHubApiClient {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;
    private final InstallationTokenProvider tokenProvider;

    public GitHubApiClient(RestClient restClient, InstallationTokenProvider tokenProvider) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
    }

    public AccessibleRepositories listAccessibleRepositories(long installationId) {
        InstallationAccessToken token = tokenProvider.tokenFor(installationId);
        String responseBody;
        responseBody = GitHubHttpResponses.read(restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/installation/repositories")
                        .queryParam("per_page", 1)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value()), false);
        return parse(responseBody);
    }

    public GitHubRepositoryMetadata getRepository(
            long installationId,
            long repositoryId,
            int maxResponseBytes) {
        if (repositoryId <= 0) {
            throw GitHubException.malformedResponse();
        }
        InstallationAccessToken token = tokenProvider.tokenFor(installationId);
        GitHubHttpResponse response = GitHubHttpResponses.read(restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment("repositories", Long.toString(repositoryId))
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value()), false, maxResponseBytes);
        return parseRepository(response.body());
    }

    public GitHubPullRequestMetadata getPullRequest(
            long installationId,
            String owner,
            String repository,
            int pullRequestNumber,
            int maxResponseBytes) {
        validateRepositoryAddress(owner, repository);
        if (pullRequestNumber <= 0) {
            throw GitHubException.malformedResponse();
        }
        InstallationAccessToken token = tokenProvider.tokenFor(installationId);
        GitHubHttpResponse response = GitHubHttpResponses.read(restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment("repos", owner, repository, "pulls", Integer.toString(pullRequestNumber))
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value()), false, maxResponseBytes);
        return parsePullRequest(response.body());
    }

    public GitHubChangedFilePage listPullRequestFiles(
            long installationId,
            String owner,
            String repository,
            int pullRequestNumber,
            int page,
            int perPage,
            int maxResponseBytes) {
        validateRepositoryAddress(owner, repository);
        if (pullRequestNumber <= 0 || page <= 0 || perPage <= 0 || perPage > 100) {
            throw GitHubException.malformedResponse();
        }
        InstallationAccessToken token = tokenProvider.tokenFor(installationId);
        GitHubHttpResponse response = GitHubHttpResponses.read(restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment("repos", owner, repository, "pulls",
                                Integer.toString(pullRequestNumber), "files")
                        .queryParam("per_page", perPage)
                        .queryParam("page", page)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value()), false, maxResponseBytes);
        return new GitHubChangedFilePage(parseFiles(response.body()), response.hasNextPage());
    }

    private AccessibleRepositories parse(String responseBody) {
        try {
            JsonNode response = OBJECT_MAPPER.readTree(responseBody);
            JsonNode totalCount = response.path("total_count");
            if (!totalCount.isIntegralNumber() || !totalCount.canConvertToLong() || totalCount.longValue() < 0) {
                throw GitHubException.malformedResponse();
            }
            return new AccessibleRepositories(totalCount.longValue());
        } catch (RuntimeException exception) {
            if (exception instanceof GitHubException githubException) {
                throw githubException;
            }
            throw GitHubException.malformedResponse();
        }
    }

    private GitHubRepositoryMetadata parseRepository(String responseBody) {
        return parseSafely(responseBody, response -> new GitHubRepositoryMetadata(
                requiredPositiveLong(response.path("id")),
                requiredBoundedString(response.path("owner").path("login"), 100),
                requiredBoundedString(response.path("name"), 100)));
    }

    private GitHubPullRequestMetadata parsePullRequest(String responseBody) {
        return parseSafely(responseBody, response -> {
            JsonNode draft = response.path("draft");
            if (!draft.isBoolean()) {
                throw GitHubException.malformedResponse();
            }
            return new GitHubPullRequestMetadata(
                    requiredPositiveInt(response.path("number")),
                    requiredPositiveLong(response.path("base").path("repo").path("id")),
                    requiredObjectId(response.path("head").path("sha")),
                    requiredObjectId(response.path("base").path("sha")),
                    draft.booleanValue());
        });
    }

    private java.util.List<GitHubChangedFileData> parseFiles(String responseBody) {
        return parseSafely(responseBody, response -> {
            if (!response.isArray()) {
                throw GitHubException.malformedResponse();
            }
            java.util.List<GitHubChangedFileData> files = new java.util.ArrayList<>();
            for (JsonNode file : response) {
                if (!file.isObject()) {
                    throw GitHubException.malformedResponse();
                }
                files.add(new GitHubChangedFileData(
                        requiredString(file.path("filename")),
                        optionalString(file.path("previous_filename")),
                        requiredString(file.path("status")),
                        requiredNonNegativeInt(file.path("additions")),
                        requiredNonNegativeInt(file.path("deletions")),
                        requiredNonNegativeInt(file.path("changes")),
                        optionalString(file.path("patch"))));
            }
            return java.util.List.copyOf(files);
        });
    }

    private <T> T parseSafely(String responseBody, java.util.function.Function<JsonNode, T> parser) {
        try {
            JsonNode response = OBJECT_MAPPER.readTree(responseBody);
            if (response == null) {
                throw GitHubException.malformedResponse();
            }
            return parser.apply(response);
        } catch (RuntimeException exception) {
            if (exception instanceof GitHubException githubException) {
                throw githubException;
            }
            throw GitHubException.malformedResponse();
        }
    }

    private long requiredPositiveLong(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw GitHubException.malformedResponse();
        }
        return value.longValue();
    }

    private int requiredPositiveInt(JsonNode value) {
        int result = requiredNonNegativeInt(value);
        if (result == 0) {
            throw GitHubException.malformedResponse();
        }
        return result;
    }

    private int requiredNonNegativeInt(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw GitHubException.malformedResponse();
        }
        return value.intValue();
    }

    private String requiredObjectId(JsonNode value) {
        String objectId = requiredBoundedString(value, 64);
        if (objectId.length() < 40 || !objectId.matches("[0-9a-fA-F]+")) {
            throw GitHubException.malformedResponse();
        }
        return objectId.toLowerCase(java.util.Locale.ROOT);
    }

    private String requiredBoundedString(JsonNode value, int maxLength) {
        String text = requiredString(value);
        if (text.length() > maxLength) {
            throw GitHubException.malformedResponse();
        }
        return text;
    }

    private String requiredString(JsonNode value) {
        if (!value.isString() || value.stringValue().isBlank()) {
            throw GitHubException.malformedResponse();
        }
        return value.stringValue();
    }

    private String optionalString(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw GitHubException.malformedResponse();
        }
        return value.stringValue();
    }

    private void validateRepositoryAddress(String owner, String repository) {
        if (owner == null || owner.isBlank() || owner.length() > 100
                || repository == null || repository.isBlank() || repository.length() > 100
                || containsPathSeparator(owner) || containsPathSeparator(repository)) {
            throw GitHubException.malformedResponse();
        }
    }

    private boolean containsPathSeparator(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
    }
}
