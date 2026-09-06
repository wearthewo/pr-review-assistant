package io.prreviewassistant.review.context;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("review.context") @Validated
public record ReviewContextProperties(boolean enabled, @Min(1) @Max(20) int maxFiles,
        @NotNull DataSize maxFileSize, @NotNull DataSize maxTotalSize,
        @NotNull DataSize maxChangedFileSize, @Min(1) @Max(2000) int maxLinesPerFile,
        @Min(1) @Max(200) int maxCandidates, @Min(1) @Max(30) int maxApiRequests) {
    public ReviewContextProperties {
        long file=bytes(maxFileSize), total=bytes(maxTotalSize), changed=bytes(maxChangedFileSize);
        if (maxFiles<1 || maxFiles>20 || maxLinesPerFile<1 || maxLinesPerFile>2000 || maxCandidates<1 || maxCandidates>200 || maxApiRequests<1 || maxApiRequests>30
                || file<1 || file>262144 || changed<1 || changed>262144 || total<file || total>1048576) throw new IllegalArgumentException("review context limits are invalid");
    }
    public int maxFileBytes(){return Math.toIntExact(maxFileSize.toBytes());}
    public int maxTotalBytes(){return Math.toIntExact(maxTotalSize.toBytes());}
    public int maxChangedFileBytes(){return Math.toIntExact(maxChangedFileSize.toBytes());}
    private static long bytes(DataSize d){return d==null?-1:d.toBytes();}
}
