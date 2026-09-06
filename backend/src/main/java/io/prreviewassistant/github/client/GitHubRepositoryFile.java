package io.prreviewassistant.github.client;

import java.util.Arrays;

public record GitHubRepositoryFile(byte[] content, long declaredSize) {
    public GitHubRepositoryFile {
        content = Arrays.copyOf(content, content.length);
        if (declaredSize < 0) throw new IllegalArgumentException("declaredSize must not be negative");
    }

    @Override public byte[] content() { return Arrays.copyOf(content, content.length); }
    @Override public String toString() { return "GitHubRepositoryFile[content=<redacted>, declaredSize=" + declaredSize + "]"; }
}
