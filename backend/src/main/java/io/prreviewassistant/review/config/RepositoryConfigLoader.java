package io.prreviewassistant.review.config;

import io.prreviewassistant.github.auth.GitHubErrorType;
import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.github.client.GitHubApiClient;
import io.prreviewassistant.github.client.GitHubRepositoryFile;
import io.prreviewassistant.review.retrieval.PullRequestSnapshot;

public final class RepositoryConfigLoader {
    private static final int RESPONSE_ENVELOPE_BYTES = 8 * 1024;

    private final GitHubApiClient client;
    private final RepositoryConfigProperties properties;
    private final RepositoryConfigParser parser;

    public RepositoryConfigLoader(GitHubApiClient client, RepositoryConfigProperties properties) {
        this.client = client;
        this.properties = properties;
        this.parser = new RepositoryConfigParser(properties.maxBytes());
    }

    public RepositoryConfigLoadResult load(PullRequestSnapshot snapshot) {
        try {
            GitHubRepositoryFile file = client.getRepositoryFile(
                    snapshot.installationId(), snapshot.repositoryOwner(), snapshot.repositoryName(),
                    EffectiveRepositoryReviewConfig.FILE_NAME, snapshot.headSha(),
                    RepositoryConfigProperties.HARD_MAX_BYTES * 2 + RESPONSE_ENVELOPE_BYTES);
            if (file.declaredSize() > properties.maxBytes() || file.content().length > properties.maxBytes()) {
                return RepositoryConfigLoadResult.defaults(RepositoryConfigStatus.OVERSIZED);
            }
            return parser.parse(file.content());
        } catch (GitHubException exception) {
            if (exception.type() == GitHubErrorType.RESOURCE_NOT_FOUND) {
                return RepositoryConfigLoadResult.defaults(RepositoryConfigStatus.DEFAULT_NOT_FOUND);
            }
            throw exception;
        }
    }
}
