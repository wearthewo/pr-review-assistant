package io.prreviewassistant.review.context;

import java.util.Objects;

public record ContextFile(String path, RepositoryRevisionSide revisionSide, String revisionSha,
        SourceLanguage language, ContextSelectionReason reason, String content,
        long originalByteSize, long retainedByteSize, boolean complete,
        int startLine, int endLine) {
    public ContextFile {
        Objects.requireNonNull(path); Objects.requireNonNull(revisionSide); Objects.requireNonNull(revisionSha);
        Objects.requireNonNull(language); Objects.requireNonNull(reason); Objects.requireNonNull(content);
        if (!revisionSha.matches("[0-9a-fA-F]{40,64}")) throw new IllegalArgumentException("invalid revision SHA");
        if (originalByteSize < 0 || retainedByteSize < 0 || retainedByteSize > originalByteSize) throw new IllegalArgumentException("invalid context sizes");
        if (startLine < 1 || endLine < startLine) throw new IllegalArgumentException("invalid fragment lines");
    }
    @Override public String toString() {
        return "ContextFile[path=<redacted>, revisionSide=" + revisionSide
                + ", revisionSha=" + revisionSha.substring(0, 8) + "..., language=" + language
                + ", reason=" + reason + ", content=<redacted>, originalByteSize=" + originalByteSize
                + ", retainedByteSize=" + retainedByteSize + ", complete=" + complete
                + ", startLine=" + startLine + ", endLine=" + endLine + "]";
    }
}
