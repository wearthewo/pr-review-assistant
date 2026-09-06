package io.prreviewassistant.review.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("review.pull-request-fetch")
@Validated
public record PullRequestFetchProperties(
        @Min(1) @Max(3000) int maxFiles,
        @NotNull DataSize maxPatchSizePerFile,
        @NotNull DataSize maxTotalPatchSize,
        @Min(1) @Max(30) int maxPages,
        @NotNull DataSize maxPageResponseSize) {

    private static final long MAX_PATCH_BYTES = DataSize.ofMegabytes(5).toBytes();
    private static final long MAX_TOTAL_BYTES = DataSize.ofMegabytes(100).toBytes();
    private static final long MAX_RESPONSE_BYTES = DataSize.ofMegabytes(32).toBytes();

    public PullRequestFetchProperties {
        long perFile = bytes(maxPatchSizePerFile);
        long total = bytes(maxTotalPatchSize);
        long response = bytes(maxPageResponseSize);
        if (maxFiles < 1 || maxFiles > 3000
                || maxPages < 1 || maxPages > 30
                || perFile < 1 || perFile > MAX_PATCH_BYTES
                || total < perFile || total > MAX_TOTAL_BYTES
                || response < 1 || response > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("pull request fetch limits are invalid");
        }
    }

    public int maxPatchBytesPerFile() {
        return Math.toIntExact(maxPatchSizePerFile.toBytes());
    }

    public long maxTotalPatchBytes() {
        return maxTotalPatchSize.toBytes();
    }

    public int maxPageResponseBytes() {
        return Math.toIntExact(maxPageResponseSize.toBytes());
    }

    private static long bytes(DataSize value) {
        return value == null ? -1 : value.toBytes();
    }
}
