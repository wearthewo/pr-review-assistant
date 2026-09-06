package io.prreviewassistant.github.client;

import java.util.List;

public record GitHubChangedFilePage(List<GitHubChangedFileData> files, boolean hasNextPage) {

    public GitHubChangedFilePage {
        files = List.copyOf(files);
    }
}
