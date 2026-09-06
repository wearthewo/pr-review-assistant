package io.prreviewassistant.review.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.prreviewassistant.github.auth.GitHubErrorType;
import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.github.client.GitHubApiClient;
import io.prreviewassistant.github.client.GitHubChangedFileData;
import io.prreviewassistant.github.client.GitHubChangedFilePage;
import io.prreviewassistant.github.client.GitHubPullRequestMetadata;
import io.prreviewassistant.github.client.GitHubRepositoryMetadata;
import io.prreviewassistant.review.job.ReviewTarget;
import org.springframework.stereotype.Service;

@Service
public final class PullRequestLoader {

    static final int FILES_PER_PAGE = 100;
    public static final int MAX_METADATA_RESPONSE_BYTES = 512 * 1024;

    private final GitHubApiClient gitHubApiClient;
    private final PullRequestFetchProperties properties;

    public PullRequestLoader(GitHubApiClient gitHubApiClient, PullRequestFetchProperties properties) {
        this.gitHubApiClient = gitHubApiClient;
        this.properties = properties;
    }

    public PullRequestLoadResult load(ReviewTarget target) {
        try {
            return loadBounded(target);
        } catch (GitHubException exception) {
            if (exception.type() == GitHubErrorType.RESPONSE_TOO_LARGE) {
                return PullRequestLoadResult.tooLarge();
            }
            throw exception;
        }
    }

    private PullRequestLoadResult loadBounded(ReviewTarget target) {
        GitHubRepositoryMetadata repository = gitHubApiClient.getRepository(
                target.installationId(), target.repositoryId(), MAX_METADATA_RESPONSE_BYTES);
        if (repository.id() != target.repositoryId()) {
            throw GitHubException.malformedResponse();
        }

        GitHubPullRequestMetadata pullRequest = gitHubApiClient.getPullRequest(
                target.installationId(), repository.owner(), repository.name(),
                target.pullRequestNumber(), MAX_METADATA_RESPONSE_BYTES);
        if (pullRequest.number() != target.pullRequestNumber()
                || pullRequest.repositoryId() != target.repositoryId()) {
            throw GitHubException.malformedResponse();
        }
        if (!pullRequest.headSha().equals(target.headSha())) {
            return PullRequestLoadResult.stale();
        }

        List<ChangedFile> files = new ArrayList<>();
        long totalPatchBytes = 0;
        for (int page = 1; page <= properties.maxPages(); page++) {
            GitHubChangedFilePage response = gitHubApiClient.listPullRequestFiles(
                    target.installationId(), repository.owner(), repository.name(),
                    target.pullRequestNumber(), page, FILES_PER_PAGE,
                    properties.maxPageResponseBytes());
            if ((long) files.size() + response.files().size() > properties.maxFiles()) {
                return PullRequestLoadResult.tooLarge();
            }
            for (GitHubChangedFileData file : response.files()) {
                int patchBytes = file.patch() == null
                        ? 0
                        : file.patch().getBytes(StandardCharsets.UTF_8).length;
                totalPatchBytes += patchBytes;
                if (totalPatchBytes > properties.maxTotalPatchBytes()) {
                    return PullRequestLoadResult.tooLarge();
                }
                files.add(normalize(file, patchBytes));
            }
            if (!response.hasNextPage()) {
                return PullRequestLoadResult.ready(new PullRequestSnapshot(
                        target.installationId(), target.repositoryId(), target.pullRequestNumber(),
                        pullRequest.headSha(), pullRequest.baseSha(), pullRequest.draft(), files));
            }
            if (page == properties.maxPages()) {
                return PullRequestLoadResult.tooLarge();
            }
        }
        return PullRequestLoadResult.tooLarge();
    }

    private ChangedFile normalize(GitHubChangedFileData file, int patchBytes) {
        PatchAvailability availability;
        String patch;
        if (file.patch() == null) {
            availability = PatchAvailability.UNAVAILABLE;
            patch = null;
        } else if (patchBytes > properties.maxPatchBytesPerFile()) {
            availability = PatchAvailability.EXCEEDED_LIMIT;
            patch = null;
        } else {
            availability = PatchAvailability.AVAILABLE;
            patch = file.patch();
        }
        try {
            return new ChangedFile(
                    file.path(), file.previousPath(), ChangedFileStatus.fromGitHub(file.status()),
                    file.additions(), file.deletions(), file.changes(), availability, patch);
        } catch (IllegalArgumentException exception) {
            throw GitHubException.malformedResponse();
        }
    }
}
