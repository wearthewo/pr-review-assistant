package io.prreviewassistant.github.client;

public record GitHubChangedFileData(
        String path,
        String previousPath,
        String status,
        int additions,
        int deletions,
        int changes,
        String patch) {

    @Override
    public String toString() {
        return "GitHubChangedFileData[path=<redacted>, previousPath="
                + (previousPath == null ? "absent" : "<redacted>")
                + ", status=" + status
                + ", additions=" + additions
                + ", deletions=" + deletions
                + ", changes=" + changes
                + ", patch=<redacted>]";
    }
}
